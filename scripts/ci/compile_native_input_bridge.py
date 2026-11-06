"""CI-only SDK compilation of the actual bridge; dependency stubs are not runtime tests."""
from pathlib import Path
import subprocess
import sys

api, out = Path(sys.argv[1]), Path(sys.argv[2])
root = Path(__file__).resolve().parents[2]
out.mkdir(parents=True, exist_ok=False)
stubs = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'cn/classfun/droidvm/lib/utils/FileUtils.java': 'package cn.classfun.droidvm.lib.utils; public class FileUtils { public static boolean deleteFile(String p){return false;} }',
    'cn/classfun/droidvm/lib/utils/StringUtils.java': 'package cn.classfun.droidvm.lib.utils; public class StringUtils { public static String fmt(String f,Object... a){return f;} }',
    'cn/classfun/droidvm/lib/natives/UnixHelper.java': '''package cn.classfun.droidvm.lib.natives; public class UnixHelper {
        public static boolean isLoaded(){return false;}
        public static int nativeUnixListen(String p){return -1;}
        public static int nativeUnixAccept(int f){return -1;}
        public static int[] nativePipe(){return null;}
        public static int nativePollIn2(int a,int b,int t){return -1;}
        public static void nativeCloseFd(int f){}
    }''',
    'cn/classfun/droidvm/lib/network/FDSocket.java': '''package cn.classfun.droidvm.lib.network; import java.io.*; public class FDSocket {
        public FDSocket(int f){} public void close(){} public boolean isOpen(){return false;}
        public InputStream getInputStream() throws IOException {return null;}
        public OutputStream getOutputStream() throws IOException {return null;}
    }''',
    'cn/classfun/droidvm/lib/store/vm/NativeDisplay.java': '''package cn.classfun.droidvm.lib.store.vm; public class NativeDisplay {
        public static final int CHANNEL_COUNT=4,MULTITOUCH=0,KEYBOARD=1,TABLET=3;
        public static boolean isPerScreen(int c){return false;}
        public static String requireBindablePath(String p){return p;}
        public static String inputSocketPath(String v,String s,int c){return s;}
    }''',
}
files=[]
for name, text in stubs.items():
    target=out/'stubs'/name
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding='utf-8')
    files.append(str(target))
base=root/'app/src/main/java/cn/classfun/droidvm/daemon/vm/backend'
files.extend(str(base/name) for name in ('NativeInputSocketOwner.java','NativeDisplayInputBridge.java'))
subprocess.run(['javac','--release','11','-cp',str(api),'-d',str(out/'classes'),*files],check=True)
print('Actual bridge SDK API compilation passed; dependency stubs, no full APK/JNI/device claim')
