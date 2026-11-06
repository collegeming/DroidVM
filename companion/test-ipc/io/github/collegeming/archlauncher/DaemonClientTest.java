package io.github.collegeming.archlauncher;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Host-only loopback fake daemon. Never reads device credentials or contacts ADB. */
public final class DaemonClientTest {
    interface Script {void run(Socket peer)throws Exception;}
    interface Check {void run(Fake fake)throws Exception;}
    static void check(boolean ok,String code){if(!ok)throw new AssertionError(code);}
    static final class Fake implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        final Path base=Files.createTempDirectory("companion-ipc-test-");
        final Thread thread;
        volatile Throwable failed;
        volatile Socket peer;
        Fake(Script script)throws Exception{
            Files.writeString(base.resolve("droidvmd-port.txt"),Integer.toString(listener.getLocalPort()));
            Files.writeString(base.resolve("droidvmd-token.txt"),"offline-test-only");
            listener.setSoTimeout(6000);
            thread=new Thread(()->{
                try(Socket accepted=listener.accept()){
                    peer=accepted;accepted.setSoTimeout(6000);script.run(accepted);
                }catch(Throwable ignored){failed=new AssertionError("FAKE_DAEMON_FAILED");}
            },"fake-daemon");
            thread.setDaemon(true);thread.start();
        }
        public void close()throws Exception{
            try{
                thread.join(7000);
                check(!thread.isAlive(),"FAKE_DAEMON_STUCK");
                check(failed==null,"FAKE_DAEMON_FAILED");
            }finally{
                listener.close();if(peer!=null)peer.close();
                Files.deleteIfExists(base.resolve("droidvmd-port.txt"));
                Files.deleteIfExists(base.resolve("droidvmd-token.txt"));Files.deleteIfExists(base);
            }
        }
    }
    static void scenario(Script script,Check body)throws Exception{try(Fake fake=new Fake(script)){body.run(fake);}}
    static byte[] little(int n){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();}
    static JSONObject read(Socket peer)throws Exception{
        DataInputStream in=new DataInputStream(peer.getInputStream());
        int length=Integer.reverseBytes(in.readInt());check(length>0&&length<65536,"TEST_REQUEST_LENGTH");
        byte[] body=new byte[length];in.readFully(body);int end=length;
        while(end>0&&body[end-1]==0)end--;
        return new JSONObject(new String(body,0,end,StandardCharsets.UTF_8));
    }
    static void raw(Socket peer,String body)throws Exception{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=peer.getOutputStream();
        out.write(little(bytes.length+1));out.write(bytes);out.write(0);out.flush();
    }
    static void reply(Socket peer,JSONObject req,boolean success)throws Exception{
        raw(peer,new JSONObject().put("request_id",req.getString("request_id")).put("success",success)
            .put("message","untrusted-response-sentinel").toString());
    }
    static void auth(Socket peer)throws Exception{
        JSONObject req=read(peer);check("auth".equals(req.getString("command")),"TEST_EXPECT_AUTH");reply(peer,req,true);
    }
    static JSONObject get(Socket peer)throws Exception{
        JSONObject req=read(peer);check("vm_get".equals(req.getString("command")),"TEST_EXPECT_GET");return req;
    }
    static void eof(Socket peer)throws Exception{check(peer.getInputStream().read()==-1,"CLIENT_NOT_CLOSED");}
    static RpcFailure expect(DaemonClient client,long phase,int reason,int cap)throws Exception{
        try{client.request("vm_get",new JSONObject(),new MonotonicDeadline(10000),cap);}
        catch(RpcFailure failed){
            check(failed.code==RpcFailure.classified(phase,reason).code,"WRONG_FAILURE_CODE");
            check("RPC_FAILURE".equals(failed.getMessage())&&failed.getCause()==null,"UNSAFE_FAILURE_TEXT");
            check(!RpcFailure.label(failed.code).contains("sentinel"),"UNSAFE_LABEL");return failed;
        }
        throw new AssertionError("EXPECTED_RPC_FAILURE");
    }
    static void getFailure(Script script,int reason)throws Exception{
        scenario(peer->{auth(peer);get(peer);script.run(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.GET,reason,2000);}finally{client.close();}
        });
    }
    static void labels(){
        long[] phases={RpcFailure.AUTH,RpcFailure.GET,RpcFailure.PING,RpcFailure.STATUS,RpcFailure.START,RpcFailure.SESSION_CLOSED,RpcFailure.INPUT};
        String[] prefixes={"AUTH","VM_GET","PING","VM_STATUS","VM_START_REPLY","SESSION_CLOSED","VM_INPUT_REPLY"};
        String[] reasons={"REMOTE_REJECTED","TIMEOUT","EOF_IO","FRAME_INVALID","JSON_INVALID","FRAME_LIMIT"};
        for(int p=0;p<phases.length;p++)for(int r=1;r<=reasons.length;r++){
            long code=RpcFailure.classified(phases[p],r).code;
            check(RpcFailure.phase(code)==phases[p]&&RpcFailure.reason(code)==r,"CODE_ROUNDTRIP");
            check(code<=RpcFailure.AUTH&&(int)code==code,"BINDER_CODE_COMPATIBILITY");
            check(RpcFailure.label(code).equals(prefixes[p]+"_"+reasons[r-1]),"LABEL_ROUNDTRIP");
            check(ManagedStart.label(code).startsWith(RpcFailure.label(code)),"START_LABEL_COMPATIBILITY");
            check(BrokerGate.label((int)code).equals(RpcFailure.label(code)),"INPUT_LABEL_COMPATIBILITY");
        }
    }
    public static void main(String[] args)throws Exception{
        labels();
        // success=false is a remote refusal, not an IO/timeout; all later calls retain that exact code.
        scenario(peer->{auth(peer);JSONObject req=get(peer);reply(peer,req,false);eof(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            RpcFailure first=expect(client,RpcFailure.GET,RpcFailure.REMOTE_REJECTED,2000);
            Files.delete(fake.base.resolve("droidvmd-port.txt"));client.close();
            try{client.request("ping",new JSONObject());throw new AssertionError("LATCH_LOST");}
            catch(RpcFailure next){check(next.code==first.code,"LATCH_CHANGED_PHASE");}
            // Explicit reconstruction, not close(), is what clears a latched failure.
            scenario(peer->{auth(peer);reply(peer,get(peer),true);},fresh->{
                DaemonClient rebuilt=new DaemonClient(fresh.base);
                try{check(rebuilt.request("vm_get",new JSONObject()).getBoolean("success"),"REBUILD_FAILED");}
                finally{rebuilt.close();}
            });
        });
        // Same socket, auth consumes half of the GET per-RPC cap. GET does not get a fresh cap.
        AtomicLong getWait=new AtomicLong();
        scenario(peer->{JSONObject req=read(peer);Thread.sleep(1500);reply(peer,req,true);get(peer);
            long start=System.nanoTime();eof(peer);getWait.set(System.nanoTime()-start);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.GET,RpcFailure.TIMEOUT,3000);}finally{client.close();}
        });
        check(getWait.get()>0&&getWait.get()<TimeUnit.MILLISECONDS.toNanos(2500),"GET_BUDGET_WAS_RESET");
        scenario(peer->{read(peer);eof(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.AUTH,RpcFailure.TIMEOUT,500);}finally{client.close();}
        });
        scenario(peer->{reply(peer,read(peer),false);eof(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.AUTH,RpcFailure.REMOTE_REJECTED,2000);}finally{client.close();}
        });
        scenario(peer->{read(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.AUTH,RpcFailure.EOF_IO,2000);}finally{client.close();}
        });
        getFailure(peer->{},RpcFailure.EOF_IO);
        getFailure(peer->{peer.getOutputStream().write(new byte[]{1,0});},RpcFailure.EOF_IO);
        getFailure(peer->{peer.getOutputStream().write(little(10));peer.getOutputStream().write('{');},RpcFailure.EOF_IO);
        for(int length:new int[]{0,-1,1024*1024+1})getFailure(peer->{peer.getOutputStream().write(little(length));eof(peer);},RpcFailure.FRAME_INVALID);
        getFailure(peer->{raw(peer,"{");eof(peer);},RpcFailure.JSON_INVALID);
        scenario(peer->{auth(peer);JSONObject req=get(peer);
            raw(peer,new JSONObject().put("request_id",req.getString("request_id")).toString());eof(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.GET,RpcFailure.JSON_INVALID,2000);}finally{client.close();}
        });
        // Unsubscribed events never consume an RPC frame-count allowance.
        scenario(peer->{auth(peer);JSONObject req=get(peer);
            for(int i=0;i<4096;i++)raw(peer,"{\"type\":\"event\"}");reply(peer,req,true);
            reply(peer,get(peer),true);},fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{
                try{client.request("vm_get",new JSONObject(),new MonotonicDeadline(0));throw new AssertionError("EXPIRED_BUDGET_ACCEPTED");}
                catch(RpcFailure expired){check(expired.code==RpcFailure.classified(RpcFailure.GET,RpcFailure.TIMEOUT).code,"EXPIRED_BUDGET_CODE");}
                for(int i=0;i<2;i++)check(client.request("vm_get",new JSONObject()).getBoolean("success"),"SUCCESS_OR_REUSE_FAILED");
            }finally{client.close();}
        });
        // More than TCP's idle receive window: the fake must finish writing before another RPC.
        CountDownLatch idleDrained=new CountDownLatch(1);
        scenario(peer->{
            peer.setSendBufferSize(8192);auth(peer);reply(peer,get(peer),true);
            String event=new JSONObject().put("type","event").put("data","x".repeat(32*1024)).toString();
            for(int i=0;i<256;i++)raw(peer,event);
            idleDrained.countDown();reply(peer,get(peer),true);eof(peer);
        },fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{
                client.request("vm_get",new JSONObject());
                check(idleDrained.await(5,TimeUnit.SECONDS),"IDLE_EVENTS_NOT_DRAINED");
                check(client.request("vm_get",new JSONObject()).getBoolean("success"),"RPC_AFTER_IDLE_EVENTS");
            }finally{client.close();}
        });
        // An event with a matching id is not a response; a stale response must also be ignored.
        scenario(peer->{auth(peer);JSONObject req=get(peer);
            raw(peer,new JSONObject().put("type","event").put("request_id",req.getString("request_id")).toString());
            raw(peer,new JSONObject().put("type","response").put("request_id","stale").put("success",true).toString());
            reply(peer,req,true);eof(peer);
        },fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{check(client.request("vm_get",new JSONObject()).getBoolean("success"),"EVENT_OR_STALE_MATCHED");}
            finally{client.close();}
        });
        // close must not wait for the serialized request lock, and must wake its waiter promptly.
        CountDownLatch pendingRead=new CountDownLatch(1);
        scenario(peer->{auth(peer);get(peer);pendingRead.countDown();eof(peer);},fake->{
            DaemonClient client=new DaemonClient(fake.base);AtomicLong closedCode=new AtomicLong();
            Thread caller=new Thread(()->{
                try{client.request("vm_get",new JSONObject(),new MonotonicDeadline(10000),10000);closedCode.set(1);}
                catch(RpcFailure failed){closedCode.set(failed.code);}
                catch(Exception ignored){closedCode.set(2);}
            },"pending-rpc-close-test");caller.setDaemon(true);caller.start();
            try{
                check(pendingRead.await(3,TimeUnit.SECONDS),"PENDING_RPC_NOT_SENT");
                client.close();caller.join(1000);check(!caller.isAlive(),"CLOSE_DID_NOT_WAKE_PENDING");
                check(closedCode.get()==RpcFailure.classified(RpcFailure.GET,RpcFailure.EOF_IO).code,"CLOSE_PENDING_CODE");
            }finally{client.close();}
        });
        // A continuous stream cannot keep a missing response alive beyond the absolute deadline.
        scenario(peer->{auth(peer);get(peer);
            try{while(true){raw(peer,"{\"type\":\"event\"}");Thread.sleep(1);}}
            catch(IOException expected){}
        },fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{expect(client,RpcFailure.GET,RpcFailure.TIMEOUT,500);}finally{client.close();}
        });
        // Framing failures received while idle remain latched before the next command sends.
        CountDownLatch idleInvalid=new CountDownLatch(1);
        scenario(peer->{auth(peer);reply(peer,get(peer),true);peer.getOutputStream().write(little(0));
            eof(peer);idleInvalid.countDown();
        },fake->{
            DaemonClient client=new DaemonClient(fake.base);
            try{
                client.request("vm_get",new JSONObject());
                check(idleInvalid.await(3,TimeUnit.SECONDS),"IDLE_INVALID_NOT_CLOSED");
                try{client.request("ping",new JSONObject());throw new AssertionError("IDLE_FAILURE_NOT_LATCHED");}
                catch(RpcFailure failed){check(failed.code==RpcFailure.classified(RpcFailure.SESSION_CLOSED,RpcFailure.FRAME_INVALID).code,"IDLE_FAILURE_PHASE");}
            }finally{client.close();}
        });
        System.out.println("DaemonClient: fixed phase/reason codes; shared auth deadline; rejection/EOF/frame/JSON; continuous idle/event drain; request-id match; close pending; event deadline; latch/rebuild; socket reuse passed");
    }
}
