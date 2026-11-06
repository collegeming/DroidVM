package io.github.collegeming.archlauncher;
import android.os.IBinder;
interface IInputBroker {
    long attach(String token, String vm, IBinder client, boolean allowStart);
    IBinder display(String token, long generation);
    int write(String token, long generation, int channel, in byte[] bytes);
    void releaseAll(String token, long generation);
    void detach(String token, long generation);
}
