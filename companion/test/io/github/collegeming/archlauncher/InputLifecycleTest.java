package io.github.collegeming.archlauncher;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Deterministic main/worker/transport queues exercise the same lease/lifetime code the app uses. */
public final class InputLifecycleTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static final class Scheduler {
        final List<Runnable> jobs=new ArrayList<>();
        void post(Runnable job){jobs.add(job);}
        void run(int i){jobs.remove(i).run();}
        void drain(){while(!jobs.isEmpty())run(0);}
    }
    static final class Harness {
        final InputLease lease=new InputLease();final SourceLifetime source=new SourceLifetime();
        final Scheduler main=new Scheduler(),worker=new Scheduler(),transport=new Scheduler();
        final Set<Long> revoked=new HashSet<>(),contacts=new HashSet<>();
        final List<String> results=new ArrayList<>();
        long generation;int failures;
        void release(long g){revoked.add(g);transport.post(()->contacts.remove(g));}
        InputLease.Attempt begin(boolean enabled){return lease.begin(enabled);}
        void attached(InputLease.Attempt attempt,long g){main.post(()->{
            if(!lease.complete(attempt,g)){release(g);return;}
            generation=g;if(!lease.current(lease.ticket()))release(g);
        });}
        void close(){lease.close();release(generation);}
        void down(InputLease.Ticket ticket,boolean delayReply){worker.post(()->{
            if(!lease.current(ticket)){results.add("LOCAL_DROP");return;}
            transport.post(()->{
                if(!revoked.contains(ticket.generation))contacts.add(ticket.generation);
                Runnable reply=()->{if(!lease.current(ticket)){release(ticket.generation);return;}
                    main.post(()->{if(lease.current(ticket)){results.add("DELIVERED");} });};
                if(delayReply)worker.post(reply);else reply.run();
            });
        });}
        void failure(InputLease.Ticket ticket){main.post(()->{if(lease.current(ticket)){failures++;close();}});}
        void connected(long sourceId){main.post(()->{if(source.current(sourceId))lease.want(true);});}
    }
    public static void main(String[] args){
        Harness h=new Harness();
        h.lease.want(true);InputLease.Attempt first=h.begin(true);h.attached(first,1);h.main.drain();
        InputLease.Ticket old=h.lease.ticket();check(h.lease.current(old));
        h.down(old,false);h.close();h.worker.drain();h.transport.drain();check(h.contacts.isEmpty());check(h.results.contains("LOCAL_DROP"));
        // Write was admitted, then close runs before the blocking reply: exact-generation compensation.
        h.lease.want(true);InputLease.Attempt second=h.begin(true);h.attached(second,2);h.main.drain();
        InputLease.Ticket inFlight=h.lease.ticket();h.down(inFlight,true);h.worker.drain();h.transport.run(0);check(h.contacts.contains(2L));
        h.close();h.lease.want(true);InputLease.Attempt third=h.begin(true);h.attached(third,3);h.main.drain();
        InputLease.Ticket current=h.lease.ticket();h.worker.drain();h.transport.drain();check(h.contacts.isEmpty());check(h.lease.current(current));
        // Old failure/result callbacks are delivered after a newer lease and must not pause it.
        h.failure(inFlight);h.main.drain();check(h.failures==0);check(h.lease.current(current));
        // Close/reopen while attach is in-flight: returned generation is paused, never blindly opened.
        InputLease.Attempt pending=h.begin(true);h.close();h.lease.want(true);h.attached(pending,4);h.main.drain();
        check(h.lease.ticket()==null);check(h.revoked.contains(4L));
        InputLease.Attempt next=h.begin(true);h.attached(next,5);h.main.drain();check(h.lease.current(h.lease.ticket()));
        // Duplicate old attach callback cannot erase a pending newer attempt.
        InputLease.Attempt newer=h.begin(true);h.attached(pending,4);h.main.drain();check(h.lease.pending());h.attached(newer,6);h.main.drain();check(h.lease.ticket().generation==6);
        // Shutdown/replacement invalidates callbacks already posted by the old Surface provider.
        long source=h.source.begin();h.connected(source);h.source.close();h.lease.close();h.main.drain();check(!h.lease.wanted());
        long replacement=h.source.begin();h.connected(source);h.main.drain();check(!h.lease.wanted());h.connected(replacement);h.main.drain();check(h.lease.wanted());
        // One monotonic budget for queue wait + connect/auth + every channel, never 4 x timeout.
        long[] now={0};MonotonicDeadline budget=new MonotonicDeadline(()->now[0],1500);
        now[0]+=TimeUnit.MILLISECONDS.toNanos(400);check(budget.millis()==1100);
        now[0]+=TimeUnit.MILLISECONDS.toNanos(700);check(budget.millis()==400);
        now[0]+=TimeUnit.MILLISECONDS.toNanos(500);check(budget.millis()==0);
        System.out.println("Input lifecycle: fake main/worker/transport late callbacks, in-flight DOWN cleanup, stale attach/source isolation and total deadline passed");
    }
}
