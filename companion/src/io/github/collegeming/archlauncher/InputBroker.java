package io.github.collegeming.archlauncher;

import android.content.*;
import android.os.*;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Root bridge to the existing managed daemon. Never owns crosvm, input sockets or a VMM. */
public final class InputBroker {
    static final String PACKAGE = "io.github.collegeming.archlauncher";
    static final String ACTION = PACKAGE + ".BROKER";
    private final DaemonClient daemon = new DaemonClient();
    private final Set<Integer>[] held = new Set[]{new HashSet<>(),new HashSet<>(),new HashSet<>(),new HashSet<>()};
    private final Set<Integer> modifiers = new HashSet<>();
    private final boolean[] dirty=new boolean[4];
    private long sequence, generation;
    private String vm;
    private boolean released, releaseFailed;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private final int uid;
    private final String token;
    private final Handler main = new Handler(Looper.getMainLooper());
    private InputBroker(int uid, String token) { this.uid=uid; this.token=token; }
    private void check(String value) {
        if(Binder.getCallingUid()!=uid || !java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new SecurityException("session denied");
    }
    private boolean valid(long supplied) { return SessionPolicy.current(generation,supplied); }
    private boolean send(int channel, byte[] bytes) {
        try {
            return daemon.request("vm_input",new JSONObject().put("vm_id",vm).put("screen","gpu-0")
                .put("channel",channel).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))).optBoolean("delivered");
        } catch(Exception ignored) { return false; }
    }
    private static void record(ByteArrayOutputStream out,int type,int code,int value) {
        byte[] bytes=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short)type).putShort((short)code).putInt(value).array();
        out.write(bytes,0,bytes.length);
    }
    private void release() {
        if(vm==null)return;
        releaseFailed=false;
        for(int ch=0;ch<4;ch++) {
            if(!dirty[ch])continue;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int key:held[ch])record(out,1,key,0);
            if(ch==0) {
                for(int slot=0;slot<32;slot++){record(out,3,0x2f,slot);record(out,3,0x39,-1);}
                record(out,1,0x14a,0);
            }
            if(ch==2 || ch==3)for(int button=0x110;button<=0x114;button++)record(out,1,button,0);
            record(out,0,0,0);
            if(send(ch,out.toByteArray())){held[ch].clear();dirty[ch]=false;}else releaseFailed=true;
        }
        modifiers.clear();
    }
    private synchronized void retire(long expected) {
        if(!valid(expected))return;
        released=true;
        release();
        generation=0;
        if(client!=null && death!=null)client.unlinkToDeath(death,0);
        client=null; death=null; vm=null;
    }
    private IInputBroker binder() {
        return new IInputBroker.Stub() {
            public long attach(String supplied,String target,IBinder lifetime) throws RemoteException {
                check(supplied);
                synchronized(InputBroker.this) {
                    if(!SessionPolicy.allowed(target)||lifetime==null)throw new SecurityException("target denied");
                    if(generation!=0)retire(generation);
                    if(releaseFailed)throw new IllegalStateException("E_RELEASE_UNCONFIRMED");
                    try {
                        JSONObject config=daemon.request("vm_get",new JSONObject().put("vm_id",target)).getJSONObject("data");
                        JSONObject screen=config.getJSONObject("screens").getJSONObject("gpu-0");
                        if(!target.equals(config.getString("id"))||!"CROSVM".equals(config.getString("backend"))||!screen.optBoolean("enabled")||!screen.optBoolean("input_enabled")||!"native".equals(screen.optString("exporter")))throw new IOException();
                        if(!"running".equals(daemon.request("vm_status",new JSONObject().put("vm_id",target)).getString("state")))throw new IOException();
                    } catch(Exception ignored) { throw new IllegalStateException("E_VM_NOT_READY"); }
                    vm=target; generation=++sequence; released=false;
                    final long captured=generation;
                    client=lifetime;
                    death=()->{synchronized(InputBroker.this){if(!valid(captured))return;retire(captured);}};
                    try { client.linkToDeath(death,0); }
                    catch(RemoteException e){retire(captured);throw e;}
                    return captured;
                }
            }
            public IBinder display(String supplied,long session) {
                check(supplied);
                synchronized(InputBroker.this) {
                    if(!valid(session))return null;
                    try {return (IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class)
                        .invoke(null,"droidvm_disp_"+vm+"_gpu-0");}catch(Exception ignored){return null;}
                }
            }
            public boolean write(String supplied,long session,int channel,byte[] bytes) {
                check(supplied);
                synchronized(InputBroker.this) {
                    if(!valid(session)||released||channel<0||channel>3||bytes==null||bytes.length==0||bytes.length>8192||bytes.length%8!=0)return false;
                    ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    while(data.remaining()>=8) {
                        int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
                        if(type>3)return false;
                        if(channel==1 && type==1) {
                            if(value==0)modifiers.remove(code);else modifiers.add(code);
                            if(value!=0 && SessionPolicy.escape(code,modifiers.contains(29),modifiers.contains(97),modifiers.contains(56),modifiers.contains(100))) {
                                released=true;release();return false;
                            }
                        }
                    }
                    // Track before send: a failed write may still have partially reached crosvm.
                    data.rewind();
                    while(data.remaining()>=8) {
                        int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
                        if(type==1 && value!=0)held[channel].add(code);
                    }
                    dirty[channel]=true;
                    if(!send(channel,bytes)){released=true;release();return false;}
                    data.rewind();
                    while(data.remaining()>=8) {
                        int type=data.getShort()&65535,code=data.getShort()&65535,value=data.getInt();
                        if(type==1 && value==0)held[channel].remove(code);
                    }
                    return true;
                }
            }
            public void releaseAll(String supplied,long session){check(supplied);synchronized(InputBroker.this){if(valid(session)){released=true;release();}}}
            public void detach(String supplied,long session){check(supplied);retire(session);main.postDelayed(()->System.exit(0),50);}
        };
    }
    public static void main(String[] args)throws Exception {
        if(android.os.Process.myUid()!=0 || args.length!=1)throw new SecurityException("root required");
        int uid=Integer.parseInt(args[0]);
        BufferedReader control=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.US_ASCII));
        String token=control.readLine();
        if(token==null||!token.matches("[0-9a-f]{64}"))throw new SecurityException("token required");
        Looper.prepareMainLooper();
        Context context=(Context)Class.forName("android.app.ActivityThread").getMethod("getSystemContext").invoke(Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null));
        if(context.getPackageManager().getApplicationInfo(PACKAGE,0).uid!=uid)throw new SecurityException("UID mismatch");
        InputBroker owner=new InputBroker(uid,token);
        Bundle session=new Bundle(); session.putBinder("binder",owner.binder().asBinder());session.putString("token",token);
        // Retry only until attach; broadcasts are constrained to the package and checked by nonce.
        Handler handler=new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable(){public void run(){synchronized(owner){if(owner.generation!=0)return;}
            context.sendBroadcast(new Intent(ACTION).setPackage(PACKAGE).putExtra("session",session));handler.postDelayed(this,500);}},250);
        new Thread(()->{try{while(control.readLine()!=null){}}catch(Exception ignored){}finally{synchronized(owner){if(owner.generation!=0)owner.retire(owner.generation);}System.exit(0);}},"broker-owner").start();
        handler.postDelayed(()->{synchronized(owner){if(owner.generation==0)System.exit(0);}},15000);
        Looper.loop();
    }
}
