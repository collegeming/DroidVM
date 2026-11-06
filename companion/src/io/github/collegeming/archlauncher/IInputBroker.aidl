package io.github.collegeming.archlauncher;
import android.os.IBinder;
interface IInputBroker {
    long attach(String token, String vm, IBinder client, boolean allowStart);
    IBinder display(String token, long generation);
    int write(String token, long generation, int channel, in byte[] bytes);
    void releaseAll(String token, long generation);
    void detach(String token, long generation);
    String keyboardGrab(String token, long generation, boolean enabled);
    String grabStatus(String token, long generation);
    int physicalKey(String token, long generation, String name, int vendor, int product, in byte[] bytes);
}
