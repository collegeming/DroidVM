package io.github.collegeming.archlauncher;
import java.nio.*;
import java.util.Properties;
public final class HotkeyPolicyTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Properties saved=new Properties();saved.setProperty("enter_hotkey","0");saved.setProperty("release_hotkey","0");
        java.io.StringWriter text=new java.io.StringWriter();saved.store(text,null);Properties loaded=new Properties();loaded.load(new java.io.StringReader(text.toString()));
        int enter=HotkeyPolicy.preset(Integer.parseInt(loaded.getProperty("enter_hotkey")),1),release=HotkeyPolicy.preset(Integer.parseInt(loaded.getProperty("release_hotkey")),2);
        check(HotkeyPolicy.action(enter,release,68,true,true,true)==HotkeyPolicy.RELEASE);
        check(HotkeyPolicy.action(enter,release,68,true,true,false)==HotkeyPolicy.ENTER);
        for(int e=0;e<3;e++)for(int r=0;r<3;r++)for(boolean active:new boolean[]{true,false})check(HotkeyPolicy.action(e,r,88,true,true,active)==HotkeyPolicy.RELEASE);
        for(int preset=0;preset<3;preset++)for(int ctrl:new int[]{29,97})for(int alt:new int[]{56,100}){
            boolean[] keys=new boolean[0x300];keys[ctrl]=true;keys[alt]=true;
            byte[] bytes=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putShort((short)1).putShort((short)HotkeyPolicy.scan(preset)).putInt(1).array();
            check(GrabPolicy.convert(bytes,24,keys,preset).escape);
            byte[] emergency=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putShort((short)1).putShort((short)88).putInt(1).array();
            check(GrabPolicy.convert(emergency,24,keys,preset).escape);
        }
        check(!HotkeyPolicy.release(0,68,true,false));check(HotkeyPolicy.preset(-100,2)==2);
        System.out.println("Hotkey presets: stable saved IDs, shared root/UI scan mapping, same-key toggle, all left/right modifiers and immutable F12 release priority passed");
    }
}
