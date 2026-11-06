package io.github.collegeming.archlauncher;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
/** One budget shared by connect/auth/write/read and all channels, never reset per operation. */
final class MonotonicDeadline {
    private final LongSupplier clock;
    private final long end;
    MonotonicDeadline(long millis){this(System::nanoTime,millis);}
    MonotonicDeadline(LongSupplier clock,long millis){this.clock=clock;this.end=clock.getAsLong()+TimeUnit.MILLISECONDS.toNanos(millis);}
    long nanos(){return Math.max(0,end-clock.getAsLong());}
    int millis(){long n=nanos();return n==0?0:(int)Math.min(Integer.MAX_VALUE,Math.max(1,TimeUnit.NANOSECONDS.toMillis(n)));}
}
