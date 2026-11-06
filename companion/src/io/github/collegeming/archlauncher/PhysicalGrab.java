package io.github.collegeming.archlauncher;

import cn.classfun.droidvm.lib.natives.UnixHelper;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Adapted from DroidVM KeyboardGrabManager: same HostKeyboard/JNI, poll+wake and raw record path.
 * No network operation runs with this object's lock or in a reader. Local ungrab precedes callbacks.
 */
final class PhysicalGrab {
    interface Sink {void records(long generation,byte[] bytes);void released(long generation,String reason);}
    private final Sink sink;
    private final GrabLease lease=new GrabLease();
    private final Object lock=lease;
    private final Map<String,Grab> grabs=new LinkedHashMap<>();
    private long generation;
    private String reason="not requested";
    private boolean loaded;
    private final ScheduledExecutorService hotplug=Executors.newSingleThreadScheduledExecutor();
    PhysicalGrab(Sink sink){this.sink=sink;hotplug.scheduleWithFixedDelay(this::rescan,1,1,TimeUnit.SECONDS);}
    private final class Grab {
        final HostKeyboard device;final int fd,readWake,writeWake;final long session;
        final boolean[] pressed=new boolean[0x300];volatile boolean stopping;
        Grab(HostKeyboard device,int fd,int[] wake,long session){this.device=device;this.fd=fd;readWake=wake[0];writeWake=wake[1];this.session=session;}
        void stop(){if(stopping)return;stopping=true;UnixHelper.nativeEvdevGrab(fd,false);UnixHelper.nativeWrite(writeWake,new byte[]{1},1);}
        void run(){
            byte[] bytes=new byte[24*64];
            try{
                while(!stopping){
                    int ready=UnixHelper.nativePollIn2(fd,readWake,-1);if(ready<0||(ready&2)!=0)break;if((ready&1)==0)continue;
                    int length=UnixHelper.nativeRead(fd,bytes,bytes.length);if(length<=0)break;
                    GrabPolicy.Frame frame;
                    synchronized(lock){if(stopping||generation!=session||grabs.get(device.path)!=this)break;frame=GrabPolicy.convert(bytes,length,pressed);}
                    if(frame.escape){release(session,"ESCAPE_RELEASED");sink.released(session,"ESCAPE_RELEASED");return;}
                    if(frame.bytes.length>0&&!stopping)sink.records(session,frame.bytes);
                }
            }catch(Throwable ignored){}
            finally{
                boolean lost=!stopping;
                synchronized(lock){stop();grabs.remove(device.path,this);}
                UnixHelper.nativeCloseFd(fd);UnixHelper.nativeCloseFd(readWake);UnixHelper.nativeCloseFd(writeWake);
                // Hot-unplug may strand guest modifiers: fail closed rather than adopt another lease.
                if(lost){release(session,"DEVICE_LOST");sink.released(session,"DEVICE_LOST");}
            }
        }
    }
    void publish(long session){lease.publish(session,()->{stopLocked();generation=0;reason="not requested";});}
    String arm(long session){
        try{
            UnixHelper.load();loaded=UnixHelper.isLoaded();
            if(!loaded){synchronized(lock){if(lease.current(session))reason="NATIVE_HELPER_UNAVAILABLE";}return status();}
            // Publication, authorization and mutation share the very same monitor. Stale arm never stops a newer grab.
            if(!lease.arm(session,()->{generation=session;reason="armed";}))return status();
            rescan();return status();
        }catch(Throwable ignored){synchronized(lock){if(lease.current(session))reason="GRAB_UNAVAILABLE";}return status();}
    }
    private void rescan(){
        long session;synchronized(lock){session=generation;}if(session<=0||!loaded)return;
        try{
            for(HostKeyboard device:HostKeyboard.scan()){
                synchronized(lock){
                    if(generation!=session)return;if(grabs.containsKey(device.path))continue;
                    int fd=UnixHelper.nativeEvdevOpen(device.path);if(fd<0)continue;
                    // Never grab anything exposing touch contacts, even a composite keyboard node.
                    byte[] bits=UnixHelper.nativeEvdevKeyBits(fd);
                    if(!GrabPolicy.typing(bits)||GrabPolicy.has(bits,0x14a)||GrabPolicy.has(bits,0x145)){UnixHelper.nativeCloseFd(fd);continue;}
                    int[] wake=UnixHelper.nativePipe();if(wake==null||wake.length!=2){UnixHelper.nativeCloseFd(fd);continue;}
                    if(UnixHelper.nativeEvdevGrab(fd,true)!=0){UnixHelper.nativeCloseFd(fd);UnixHelper.nativeCloseFd(wake[0]);UnixHelper.nativeCloseFd(wake[1]);continue;}
                    Grab grab=new Grab(device,fd,wake,session);grabs.put(device.path,grab);
                    Thread reader=new Thread(grab::run,"companion-evdev-"+new java.io.File(device.path).getName());reader.setDaemon(true);reader.start();
                }
            }
        }catch(Throwable ignored){reason="GRAB_SCAN_FAILED";}
    }
    void release(long session,String why){lease.revoke(session,()->{stopLocked();generation=0;reason=why;});}
    private void stopLocked(){for(Grab grab:grabs.values())grab.stop();grabs.clear();}
    boolean held(String name,int vendor,int product){synchronized(lock){for(Grab grab:grabs.values())if(!grab.stopping&&grab.device.name.equals(name)&&grab.device.vendor==vendor&&grab.device.product==product)return true;return false;}}
    String status(){
        try{synchronized(lock){JSONObject out=new JSONObject().put("generation",generation).put("reason",reason).put("implemented",true);JSONArray held=new JSONArray();for(Grab grab:grabs.values())if(!grab.stopping)held.put(grab.device.toJson());return out.put("held",held).toString();}}
        catch(Exception ignored){return "{\"held\":[],\"reason\":\"STATUS_ERROR\"}";}
    }
}
