package io.github.collegeming.archlauncher;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class ManagedStartTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static final class Host implements ManagedStart.Host {
        boolean config=true,failPing,failStart;long rpcFailure;String hash=StartPolicy.DISPATCHER_SHA;
        final List<String> states=new ArrayList<>();int starts,reads;long[] time={0};
        Host(String... states){this.states.addAll(Arrays.asList(states));}
        public boolean configured(){return config;}
        public String dispatcherHash(){return hash;}
        public void ping()throws Exception{if(rpcFailure!=0)throw new RpcFailure(rpcFailure);if(failPing)throw new Exception();}
        public String state(){return states.get(Math.min(reads++,states.size()-1));}
        public void start()throws Exception{starts++;if(failStart)throw new Exception();}
        public void sleep(){time[0]+=TimeUnit.MILLISECONDS.toNanos(250);}
        long run(boolean allow){return ManagedStart.ensure(this,allow,new MonotonicDeadline(()->time[0],2000));}
    }
    public static void main(String[] args){
        String gfx=SessionPolicy.IDS[0],pan=SessionPolicy.IDS[1];String[] guard={StartPolicy.GUARD};
        check(StartPolicy.configured(gfx,new String[]{"DROIDVM_RUNTIME_PROFILE=gfxstream-v1"},guard));
        check(StartPolicy.configured(pan,new String[]{"DROIDVM_RUNTIME_PROFILE=panvk-v1"},guard));
        check(!StartPolicy.configured(gfx,new String[]{"DROIDVM_RUNTIME_PROFILE=panvk-v1"},guard));
        check(!StartPolicy.configured(gfx,new String[]{"DROIDVM_RUNTIME_PROFILE=gfxstream-v1","DROIDVM_RUNTIME_PROFILE=gfxstream-v1"},guard));
        check(!StartPolicy.configured(gfx,new String[]{"DROIDVM_RUNTIME_PROFILE=gfxstream-v1"},new String[]{StartPolicy.GUARD,StartPolicy.GUARD}));
        check(!StartPolicy.configured(gfx,new String[]{"DROIDVM_RUNTIME_PROFILE=gfxstream-v1"},new String[]{"--droidvm-profile-guard=v2"}));
        check(StartPolicy.knownDispatcher(StartPolicy.DURABLE_DISPATCHER_SHA));
        Host durable=new Host("stopped","stopped","running");durable.hash=StartPolicy.DURABLE_DISPATCHER_SHA;check(durable.run(true)==1&&durable.starts==1);
        Host running=new Host("running");check(running.run(true)==1&&running.starts==0);
        Host stopped=new Host("stopped","stopped","starting","running");check(stopped.run(true)==1&&stopped.starts==1);
        Host rearm=new Host("stopped");check(rearm.run(false)==ManagedStart.NOT_READY&&rearm.starts==0);
        Host unknown=new Host("stopped");unknown.hash="stock";check(unknown.run(true)==ManagedStart.UNKNOWN_RUNTIME&&unknown.starts==0);
        Host bad=new Host("stopped");bad.config=false;check(bad.run(true)==ManagedStart.BAD_PROFILE&&bad.starts==0);
        Host transition=new Host("starting");check(transition.run(true)==ManagedStart.NOT_READY&&transition.starts==0);
        Host fail=new Host("stopped","stopped");fail.failStart=true;check(fail.run(true)==ManagedStart.START_FAILED&&fail.starts==1);
        Host timeout=new Host("stopped","stopped","starting");check(timeout.run(true)==ManagedStart.START_TIMEOUT&&timeout.starts==1);
        Host unhealthy=new Host("stopped");unhealthy.failPing=true;check(unhealthy.run(true)==ManagedStart.DAEMON_UNHEALTHY&&unhealthy.starts==0);
        Host phase=new Host("running");phase.rpcFailure=RpcFailure.STATUS;check(phase.run(false)==RpcFailure.STATUS);check(ManagedStart.label(RpcFailure.STATUS).startsWith("VM_STATUS_TIMEOUT_OR_IO"));
        check(!BrokerGate.label((int)RpcFailure.INPUT).equals(BrokerGate.label(BrokerGate.NOT_DELIVERED)));
        System.out.println("Managed start: fixed route/profile/unique guard/dispatcher allowlist, running attach-only, stopped start-once, no fallback, health and total poll timeout passed");
    }
}
