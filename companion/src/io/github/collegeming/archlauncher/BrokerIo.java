package io.github.collegeming.archlauncher;

import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One transport owner. A single generation-coalesced cleanup slot cannot be filled by raw input. */
final class BrokerIo implements Executor {
    static final class Cleanup {
        final long generation;
        final MonotonicDeadline budget;
        int reason;
        boolean retire;
        Cleanup(long generation,int reason,boolean retire,MonotonicDeadline budget){this.generation=generation;this.reason=reason;this.retire=retire;this.budget=budget;}
    }
    private final ArrayDeque<Runnable> ordinary=new ArrayDeque<>();
    private final Consumer<Cleanup> clean;
    private Cleanup pending;
    BrokerIo(Consumer<Cleanup> clean){this.clean=clean;Thread worker=new Thread(this::run,"companion-broker-io");worker.setDaemon(true);worker.start();}
    public synchronized void execute(Runnable task){if(ordinary.size()>=64)throw new RejectedExecutionException("ordinary queue full");ordinary.add(task);notifyAll();}
    <T> Future<T> submit(Callable<T> task){FutureTask<T> result=new FutureTask<>(task);execute(result);return result;}
    synchronized void cleanup(long generation,int reason,boolean retire,MonotonicDeadline budget){
        if(generation<=0)return;
        // Generations only advance after the previous one was retired/cleaned. Never replace newer cleanup with stale work.
        if(pending==null||generation>pending.generation)pending=new Cleanup(generation,reason,retire,budget);
        else if(generation==pending.generation){pending.retire|=retire;if(reason==BrokerGate.ESCAPE)pending.reason=reason;}
        notifyAll();
    }
    synchronized int pendingCleanupCount(){return pending==null?0:1;}
    private void run(){
        while(true){Runnable next;Cleanup cleanup;
            synchronized(this){
                while(pending==null&&ordinary.isEmpty())try{wait();}catch(InterruptedException ignored){}
                cleanup=pending;pending=null;next=cleanup==null?ordinary.remove():null;
            }
            try{if(cleanup!=null)clean.accept(cleanup);else next.run();}catch(Throwable ignored){}
        }
    }
}
