package io.github.collegeming.archlauncher;

import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Used only inside root app_process. No daemon bootstrap or VM lifecycle commands. */
final class DaemonClient {
    private Socket socket;
    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private static final String BASE = "/data/data/cn.classfun.droidvm/run/";
    synchronized JSONObject request(String command, JSONObject params) throws Exception {
        if (socket == null) {
            Socket opening = new Socket();
            socket = opening;
            try {
                int port = Integer.parseInt(Files.readString(Path.of(BASE + "droidvmd-port.txt")).trim());
                opening.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                opening.setSoTimeout(2000);
                String token = Files.readString(Path.of(BASE + "droidvmd-token.txt")).trim();
                exchange("auth", new JSONObject().put("token", token));
            } catch (Exception e) { close(); throw new IOException("E_AUTH"); }
        }
        try { return exchange(command, params); }
        catch (Exception e) { close(); throw new IOException("E_DAEMON"); }
    }
    private JSONObject exchange(String command, JSONObject params) throws Exception {
        final Socket active = socket;
        // Read timeout alone does not bound writes. Closing from another thread unblocks both.
        ScheduledFuture<?> deadline = watchdog.schedule(() -> { try { active.close(); } catch(Exception ignored){} }, 2500, TimeUnit.MILLISECONDS);
        try {
            String id = UUID.randomUUID().toString();
            params.put("type","request").put("request_id",id).put("command",command);
            byte[] data = params.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = active.getOutputStream();
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array()); out.write(data); out.flush();
            DataInputStream in = new DataInputStream(active.getInputStream());
            for(int count=0;count<64;count++) {
                int length = Integer.reverseBytes(in.readInt());
                if(length<1 || length>1024*1024)throw new IOException("frame");
                byte[] bytes = new byte[length]; in.readFully(bytes);
                int end=length; while(end>0 && bytes[end-1]==0)end--;
                JSONObject response = new JSONObject(new String(bytes,0,end,StandardCharsets.UTF_8));
                if(!id.equals(response.optString("request_id")))continue;
                if(!response.optBoolean("success"))throw new IOException("request");
                return response;
            }
            throw new IOException("deadline");
        } finally { deadline.cancel(false); }
    }
    synchronized void close() { if(socket!=null)try{socket.close();}catch(Exception ignored){} socket=null; }
}
