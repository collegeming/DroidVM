package io.github.collegeming.archlauncher;
import java.nio.*;
public final class GrabPolicyTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static byte[] event(int type,int code,int value){return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putShort((short)type).putShort((short)code).putInt(value).array();}
    static GrabPolicy.Frame key(boolean[] keys,int code,int value){return GrabPolicy.convert(event(1,code,value),24,keys);}
    public static void main(String[] args){
        for(int ctrl:new int[]{29,97})for(int alt:new int[]{56,100}){
            boolean[] keys=new boolean[0x300];check(!key(keys,ctrl,1).escape);check(!key(keys,alt,1).escape);check(key(keys,88,1).escape);
        }
        boolean[] keys=new boolean[0x300];check(key(keys,30,1).bytes.length==8);check(key(keys,30,2).bytes.length==0);check(key(keys,30,0).bytes.length==8);
        check(GrabPolicy.convert(event(4,4,22),24,keys).bytes.length==0); // never EV_MSC
        check(!key(keys,88,1).escape);check(!key(keys,29,1).escape);
        byte[] bits=new byte[128];for(int code:new int[]{16,30,44,28,57})bits[code/8]|=1<<(code%8);check(GrabPolicy.typing(bits));
        check(!GrabPolicy.typing(new byte[128]));check(!GrabPolicy.has(bits,0x14a));bits[0x14a/8]|=1<<(0x14a%8);check(GrabPolicy.has(bits,0x14a));
        System.out.println("Raw grab: all left/right Ctrl+Alt+F12 variants, 24-to-8 byte encoding, repeat/MSC filtering and typing/touch capability checks passed");
    }
}
