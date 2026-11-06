package io.github.collegeming.archlauncher;

import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Root only. Serialized by the broker IO worker, never by a broker state lock. */
final class DaemonClient {
    private volatile Socket socket;
    private long failure;
    private static final ScheduledThreadPoolExecutor watchdog=new ScheduledThreadPoolExecutor(1);
    static { watchdog.setRemoveOnCancelPolicy(true); }
    private static final String BASE="/data/data/cn.classfun.droidvm/run/";
    JSONObject request(String command,JSONObject params)throws Exception{return request(command,params,new MonotonicDeadline(2500));}
    JSONObject request(String command,JSONObject params,MonotonicDeadline budget)throws Exception{
        if(failure!=0)throw new RpcFailure(failure);
        return request(command,params,budget,2500);
    }
    JSONObject request(String command,JSONObject params,MonotonicDeadline budget,int perRpcMillis)throws Exception{
        if(failure!=0)throw new RpcFailure(failure);
        requireTime(budget);
        budget=new MonotonicDeadline(Math.min(perRpcMillis,budget.millis()));
        if(socket==null){
            Socket opening=new Socket();socket=opening;
            try{
                int port=Integer.parseInt(Files.readString(Path.of(BASE+"droidvmd-port.txt")).trim());
                opening.connect(new InetSocketAddress("127.0.0.1",port),Math.min(2000,requireTime(budget)));
                opening.setTcpNoDelay(true);
                String token=Files.readString(Path.of(BASE+"droidvmd-token.txt")).trim();
                exchange(opening,"auth",new JSONObject().put("token",token),budget);
            }catch(Exception ignored){failure=RpcFailure.AUTH;close();throw new RpcFailure(failure);}
        }
        try{return exchange(socket,command,params,budget);}
        catch(Exception ignored){failure=RpcFailure.command(command);close();throw new RpcFailure(failure);}
    }
    private static int requireTime(MonotonicDeadline budget)throws IOException{int millis=budget.millis();if(millis==0)throw new IOException("E_DEADLINE");return millis;}
    private JSONObject exchange(Socket active,String command,JSONObject params,MonotonicDeadline budget)throws Exception{
        requireTime(budget);
        // Absolute remaining budget includes writes; socket close unblocks read or write.
        java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> timeout=watchdog.schedule(()->{if(finished.compareAndSet(false,true))try{active.close();}catch(Exception ignored){}},budget.nanos(),TimeUnit.NANOSECONDS);
        try{
            String id=UUID.randomUUID().toString();params.put("type","request").put("request_id",id).put("command",command);
            byte[] data=params.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=active.getOutputStream();out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length+1).array());out.write(data);out.write(0);out.flush();
            DataInputStream in=new DataInputStream(active.getInputStream());
            for(int count=0;count<64;count++){
                active.setSoTimeout(requireTime(budget));
                int length=Integer.reverseBytes(in.readInt());if(length<1||length>1024*1024)throw new IOException("frame");
                byte[] bytes=new byte[length];in.readFully(bytes);int end=length;while(end>0&&bytes[end-1]==0)end--;
                JSONObject response=new JSONObject(new String(bytes,0,end,StandardCharsets.UTF_8));
                if(!id.equals(response.optString("request_id")))continue;
                if(!response.optBoolean("success"))throw new IOException("request");return response;
            }
            throw new IOException("deadline");
        }finally{finished.set(true);timeout.cancel(false);}
    }
    void close(){Socket old=socket;socket=null;if(old!=null)try{old.close();}catch(Exception ignored){}}
}
