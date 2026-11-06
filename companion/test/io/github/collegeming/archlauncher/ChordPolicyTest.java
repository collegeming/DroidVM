package io.github.collegeming.archlauncher;
import java.nio.*;
public final class ChordPolicyTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static byte[] key(int code){return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putShort((short)1).putShort((short)code).putInt(1).array();}
    public static void main(String[] args){
        ChordPolicy.Recorder record=new ChordPolicy.Recorder();
        check(record.event(97,true,false)==null);check(record.event(34,true,false)==null); // right Ctrl+G
        check(record.event(34,false,false)==null);ChordPolicy g=record.event(97,false,false);check(g!=null&&g.trigger==34&&g.modifiers==1);
        check(ChordPolicy.decode(g.encode()).encode().equals(g.encode()));check(ChordPolicy.decode("2:1:34")==null);
        record.event(29,true,false);check(record.event(29,false,false)==null&&record.error!=null);
        record.reset();record.event(30,true,false);check(record.event(30,false,false)==null&&record.error!=null);
        record.reset();record.event(29,true,false);record.event(34,true,true);record.event(34,false,false);check(record.event(29,false,false)==null);
        check(!ChordPolicy.valid(1,116));check(!ChordPolicy.valid(0,34));check(!ChordPolicy.valid(1,29));
        check(ChordPolicy.migrate(0).trigger==68);check(ChordPolicy.migrate(2).trigger==87);
        for(int trigger:new int[]{34,87,88})for(int ctrl:new int[]{29,97})for(int alt:new int[]{56,100}){
            ChordPolicy chord=new ChordPolicy(3,trigger);boolean[] pressed=new boolean[0x300];pressed[ctrl]=pressed[alt]=true;
            check(GrabPolicy.convert(key(trigger),24,pressed,chord).escape);
            ChordPolicy.Cycle cycle=new ChordPolicy.Cycle();
            check(!cycle.event(chord,ctrl,1,true,false));check(!cycle.event(chord,alt,3,true,false));
            check(!cycle.event(chord,trigger,3,true,false));check(cycle.pending());
            check(!cycle.event(chord,trigger,3,false,false));check(!cycle.event(chord,ctrl,2,false,false));check(cycle.event(chord,alt,0,false,false));
            check(!cycle.event(chord,trigger,0,false,false)); // orphan UP after root release never rearms
            check(!cycle.event(chord,trigger,3,true,true));check(!cycle.pending());
        }
        ChordPolicy meta=new ChordPolicy(ChordPolicy.META|ChordPolicy.SHIFT,20);boolean[] pressed=new boolean[0x300];pressed[126]=pressed[54]=true;check(GrabPolicy.convert(key(20),24,pressed,meta).escape);
        ChordPolicy.Cycle canceled=new ChordPolicy.Cycle();canceled.event(g,34,1,true,false);canceled.reset();check(!canceled.event(g,34,0,false,false));
        System.out.println("Recorded chord: final key-up save, schema/migration, letters/F12, normalized Ctrl/Alt/Shift/Meta, invalid/repeat rejection, same-cycle orphan-UP prevention and root/UI match passed");
    }
}
