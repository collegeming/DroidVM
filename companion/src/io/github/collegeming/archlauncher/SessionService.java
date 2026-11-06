package io.github.collegeming.archlauncher;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.concurrent.*;

/** Foreground session ownership survives opening Settings; disconnect never stops the VM. */
public final class SessionService extends Service {
    static SessionService active;
    static final String RELEASE="release", DISCONNECT="disconnect", CONNECT="connect";
    final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private IInputBroker broker;
    private final IBinder lifetime=new Binder();
    private java.lang.Process process;
    private OutputStream ownerPipe;
    private String token;
    private int route;
    private volatile long generation;
    private final InputLease lease=new InputLease();
    private boolean manual, connected, starting, stopped;
    private long launchId;
    private volatile String detail="未连接";
    private volatile boolean delivered;
    private int lastResult;
    private boolean inputFault;
    private long localDrops;
    private boolean receiverRegistered;
    private DisplayActivity display;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        Bundle bundle=intent.getBundleExtra("session");
        if(bundle==null||!String.valueOf(token).equals(bundle.getString("token")))return;
        IBinder binder=bundle.getBinder("binder"); if(binder==null||broker!=null)return;
        broker=IInputBroker.Stub.asInterface(binder);
        try{binder.linkToDeath(()->main.post(()->{broker=null;setError("E_BROKER_DIED");}),0);}catch(Exception ignored){setError("E_BROKER_DIED");return;}
        prepare(false);
    }};
    public IBinder onBind(Intent intent){return null;}
    public void onCreate(){super.onCreate();active=this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("session","Arch Linux connection",NotificationManager.IMPORTANCE_LOW));
        registerReceiver(receiver,new IntentFilter(InputBroker.ACTION),"android.permission.DUMP",null,Context.RECEIVER_EXPORTED); receiverRegistered=true;
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        notifyState();
        String action=intent==null?null:intent.getAction();
        if(DISCONNECT.equals(action)){disconnect();return START_NOT_STICKY;}
        if(RELEASE.equals(action)){manual=true;gate(false);detail="输入已手动释放；请在设置显式恢复";changed();return START_NOT_STICKY;}
        if(CONNECT.equals(action)) {
            int requested=SessionPolicy.route(intent.getIntExtra("route",0));
            if(process==null&&!starting) {route=requested;manual=false;startBroker();}
            else if(requested!=route) {detail="切换路线前请先断开；VM不会被关闭";changed();}
            else {manual=false;inputFault=false;if(broker!=null&&!connected&&!lease.pending())prepare(false);else if(display!=null)display.refreshGate();changed();}
        }
        return START_NOT_STICKY;
    }
    private void startBroker(){
        final long launch=++launchId;starting=true;
        detail="正在连接 root bridge（仅已运行的管理VM）";changed();
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));token=out.toString();
        String apk=getApplicationInfo().sourceDir;
        if(apk.contains("'")){setError("E_APK_PATH");return;}
        control.execute(()->{
            try{
                java.lang.Process next=new ProcessBuilder("su","-M","-c","CLASSPATH='"+apk+"' /system/bin/app_process /system/bin "+InputBroker.class.getName()+" "+android.os.Process.myUid())
                    .redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
                OutputStream pipe=next.getOutputStream();pipe.write((token+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));pipe.flush();
                main.post(()->{if(stopped||launch!=launchId){try{pipe.close();}catch(Exception ignored){}return;}starting=false;process=next;ownerPipe=pipe;});
                new Thread(()->{try{next.waitFor();}catch(InterruptedException ignored){}main.post(()->{if(process==next){process=null;setError("E_ROOT_BRIDGE_EXIT");}});},"root-exit").start();
            }catch(Exception ignored){main.post(()->{starting=false;if(!stopped&&launch==launchId)setError("E_ROOT_START");});}
        });
        main.postDelayed(()->{if(!stopped&&launch==launchId&&broker==null)setError("E_ROOT_TIMEOUT");},12000);
    }
    private void releaseRemote(IInputBroker b,String t,long g){
        if(b!=null&&g>0)try{control.execute(()->{try{b.releaseAll(t,g);}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private void prepare(boolean enable){
        final IInputBroker b=broker;final String t=token;
        if(b==null||lease.pending()||stopped)return;
        final InputLease.Attempt attempt=lease.begin(enable);
        if(display!=null)display.clearInput();
        control.execute(()->{
            try{
                long next=b.attach(t,SessionPolicy.IDS[route],lifetime);
                main.post(()->{
                    if(stopped||broker!=b){lease.fail(attempt);releaseRemote(b,t,next);return;}
                    if(!lease.complete(attempt,next)){releaseRemote(b,t,next);return;}
                    generation=next;connected=true;
                    if(!lease.current(lease.ticket()))releaseRemote(b,t,next);
                    detail=canInput()?"管理VM已连接":"管理VM已连接，输入暂停";changed();
                    if(display!=null)display.sessionReady();
                });
            }catch(Exception ignored){main.post(()->{if(lease.fail(attempt)&&!stopped)setError("E_VM_NOT_READY（请由管理端启动此路线）");});}
        });
    }
    void attachDisplay(DisplayActivity activity){if(display!=activity){gate(false);display=activity;}if(connected)activity.sessionReady();changed();}
    void removeDisplay(DisplayActivity activity){if(display==activity){gate(false);display=null;}}
    boolean canInput(){return connected&&!inputBlocked()&&lease.current(lease.ticket());}
    boolean inputBlocked(){return manual||inputFault;}
    boolean isConnected(){return connected;}
    boolean isManual(){return manual;}
    int route(){return route;}
    void gate(boolean allowed){
        if(!allowed||inputBlocked()){
            lease.close();
            if(display!=null)display.clearInput();
            releaseRemote(broker,token,generation);
            changed();
        }else{
            lease.want(true);
            if(connected&&!canInput()&&!lease.pending()&&broker!=null)prepare(true);
        }
    }
    android.os.IBinder displayBinder(){
        IInputBroker b=broker;String t=token;long g=generation;
        try{return b==null?null:b.display(t,g);}catch(Exception ignored){return null;}
    }
    InputForwarder newForwarder(){
        final IInputBroker b=broker;final String t=token;final InputLease.Ticket ticket=lease.ticket();
        if(!lease.current(ticket))return null;
        final long g=ticket.generation;
        final java.util.concurrent.atomic.AtomicBoolean cleanupQueued=new java.util.concurrent.atomic.AtomicBoolean();
        InputForwarder forwarder=new InputForwarder((channel,bytes)->{
            if(!lease.current(ticket)){main.post(()->{localDrops++;});return false;}
            int result;try{result=b.write(t,g,channel,bytes);}catch(Exception ignored){result=BrokerGate.IPC_ERROR;}
            final int outcome=result;
            boolean ok=result==BrokerGate.DELIVERED;
            // close may race a blocking write; enqueue cleanup of this exact generation after it.
            if(!lease.current(ticket)){if(cleanupQueued.compareAndSet(false,true))releaseRemote(b,t,g);return false;}
            main.post(()->{
                if(!lease.current(ticket))return;
                delivered=outcome==BrokerGate.DELIVERED;lastResult=outcome;
                if(outcome!=BrokerGate.DELIVERED){
                    // A stale worker is not an escape key or a user-requested manual release.
                    inputFault=outcome!=BrokerGate.STALE;
                    if(outcome==BrokerGate.ESCAPE)manual=true;
                    gate(false);
                    detail="输入暂停 ["+BrokerGate.label(outcome)+"] · ch="+channel+" · generation="+g+"；设置中可显式恢复";
                    changed();
                }
            });
            return ok;
        });
        int mode=Math.max(0,Math.min(2,getSharedPreferences("settings",0).getInt("mode",0)));
        forwarder.setInputMode(InputMode.values()[mode]);
        return forwarder;
    }
    String status(){return SessionPolicy.NAMES[route]+"\n"+detail+"\n输入: "+(canInput()?"普通转发就绪":"已暂停")+" · 最近投递: "+(delivered?"成功":"未确认")+" · lease关闭丢弃: "+localDrops+"\nexclusive配置: "+(getSharedPreferences("settings",0).getBoolean("exclusive",true)?"开启":"关闭")+" · 实际held: 否（grab未完成）";}
    private void setError(String code){if(stopped)return;connected=false;gate(false);detail=code;changed();}
    private void changed(){if(stopped)return;notifyState();if(display!=null)display.updateStatus();}
    private PendingIntent activity(Class<?> cls,int id){return PendingIntent.getActivity(this,id,new Intent(this,cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private PendingIntent action(String action,int id){return PendingIntent.getService(this,id,new Intent(this,SessionService.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private void notifyState(){
        Notification.Builder n=new Notification.Builder(this,"session").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(SessionPolicy.NAMES[route]).setContentText(detail)
            .setContentIntent(activity(SettingsActivity.class,2)).setSubText("点击通知返回设置").setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"返回显示",activity(DisplayActivity.class,1)).build())
            .addAction(new Notification.Action.Builder(null,"释放输入",action(RELEASE,3)).build())
            .addAction(new Notification.Action.Builder(null,"断开（不关VM）",action(DISCONNECT,4)).build());
        startForeground(1,n.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
    private void disconnect(){
        stopped=true;++launchId;starting=false;
        connected=false;gate(false);
        if(display!=null)display.disconnected();
        try{if(ownerPipe!=null)ownerPipe.close();}catch(Exception ignored){}
        // Closing ownership pipe makes root release guest state and exit; never kill crosvm.
        ownerPipe=null;process=null;broker=null;token=null;detail="已断开，VM继续运行";
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    public void onDestroy(){if(receiverRegistered)unregisterReceiver(receiver);if(active==this)active=null;
        try{if(ownerPipe!=null)ownerPipe.close();}catch(Exception ignored){}
        control.shutdown();super.onDestroy();}
}
