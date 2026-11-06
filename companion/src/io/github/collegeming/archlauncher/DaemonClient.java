package io.github.collegeming.archlauncher;

import org.json.JSONException;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Root only. One RPC at a time; the independent reader also drains unsolicited idle events. */
final class DaemonClient {
    private static final int MAX_FRAME=1024*1024;
    private final Object rpcLock=new Object(),stateLock=new Object();
    private Connection connection;
    private Pending pending;
    private long failure;
    private static final ScheduledThreadPoolExecutor watchdog=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"companion-rpc-watchdog");t.setDaemon(true);return t;});
    static { watchdog.setRemoveOnCancelPolicy(true); }
    private static final class Connection {
        final Socket socket=new Socket();
        boolean readerStarted;
    }
    private static final class Pending {
        final String id=UUID.randomUUID().toString();
        final long phase;
        final CountDownLatch ready=new CountDownLatch(1);
        JSONObject response;
        RpcFailure error;
        boolean done;
        Pending(String command){phase=RpcFailure.command(command);}
        void fail(RpcFailure failed){error=failed;done=true;ready.countDown();}
    }
    private final Path base;
    DaemonClient(){this(Path.of("/data/data/cn.classfun.droidvm/run/"));}
    // Package-private seam for an offline loopback daemon; production still uses the fixed root path.
    DaemonClient(Path base){this.base=base;}
    JSONObject request(String command,JSONObject params)throws Exception{return request(command,params,new MonotonicDeadline(2500));}
    JSONObject request(String command,JSONObject params,MonotonicDeadline budget)throws Exception{
        return request(command,params,budget,2500);
    }
    JSONObject request(String command,JSONObject params,MonotonicDeadline budget,int perRpcMillis)throws Exception{
        synchronized(rpcLock){
            Connection active;
            synchronized(stateLock){if(failure!=0)throw new RpcFailure(failure);active=connection;}
            // An already-expired caller budget has not attempted IO; do not poison a healthy transport.
            try{requireTime(budget);}catch(SocketTimeoutException ignored){throw RpcFailure.classified(RpcFailure.command(command),RpcFailure.TIMEOUT);}
            budget=new MonotonicDeadline(Math.min(perRpcMillis,budget.millis()));
            if(active==null){
                active=new Connection();
                synchronized(stateLock){connection=active;}
                try{
                    int port=Integer.parseInt(Files.readString(base.resolve("droidvmd-port.txt")).trim());
                    active.socket.connect(new InetSocketAddress("127.0.0.1",port),Math.min(2000,requireTime(budget)));
                    active.socket.setTcpNoDelay(true);
                    String token=Files.readString(base.resolve("droidvmd-token.txt")).trim();
                    exchange(active,"auth",new JSONObject().put("token",token),budget);
                }catch(RpcFailure failed){throw latch(failed);}
                catch(SocketTimeoutException ignored){throw latch(RpcFailure.classified(RpcFailure.AUTH,RpcFailure.TIMEOUT));}
                catch(Exception ignored){throw latch(RpcFailure.classified(RpcFailure.AUTH,RpcFailure.EOF_IO));}
            }
            try{return exchange(active,command,params,budget);}
            catch(RpcFailure failed){throw latch(failed);}
        }
    }
    private RpcFailure latch(RpcFailure failed){
        Connection old;
        synchronized(stateLock){
            if(failure==0)failure=failed.code;
            failed=new RpcFailure(failure);old=connection;connection=null;
            if(pending!=null&&!pending.done)pending.fail(failed);
        }
        closeSocket(old);return failed;
    }
    private static int requireTime(MonotonicDeadline budget)throws SocketTimeoutException{int millis=budget.millis();if(millis==0)throw new SocketTimeoutException();return millis;}
    private JSONObject exchange(Connection active,String command,JSONObject params,MonotonicDeadline budget)throws RpcFailure{
        Pending request=new Pending(command);boolean startReader;
        synchronized(stateLock){
            if(failure!=0)throw new RpcFailure(failure);
            if(connection!=active)throw RpcFailure.classified(request.phase,RpcFailure.EOF_IO);
            pending=request;startReader=!active.readerStarted;active.readerStarted=true;
        }
        // Absolute remaining budget includes writes. Neither reader nor close needs the RPC lock.
        ScheduledFuture<?> timeout=watchdog.schedule(()->failConnection(active,request,RpcFailure.TIMEOUT),budget.nanos(),TimeUnit.NANOSECONDS);
        try{
            if(startReader){Thread reader=new Thread(()->readLoop(active),"companion-daemon-reader");reader.setDaemon(true);reader.start();}
            requireTime(budget);
            params.put("type","request").put("request_id",request.id).put("command",command);
            byte[] data=params.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=active.socket.getOutputStream();out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length+1).array());out.write(data);out.write(0);out.flush();
            if(!request.ready.await(budget.nanos(),TimeUnit.NANOSECONDS))throw RpcFailure.classified(request.phase,RpcFailure.TIMEOUT);
            if(request.error!=null)throw request.error;
            requireTime(budget);
            if(!request.response.getBoolean("success"))throw RpcFailure.classified(request.phase,RpcFailure.REMOTE_REJECTED);
            return request.response;
        }catch(RpcFailure failed){throw failed;}
        catch(SocketTimeoutException ignored){throw RpcFailure.classified(request.phase,RpcFailure.TIMEOUT);}
        catch(JSONException ignored){throw RpcFailure.classified(request.phase,RpcFailure.JSON_INVALID);}
        catch(InterruptedException ignored){Thread.currentThread().interrupt();throw RpcFailure.classified(request.phase,RpcFailure.EOF_IO);}
        catch(Exception ignored){
            synchronized(stateLock){if(failure!=0)throw new RpcFailure(failure);}
            throw RpcFailure.classified(request.phase,budget.millis()==0?RpcFailure.TIMEOUT:RpcFailure.EOF_IO);
        }finally{
            timeout.cancel(false);
            synchronized(stateLock){if(pending==request)pending=null;}
        }
    }
    private void readLoop(Connection active){
        int reason=RpcFailure.EOF_IO;
        try{
            DataInputStream in=new DataInputStream(active.socket.getInputStream());
            while(true){
                int length=Integer.reverseBytes(in.readInt());
                if(length<1||length>MAX_FRAME){reason=RpcFailure.FRAME_INVALID;break;}
                byte[] bytes=new byte[length];in.readFully(bytes);int end=length;while(end>0&&bytes[end-1]==0)end--;
                JSONObject response=new JSONObject(new String(bytes,0,end,StandardCharsets.UTF_8));
                // No subscriptions and no event queue: consume every frame even between requests.
                if("event".equals(response.optString("type")))continue;
                synchronized(stateLock){
                    if(connection!=active)return;
                    Pending request=pending;
                    if(request==null||request.done||!request.id.equals(response.optString("request_id")))continue;
                    try{
                        if(!response.getBoolean("success"))request.error=RpcFailure.classified(request.phase,RpcFailure.REMOTE_REJECTED);
                        else request.response=response;
                    }catch(JSONException ignored){request.error=RpcFailure.classified(request.phase,RpcFailure.JSON_INVALID);}
                    request.done=true;request.ready.countDown();
                    if(request.error!=null){if(failure==0)failure=request.error.code;return;}
                }
            }
        }catch(JSONException ignored){reason=RpcFailure.JSON_INVALID;}
        catch(Exception ignored){}
        finally{failConnection(active,null,reason);}
    }
    private void failConnection(Connection active,Pending expected,int reason){
        synchronized(stateLock){
            if(connection!=active||(expected!=null&&(pending!=expected||expected.done)))return;
            long phase=pending!=null&&!pending.done?pending.phase:RpcFailure.SESSION_CLOSED;
            if(failure==0)failure=RpcFailure.classified(phase,reason).code;
            connection=null;
            if(pending!=null&&!pending.done)pending.fail(new RpcFailure(failure));
        }
        closeSocket(active);
    }
    private static void closeSocket(Connection old){if(old!=null)try{old.socket.close();}catch(Exception ignored){}}
    void close(){
        Connection old;
        synchronized(stateLock){
            old=connection;connection=null;
            if(pending!=null&&!pending.done)pending.fail(RpcFailure.classified(pending.phase,RpcFailure.EOF_IO));
        }
        closeSocket(old);
    }
}
