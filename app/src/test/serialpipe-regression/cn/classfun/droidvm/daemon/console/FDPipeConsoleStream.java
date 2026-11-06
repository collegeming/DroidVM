package cn.classfun.droidvm.daemon.console;
import cn.classfun.droidvm.lib.natives.UnixHelper;

/** Adoption/reuse simulation, NOT ParcelFileDescriptor or Android/JNI evidence. */
public final class FDPipeConsoleStream {
    public int fail, closes;
    private int read = -1, write = -1;
    public void setReadFd(int fd) {
        if (fail == 1) throw new IllegalStateException("before read adopt");
        read = fd; UnixHelper.adopt(fd);
        if (fail == 2) throw new IllegalStateException("after read adopt");
    }
    public void setWriteFd(int fd) {
        if (fail == 3) throw new IllegalStateException("before write adopt");
        write = fd; UnixHelper.adopt(fd);
        if (fail == 4) throw new IllegalStateException("after write adopt");
    }
    public int getPosixReadFd() { return read; }
    public int getPosixWriteFd() { return write; }
    public boolean isReady() { return read >= 0 || write >= 0; }
    public void close() {
        closes++; UnixHelper.releaseAndReuse(read); UnixHelper.releaseAndReuse(write);
        read = write = -1;
    }
}
