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
    private final ExecutorService io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64));
    // Immediate revocation is independent of IO progress; no network call holds a state monitor.
    private final Set<Long> revoked=ConcurrentHashMap.newKeySet();
    private volatile long publishedGeneration;
    private final Set<Integer>[] held=new Set[]{new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>()};
    private final Set<Integer> modifiers=new HashSet<>(),touchSlots=new HashSet<>();
    private final boolean[] dirty=new boolean[4];
    private final BrokerGate gate=new BrokerGate();
    private int touchSlot;
    private String vm;
    private boolean releaseFailed;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private final int uid;
    private final String token;
    private InputBroker(int uid,String token){this.uid=uid;this.token=token;}
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
    private void queue(Runnable work){try{io.execute(work);}catch(RejectedExecutionException ignored){}}
    private boolean send(int channel,byte[] bytes,MonotonicDeadline budget){
        if(budget.millis()==0)return false;
        try{return daemon.request("vm_input",new JSONObject().put("vm_id",vm).put("screen","gpu-0").put("channel",channel)
            .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)),budget).optBoolean("delivered");}catch(Exception ignored){return false;}
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
                if(value!=0&&SessionPolicy.escape(code,modifiers.contains(29),modifiers.contains(97),modifiers.contains(56),modifiers.contains(100))){gate.pause(session,BrokerGate.ESCAPE);revoked.add(session);release(new MonotonicDeadline(RELEASE_MS));return BrokerGate.ESCAPE;}
            }
        }
        data.rewind();while(data.remaining()>=8){
            int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
            if(type==1&&value!=0)held[channel].add(code);
            if(channel==0&&type==3){if(code==0x2f)touchSlot=value;else if(code==0x39&&value>=0)touchSlots.add(touchSlot);}
        }
        dirty[channel]=true;
        if(!send(channel,bytes,budget)){
            gate.pause(session,BrokerGate.NOT_DELIVERED);revoked.add(session);
            // Network budget already consumed: cleanup gets its own bounded queue job, not this call.
            MonotonicDeadline cleanup=new MonotonicDeadline(RELEASE_MS);queue(()->{if(gate.current(session))release(cleanup);});return BrokerGate.NOT_DELIVERED;
        }
        data.rewind();while(data.remaining()>=8){
            int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
            if(type==1&&value==0)held[channel].remove(code);
            if(channel==0&&type==3){if(code==0x2f)touchSlot=value;else if(code==0x39&&value<0)touchSlots.remove(touchSlot);}
        }
        if(revoked.contains(session))return BrokerGate.PAUSED; // release job queued behind this write
        return BrokerGate.DELIVERED;
    }
    private IInputBroker binder(){return new IInputBroker.Stub(){
        public long attach(String supplied,String target,IBinder lifetime){
            check(supplied);if(!SessionPolicy.allowed(target)||lifetime==null)throw new SecurityException("target denied");
            MonotonicDeadline budget=new MonotonicDeadline(5000);
            long result=call(budget,()->{
                if(budget.millis()==0)return 0L;
                MonotonicDeadline cleanup=new MonotonicDeadline(Math.min(RELEASE_MS,budget.millis()));
                if(gate.generation()!=0)retire(gate.generation(),cleanup);
                if(releaseFailed){release(cleanup);if(releaseFailed)return 0L;}
                try{
                    JSONObject config=daemon.request("vm_get",new JSONObject().put("vm_id",target),budget).getJSONObject("data");
                    JSONObject screen=config.getJSONObject("screens").getJSONObject("gpu-0");
                    if(!target.equals(config.getString("id"))||!"CROSVM".equals(config.getString("backend"))||!screen.optBoolean("enabled")||!screen.optBoolean("input_enabled")||!"native".equals(screen.optString("exporter")))return 0L;
                    if(!"running".equals(daemon.request("vm_status",new JSONObject().put("vm_id",target),budget).getString("state")))return 0L;
                    if(budget.millis()==0)return 0L;
                    vm=target;long captured=gate.attach();publishedGeneration=captured;client=lifetime;
                    death=()->{revoked.add(captured);MonotonicDeadline deathBudget=new MonotonicDeadline(RELEASE_MS);queue(()->retire(captured,deathBudget));};
                    try{client.linkToDeath(death,0);}catch(RemoteException ignored){retire(captured,new MonotonicDeadline(RELEASE_MS));return 0L;}
                    return captured;
                }catch(Exception ignored){return 0L;}
            },0L);
            if(result==0)throw new IllegalStateException("E_VM_NOT_READY_OR_RELEASE_UNCONFIRMED");return result;
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
        public void releaseAll(String supplied,long session){
            check(supplied);revoked.add(session);MonotonicDeadline budget=new MonotonicDeadline(RELEASE_MS);
            call(budget,()->{if(gate.pause(session,BrokerGate.PAUSED))release(budget);return true;},false);
        }
        public void detach(String supplied,long session){
            check(supplied);revoked.add(session);MonotonicDeadline budget=new MonotonicDeadline(RELEASE_MS);
            call(budget,()->{retire(session,budget);return true;},false);
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
            long g=owner.publishedGeneration;owner.revoked.add(g);MonotonicDeadline budget=new MonotonicDeadline(RELEASE_MS);
            owner.call(budget,()->{owner.retire(g,budget);return true;},false);System.exit(0);
        }},"broker-owner").start();
        handler.postDelayed(()->{if(owner.publishedGeneration==0)System.exit(0);},15000);Looper.loop();
    }
}
