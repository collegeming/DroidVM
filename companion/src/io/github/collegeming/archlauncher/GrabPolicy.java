package io.github.collegeming.archlauncher;
import java.nio.*;
/** Raw-event conversion and escape rules shared by the real root reader and executable tests. */
final class GrabPolicy {
    static final int EVENT_SIZE=24;
    static boolean typing(byte[] bits){for(int code:new int[]{16,30,44,28,57})if(!has(bits,code))return false;return true;}
    static boolean has(byte[] bits,int code){return bits!=null&&code/8<bits.length&&(bits[code/8]&(1<<(code%8)))!=0;}
    static final class Frame {final byte[] bytes;final boolean escape;Frame(byte[] bytes,boolean escape){this.bytes=bytes;this.escape=escape;}}
    static Frame convert(byte[] bytes,int length,boolean[] pressed){return convert(bytes,length,pressed,2);}
    static Frame convert(byte[] bytes,int length,boolean[] pressed,int releasePreset){
        ByteBuffer in=ByteBuffer.wrap(bytes,0,length).order(ByteOrder.LITTLE_ENDIAN),out=ByteBuffer.allocate(length/24*8+8).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=0;offset+24<=length;offset+=24){
            int type=in.getShort(offset+16)&65535,code=in.getShort(offset+18)&65535,value=in.getInt(offset+20);
            if(type==1){
                if(value==2)continue; // guest, not host, owns autorepeat
                if(code<pressed.length)pressed[code]=value!=0;
                if(code<pressed.length&&value!=0&&HotkeyPolicy.release(releasePreset,code,pressed))return new Frame(new byte[0],true);
                out.putShort((short)type).putShort((short)code).putInt(value);
            }else if(type==0&&code==0&&out.position()>0)out.putShort((short)0).putShort((short)0).putInt(0);
            // EV_MSC/LED/REP are never forwarded into a guest accepting types 0..3.
        }
        return new Frame(java.util.Arrays.copyOf(out.array(),out.position()),false);
    }
}
