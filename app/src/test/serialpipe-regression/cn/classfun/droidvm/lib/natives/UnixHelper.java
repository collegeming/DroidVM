package cn.classfun.droidvm.lib.natives;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Test-only logical FD table: no native descriptors, Android or JNI. */
public final class UnixHelper {
    public static Path fixture;
    public static int calls, pipeFailure;
    public static final Map<Integer, String> live = new HashMap<>();
    public static final Set<Integer> adopted = new HashSet<>();
    public static final List<Integer> raw = new ArrayList<>();
    public static final Map<Integer, FileInputStream> reused = new HashMap<>();
    public static synchronized void reset() throws IOException {
        for (FileInputStream f : reused.values()) f.close();
        live.clear(); adopted.clear(); raw.clear(); reused.clear(); calls = pipeFailure = 0;
    }
    public static synchronized int[] nativePipe() {
        int n = ++calls;
        if (n == 2 && pipeFailure == 1) return null;
        if (n == 2 && pipeFailure == 2) throw new IllegalStateException("pipe failure");
        int a = n * 10; live.put(a, "raw");
        if (n == 2 && pipeFailure == 3) return new int[]{a};
        live.put(a + 1, "raw"); return new int[]{a, a + 1};
    }
    public static synchronized void adopt(int fd) { adopted.add(fd); live.put(fd, "adopted"); }
    public static synchronized void releaseAndReuse(int fd) {
        if (fd < 0) return;
        try { live.put(fd, "unrelated"); reused.put(fd, new FileInputStream(fixture.toFile())); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static synchronized void nativeCloseFd(int fd) {
        raw.add(fd); live.remove(fd);
        try { if (reused.containsKey(fd)) reused.get(fd).close(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
