package cn.classfun.droidvm.daemon.vm;
import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class SerialPipeRegression {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static void intact() throws Exception {
        check(Collections.disjoint(UnixHelper.raw, UnixHelper.adopted), "raw-close adopted local");
        for (var f : UnixHelper.reused.values()) check(f.read() == 42, "unrelated resource closed");
    }
    public static void main(String[] args) throws Exception {
        UnixHelper.fixture = Files.createTempFile("serialpipe-", ".bin");
        Files.write(UnixHelper.fixture, new byte[]{42});
        try {
            for (int mode = 0; mode < 3; mode++) {
                UnixHelper.reset(); var s = new FDPipeConsoleStream(); var p = new SerialPipe(s, "test");
                check(p.isReady() && p.getStream() == s && p.getName().equals("test"), "construction");
                check(p.getOutputLocalFd() == 10 && p.getInputLocalFd() == 21, "borrow locals");
                check(p.getOutputRemoteFd() == 11 && p.getInputRemoteFd() == 20, "borrow remotes");
                if (mode == 1) { p.closeRemoteFd(); p.closeRemoteFd(); check(s.isReady(), "remote closed locals"); }
                if (mode == 2) {
                    var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
                    try {
                        var jobs = new ArrayList<Future<?>>();
                        for (int i = 0; i < 32; i++) { final int n = i;
                            jobs.add(pool.submit(() -> { start.await(); if (n % 2 == 0) p.close(); else p.closeRemoteFd(); return null; })); }
                        start.countDown(); for (var j : jobs) j.get(5, TimeUnit.SECONDS);
                    } finally { pool.shutdownNow(); }
                }
                p.close(); p.close(); p.closeRemoteFd();
                intact(); check(s.closes == 1 && !p.isReady(), "idempotent close");
                check(UnixHelper.raw.size() == 2 && new HashSet<>(UnixHelper.raw).equals(Set.of(11, 20)), "remote exactly once");
                if (mode == 0) System.out.println("ADOPTED_REUSE_GUARD_PASS");
            }
            for (int mode = 1; mode <= 7; mode++) {
                UnixHelper.reset(); var s = new FDPipeConsoleStream();
                if (mode <= 3) UnixHelper.pipeFailure = mode; else s.fail = mode - 3;
                try { new SerialPipe(s, "failure"); throw new AssertionError("expected constructor failure"); }
                catch (IllegalStateException expected) { }
                check(s.closes == 0, "caller stream closed on failure");
                check(Collections.disjoint(UnixHelper.raw, UnixHelper.adopted), "failure raw-closed adoption");
                check(UnixHelper.live.keySet().equals(UnixHelper.adopted), "unadopted FD leaked");
                // Known residual: partial adoption stays with caller; NOT an exception-path leak fix.
                if (!UnixHelper.adopted.isEmpty()) check(s.isReady(), "partial adoption not preserved");
                s.close(); intact();
            }
            System.out.println("PASS production SerialPipe + helper; test-only fake FD adoption/reuse; no Android/JNI proof; partial-adopt residue remains");
        } finally { UnixHelper.reset(); Files.deleteIfExists(UnixHelper.fixture); }
    }
}
