package io.github.collegeming.archlauncher;

import android.content.*;
import android.os.*;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Root bridge to the managed daemon. Mutable transport state belongs to one bounded IO worker. */
public final class InputBroker {
    static final String PACKAGE="io.github.collegeming.archlauncher", ACTION=PACKAGE+".BROKER";
    private static final int RELEASE_MS=1500;
    private final DaemonClient daemon=new DaemonClient();
    private final BrokerIo io=new BrokerIo(this::cleanNow);
    // Immediate revocation is independent of IO progress; no network call holds a state monitor.
    private final Set<Long> revoked=ConcurrentHashMap.newKeySet();
    private volatile long publishedGeneration;
    private volatile boolean attachedOnce,closing;
    private static final String LAUNCHER="/data/data/cn.classfun.droidvm/usr/bin/crosvm";
    private final Set<Integer>[] held=new Set[]{new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>()};
    private final Set<Integer> modifiers=new HashSet<>(),touchSlots=new HashSet<>();
    private final boolean[] dirty=new boolean[4];
    private final BrokerGate gate=new BrokerGate();
    private int touchSlot;
    private String vm;
    private boolean releaseFailed;
    private int sendResult=BrokerGate.NOT_DELIVERED;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private final int uid;
    private final String token;
    private final PhysicalGrab physical;
    private InputBroker(int uid,String token){
        this.uid=uid;this.token=token;
        physical=new PhysicalGrab(new PhysicalGrab.Sink(){
            public void records(long session,byte[] bytes){
                if(revoked.contains(session))return;
                MonotonicDeadline budget=new MonotonicDeadline(1500);
                try{io.execute(()->{int result=writeNow(session,1,bytes,budget);if(result!=BrokerGate.DELIVERED){physical.release(session,"RAW_DELIVERY_FAILED");requestCleanup(session,result,false);}});}
                catch(RejectedExecutionException ignored){physical.release(session,"RAW_QUEUE_FULL");requestCleanup(session,BrokerGate.PAUSED,false);}
            }
            public void released(long session,String reason){
                requestCleanup(session,"ESCAPE_RELEASED".equals(reason)?BrokerGate.ESCAPE:BrokerGate.PAUSED,false);
            }
        });
    }
    private void check(String value){
        if(Binder.getCallingUid()!=uid||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new SecurityException("session denied");
    }
    private <T>T call(MonotonicDeadline budget,Callable<T> work,T failure){
        try{
            if(budget.millis()==0)return failure;
            Future<T> future=io.submit(work);
            // Do not cancel cleanup on caller timeout: queued cleanup must mark unconfirmed state.
            return future.get(Math.max(1,budget.nanos()),TimeUnit.NANOSECONDS);
        }catch(Exception ignored){return failure;}
    }
    private void requestCleanup(long session,int reason,boolean retire){
        revoked.add(session);io.cleanup(session,reason,retire,new MonotonicDeadline(RELEASE_MS));
    }
    private void cleanNow(BrokerIo.Cleanup cleanup){
        if(!gate.current(cleanup.generation))return;
        if(cleanup.retire)retire(cleanup.generation,cleanup.budget);
        else if(gate.pause(cleanup.generation,cleanup.reason))release(cleanup.budget);
    }
    private boolean send(int channel,byte[] bytes,MonotonicDeadline budget){
        sendResult=BrokerGate.IPC_ERROR;
        if(budget.millis()==0)return false;
        try{
            JSONObject response=daemon.request("vm_input",new JSONObject().put("vm_id",vm).put("screen","gpu-0").put("channel",channel)
                .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)),budget);
            if(!response.has("delivered")){sendResult=BrokerGate.ACK_MISSING;return false;}
            boolean ok=response.getBoolean("delivered");sendResult=ok?BrokerGate.DELIVERED:BrokerGate.NOT_DELIVERED;return ok;
        }catch(RpcFailure failure){sendResult=(int)failure.code;return false;}catch(Exception ignored){return false;}
    }
    private static void record(ByteArrayOutputStream out,int type,int code,int value){byte[] bytes=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short)type).putShort((short)code).putInt(value).array();out.write(bytes,0,bytes.length);}
    private void release(MonotonicDeadline budget){
        // Future raw grab must ungrab local descriptors BEFORE this bounded guest cleanup.
        if(vm==null)return;
        releaseFailed=false;
        for(int ch=0;ch<4;ch++){
            if(!dirty[ch])continue;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int key:held[ch])record(out,1,key,0);
            if(ch==0){for(int slot:touchSlots){record(out,3,0x2f,slot);record(out,3,0x39,-1);}record(out,1,0x14a,0);}
            if(ch==2||ch==3)for(int button=0x110;button<=0x114;button++)record(out,1,button,0);
            record(out,0,0,0);
            if(send(ch,out.toByteArray(),budget)){held[ch].clear();dirty[ch]=false;if(ch==0){touchSlots.clear();touchSlot=0;}}
            else releaseFailed=true;
        }
        modifiers.clear();
    }
    private void retire(long expected,MonotonicDeadline budget){
        if(!gate.current(expected))return;
        physical.release(expected,"SESSION_RETIRED");
        gate.pause(expected,BrokerGate.PAUSED);release(budget);gate.retire(expected);publishedGeneration=0;
        if(client!=null&&death!=null)client.unlinkToDeath(death,0);client=null;death=null;
        // Keep the old VM/dirty state if cleanup was not acknowledged: a new attach must retry it.
        if(!releaseFailed)vm=null;
        revoked.remove(expected);
    }
    private int writeNow(long session,int channel,byte[] bytes,MonotonicDeadline budget){
        if(budget.millis()==0)return BrokerGate.IPC_ERROR;
        int state=gate.status(session);if(state!=0)return state;
        if(revoked.contains(session))return BrokerGate.PAUSED;
        if(channel<0||channel>3||bytes==null||bytes.length==0||bytes.length>8192||bytes.length%8!=0)return BrokerGate.INVALID;
        ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while(data.remaining()>=8){
            int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
            if(type>3)return BrokerGate.INVALID;
            if(channel==1&&type==1){
                if(value==0)modifiers.remove(code);else modifiers.add(code);
                if(value!=0&&SessionPolicy.escape(code,modifiers.contains(29),modifiers.contains(97),modifiers.contains(56),modifiers.contains(100))){physical.release(session,"ESCAPE_RELEASED");gate.pause(session,BrokerGate.ESCAPE);revoked.add(session);release(new MonotonicDeadline(RELEASE_MS));return BrokerGate.ESCAPE;}
            }
        }
        data.rewind();while(data.remaining()>=8){
            int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
            if(type==1&&value!=0)held[channel].add(code);
            if(channel==0&&type==3){if(code==0x2f)touchSlot=value;else if(code==0x39&&value>=0)touchSlots.add(touchSlot);}
        }
        dirty[channel]=true;
        if(!send(channel,bytes,budget)){
            int failure=sendResult;physical.release(session,"INPUT_DELIVERY_FAILED");gate.pause(session,failure);revoked.add(session);
            // Preserve the original ACK/transport cause before cleanup attempts another send.
            requestCleanup(session,failure,false);return failure;
        }
        data.rewind();while(data.remaining()>=8){
            int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
            if(type==1&&value==0)held[channel].remove(code);
            if(channel==0&&type==3){if(code==0x2f)touchSlot=value;else if(code==0x39&&value<0)touchSlots.remove(touchSlot);}
        }
        if(revoked.contains(session))return BrokerGate.PAUSED; // release job queued behind this write
        return BrokerGate.DELIVERED;
    }
    private static String[] strings(org.json.JSONArray array)throws Exception{String[] out=new String[array.length()];for(int i=0;i<out.length;i++)out[i]=array.getString(i);return out;}
    private boolean configured(String target,MonotonicDeadline budget)throws Exception{
        JSONObject config=daemon.request("vm_get",new JSONObject().put("vm_id",target),budget,5000).getJSONObject("data");
        JSONObject screen=config.getJSONObject("screens").getJSONObject("gpu-0");
        return target.equals(config.getString("id"))&&"CROSVM".equals(config.getString("backend"))&&screen.optBoolean("enabled")&&screen.optBoolean("input_enabled")&&"native".equals(screen.optString("exporter"))
            &&StartPolicy.configured(target,strings(config.getJSONArray("environment_variables")),strings(config.getJSONArray("extra_options")));
    }
    private static String dispatcherHash()throws Exception{
        // Small script, read-only. No shell command, mutable executable invocation or hash fallback.
        java.nio.file.Path path=java.nio.file.Path.of(LAUNCHER);
        if(!java.nio.file.Files.isRegularFile(path)||java.nio.file.Files.size(path)>1024*1024)return "";
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=java.nio.file.Files.newInputStream(path)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private IInputBroker binder(){return new IInputBroker.Stub(){
        public long attach(String supplied,String target,IBinder lifetime,boolean allowStart){
            check(supplied);if(!SessionPolicy.allowed(target)||lifetime==null)throw new SecurityException("target denied");
            attachedOnce=true; // startup may exceed the binder-announcement timeout; client pipe owns it
            MonotonicDeadline budget=new MonotonicDeadline(allowStart?45000:12000);
            return call(budget,()->{
                if(budget.millis()==0)return ManagedStart.START_TIMEOUT;
                MonotonicDeadline cleanup=new MonotonicDeadline(Math.min(RELEASE_MS,budget.millis()));
                if(gate.generation()!=0)retire(gate.generation(),cleanup);
                if(releaseFailed){release(cleanup);if(releaseFailed)return ManagedStart.RELEASE_UNCONFIRMED;}
                long ready=ManagedStart.ensure(new ManagedStart.Host(){
                    public boolean configured()throws Exception{if(closing)throw new IOException();return InputBroker.this.configured(target,budget);}
                    public String dispatcherHash()throws Exception{return InputBroker.dispatcherHash();}
                    public void ping()throws Exception{daemon.request("ping",new JSONObject(),budget,5000);}
                    public String state()throws Exception{if(closing)throw new IOException();return daemon.request("vm_status",new JSONObject().put("vm_id",target),budget,5000).getString("state");}
                    public void start()throws Exception{if(closing)throw new IOException();daemon.request("vm_start",new JSONObject().put("vm_id",target),budget,20000);}
                    public void sleep()throws Exception{Thread.sleep(Math.min(250,budget.millis()));}
                },allowStart,budget);
                if(ready<0)return ready;
                if(budget.millis()==0)return ManagedStart.START_TIMEOUT;
                vm=target;long captured=gate.attach();physical.publish(captured);publishedGeneration=captured;client=lifetime;
                death=()->{physical.release(captured,"CLIENT_DIED");requestCleanup(captured,BrokerGate.PAUSED,true);};
                try{client.linkToDeath(death,0);}catch(RemoteException ignored){retire(captured,new MonotonicDeadline(RELEASE_MS));return ManagedStart.NOT_READY;}
                return captured;
            },ManagedStart.START_TIMEOUT);
        }
        public IBinder display(String supplied,long session){
            check(supplied);MonotonicDeadline budget=new MonotonicDeadline(500);
            return call(budget,()->{
                if(!gate.current(session)||budget.millis()==0)return null;
                try{return (IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"droidvm_disp_"+vm+"_gpu-0");}catch(Exception ignored){return null;}
            },null);
        }
        public int write(String supplied,long session,int channel,byte[] bytes){
            check(supplied);if(revoked.contains(session))return BrokerGate.PAUSED;
            MonotonicDeadline budget=new MonotonicDeadline(2500);return call(budget,()->writeNow(session,channel,bytes,budget),BrokerGate.IPC_ERROR);
        }
        public String keyboardGrab(String supplied,long session,boolean enabled){
            check(supplied);
            if(!enabled){physical.release(session,"DISABLED");return physical.status();}
            // PhysicalGrab.arm authorizes and mutates atomically under the publication/revocation lease.
            return physical.arm(session);
        }
        public String grabStatus(String supplied,long session){
            check(supplied);return physical.status();
        }
        public int physicalKey(String supplied,long session,String name,int vendor,int product,byte[] bytes){
            check(supplied);
            if(physical.held(name,vendor,product))return BrokerGate.HELD_SUPPRESSED;
            MonotonicDeadline budget=new MonotonicDeadline(2500);
            return call(budget,()->{
                if(physical.held(name,vendor,product))return BrokerGate.HELD_SUPPRESSED;
                return writeNow(session,1,bytes,budget);
            },BrokerGate.IPC_ERROR);
        }
        public void releaseAll(String supplied,long session){
            check(supplied);physical.release(session,"CLIENT_RELEASED");requestCleanup(session,BrokerGate.PAUSED,false);
        }
        public void detach(String supplied,long session){
            check(supplied);physical.release(session,"CLIENT_RELEASED");requestCleanup(session,BrokerGate.PAUSED,true);
        }
    };}
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=0||args.length!=1)throw new SecurityException("root required");
        int uid=Integer.parseInt(args[0]);BufferedReader control=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.US_ASCII));
        String token=control.readLine();if(token==null||!token.matches("[0-9a-f]{64}"))throw new SecurityException("token required");
        Looper.prepareMainLooper();Context context=(Context)Class.forName("android.app.ActivityThread").getMethod("getSystemContext").invoke(Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null));
        if(context.getPackageManager().getApplicationInfo(PACKAGE,0).uid!=uid)throw new SecurityException("UID mismatch");
        InputBroker owner=new InputBroker(uid,token);Bundle session=new Bundle();session.putBinder("binder",owner.binder().asBinder());session.putString("token",token);
        Handler handler=new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable(){public void run(){if(owner.publishedGeneration!=0)return;context.sendBroadcast(new Intent(ACTION).setPackage(PACKAGE).putExtra("session",session));handler.postDelayed(this,500);}},250);
        new Thread(()->{try{while(control.readLine()!=null){}}catch(Exception ignored){}finally{
            owner.closing=true;long g=owner.publishedGeneration;owner.physical.release(g,"OWNER_PIPE_CLOSED");owner.requestCleanup(g,BrokerGate.PAUSED,true);
            try{Thread.sleep(RELEASE_MS);}catch(InterruptedException ignored){}System.exit(0);
        }},"broker-owner").start();
        handler.postDelayed(()->{if(!owner.attachedOnce)System.exit(0);},15000);Looper.loop();
    }
}
