package io.github.collegeming.archlauncher;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class GrabConcurrencyTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void await(CountDownLatch latch)throws Exception{check(latch.await(5,TimeUnit.SECONDS));}
    public static void main(String[] args)throws Exception{
        GrabLease lease=new GrabLease();AtomicLong held=new AtomicLong();AtomicInteger stops=new AtomicInteger();
        lease.publish(1,()->{});CountDownLatch prechecked=new CountDownLatch(1),resumeOld=new CountDownLatch(1);
        AtomicBoolean oldArmed=new AtomicBoolean(true);
        Thread stale=new Thread(()->{try{check(lease.current(1));prechecked.countDown();await(resumeOld);oldArmed.set(lease.arm(1,()->{stops.incrementAndGet();held.set(1);}));}catch(Exception e){throw new RuntimeException(e);}});
        stale.start();await(prechecked);
        lease.publish(2,()->held.set(0));check(lease.arm(2,()->held.set(2)));resumeOld.countDown();stale.join(5000);
        check(!stale.isAlive()&&!oldArmed.get()&&held.get()==2&&stops.get()==0);
        check(!lease.revoke(1,()->held.set(0)));check(held.get()==2);
        check(lease.revoke(2,()->held.set(0)));check(!lease.arm(2,()->held.set(2)));check(held.get()==0);
        // Publication waits for a currently-authorized arm's local critical section; never halfway mutation.
        CountDownLatch inArm=new CountDownLatch(1),finishArm=new CountDownLatch(1);lease.publish(3,()->{});
        Thread arm=new Thread(()->lease.arm(3,()->{inArm.countDown();try{await(finishArm);}catch(Exception e){throw new RuntimeException(e);}held.set(3);}));arm.start();await(inArm);
        Thread publish=new Thread(()->lease.publish(4,()->held.set(0)));publish.start();finishArm.countDown();arm.join(5000);publish.join(5000);check(lease.current(4)&&held.get()==0);
        List<String> order=Collections.synchronizedList(new ArrayList<>());CountDownLatch blocked=new CountDownLatch(1),unblock=new CountDownLatch(1),cleaned=new CountDownLatch(1),drained=new CountDownLatch(64);
        AtomicBoolean dirty=new AtomicBoolean(true),unconfirmed=new AtomicBoolean();AtomicLong clock=new AtomicLong();
        BrokerIo io=new BrokerIo(cleanup->{order.add("cleanup");if(cleanup.budget.millis()==0)unconfirmed.set(true);else dirty.set(false);cleaned.countDown();});
        io.execute(()->{blocked.countDown();try{await(unblock);}catch(Exception e){throw new RuntimeException(e);}});await(blocked);
        for(int i=0;i<64;i++)io.execute(()->{order.add("raw");drained.countDown();});
        boolean rejected=false;try{io.execute(()->{});}catch(RejectedExecutionException expected){rejected=true;}check(rejected);
        MonotonicDeadline deadline=new MonotonicDeadline(clock::get,1500);
        for(int i=0;i<10000;i++)io.cleanup(4,BrokerGate.PAUSED,false,deadline);
        check(io.pendingCleanupCount()==1);clock.set(TimeUnit.SECONDS.toNanos(2));unblock.countDown();await(cleaned);await(drained);
        check(order.get(0).equals("cleanup")&&dirty.get()&&unconfirmed.get());
        // Retry is explicit and ACK-confirmed before publishing/rearming the replacement generation.
        CountDownLatch confirmed=new CountDownLatch(1);io.cleanup(4,BrokerGate.PAUSED,true,new MonotonicDeadline(1500));io.execute(()->{check(!dirty.get());lease.publish(5,()->{});confirmed.countDown();});await(confirmed);check(lease.current(5));
        System.out.println("Concurrent grab: stale prechecked arm cannot replace new generation; revoke blocks rearm; saturated raw queue preserves/coalesces priority cleanup and failed dirty state until ACK passed");
    }
}
