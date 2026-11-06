package io.github.collegeming.archlauncher;

/** Testable startup sequence. The adapter owns root/IPC; this class never creates a VMM. */
final class ManagedStart {
    static final long BAD_PROFILE=-10, UNKNOWN_RUNTIME=-11, NOT_READY=-12, START_FAILED=-13, START_TIMEOUT=-14, DAEMON_UNHEALTHY=-15, RELEASE_UNCONFIRMED=-16;
    interface Host {
        boolean configured()throws Exception;
        String dispatcherHash()throws Exception;
        void ping()throws Exception;
        String state()throws Exception;
        void start()throws Exception;
        void sleep()throws Exception;
    }
    static long ensure(Host host,boolean allowStart,MonotonicDeadline budget){
        try{
            if(!host.configured())return BAD_PROFILE;
            if(!StartPolicy.knownDispatcher(host.dispatcherHash()))return UNKNOWN_RUNTIME;
            host.ping();String state=host.state();
            if(StartPolicy.attachOnly(state))return 1;
            if(!allowStart||!StartPolicy.mayStart(state))return NOT_READY;
            // Re-check allowlist/target immediately before the only lifecycle mutation.
            if(!host.configured())return BAD_PROFILE;
            if(!StartPolicy.knownDispatcher(host.dispatcherHash()))return UNKNOWN_RUNTIME;
            if(!StartPolicy.mayStart(host.state()))return NOT_READY;
            try{host.start();}catch(Exception ignored){return START_FAILED;}
            while(budget.millis()>0){
                state=host.state();
                if(StartPolicy.attachOnly(state))return 1;
                if(!"starting".equals(state)&&!"stopped".equals(state))return START_FAILED;
                host.sleep();
            }
            return START_TIMEOUT;
        }catch(Exception ignored){return DAEMON_UNHEALTHY;}
    }
    static String label(long result){
        if(result==BAD_PROFILE)return "PROFILE_MISMATCH";
        if(result==UNKNOWN_RUNTIME)return "RUNTIME_SETUP_REQUIRED（未知launcher；请准备受控runtime）";
        if(result==START_FAILED)return "VM_START_FAILED（未采用其他启动方式）";
        if(result==START_TIMEOUT)return "VM_START_TIMEOUT";
        if(result==DAEMON_UNHEALTHY)return "DAEMON_UNHEALTHY（请由管理端检查；不会自动重启）";
        if(result==RELEASE_UNCONFIRMED)return "INPUT_RELEASE_UNCONFIRMED";
        return "VM_NOT_READY";
    }
}
