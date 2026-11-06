package io.github.collegeming.archlauncher;

/** One versioned evdev chord shared by recording, Android dispatch and the root raw reader. */
final class ChordPolicy {
    static final int CTRL=1,ALT=2,SHIFT=4,META=8;
    static final ChordPolicy DEFAULT=new ChordPolicy(CTRL|ALT,87); // Ctrl+Alt+F11
    final int modifiers,trigger;
    ChordPolicy(int modifiers,int trigger){if(!valid(modifiers,trigger))throw new IllegalArgumentException("invalid chord");this.modifiers=modifiers;this.trigger=trigger;}
    static int modifier(int scan){switch(scan){case 29:case 97:return CTRL;case 56:case 100:return ALT;case 42:case 54:return SHIFT;case 125:case 126:return META;default:return 0;}}
    static boolean valid(int modifiers,int trigger){
        if(modifiers<=0||(modifiers&~15)!=0||modifier(trigger)!=0)return false;
        // Stable common typing keys, function keys and navigation keys; never host power/volume/Home/Back.
        return trigger>=2&&trigger<=13||trigger>=16&&trigger<=28||trigger>=30&&trigger<=41||trigger>=43&&trigger<=53
            ||trigger>=57&&trigger<=68||trigger==87||trigger==88||trigger>=102&&trigger<=111;
    }
    boolean matches(int scan,int mask){return trigger==scan&&(mask&15)==modifiers;}
    boolean matches(int scan,boolean[] pressed){int mask=0;for(int i=0;i<pressed.length;i++)if(pressed[i])mask|=modifier(i);return matches(scan,mask);}
    String encode(){return "1:"+modifiers+":"+trigger;}
    static ChordPolicy decode(String value){try{String[] fields=value.split(":",-1);if(fields.length!=3||!fields[0].equals("1"))return null;return new ChordPolicy(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]));}catch(Exception ignored){return null;}}
    static ChordPolicy migrate(int oldEnter){return new ChordPolicy(CTRL|ALT,oldEnter==0?68:87);}
    static final class Cycle {
        private boolean candidate;
        private final java.util.Set<Integer> down=new java.util.HashSet<>();
        /** Only a trigger DOWN observed by this cycle can toggle on the final UP. Orphan UP never enters. */
        boolean event(ChordPolicy chord,int scan,int mask,boolean pressed,boolean repeat){
            if(pressed){down.add(scan);int observed=0;for(int code:down)observed|=modifier(code);if(!repeat&&chord.matches(scan,mask)&&observed==chord.modifiers)candidate=true;return false;}
            down.remove(scan);int remainingMask=(mask&15)&~modifier(scan);if(candidate&&down.isEmpty()&&remainingMask==0){candidate=false;return true;}return false;
        }
        boolean pending(){return candidate;}
        void reset(){candidate=false;down.clear();}
    }
    static final class Recorder {
        private final java.util.Set<Integer> down=new java.util.HashSet<>();
        private ChordPolicy candidate;
        private boolean rejected;
        private int modifiers;
        String error;
        ChordPolicy event(int scan,boolean pressed,boolean repeat){
            if(pressed){
                if(repeat){rejected=true;error="请松开后重新录入，不接受长按重复";}
                down.add(scan);int mod=modifier(scan);modifiers|=mod;
                if(mod==0){
                    if(candidate!=null||!valid(modifiers,scan)){rejected=true;error="需要至少一个修饰键和一个可用触发键；不接受系统键";}
                    else candidate=new ChordPolicy(modifiers,scan);
                }
                return null;
            }
            down.remove(scan);
            if(!down.isEmpty())return null;
            if(candidate!=null&&!rejected){ChordPolicy result=candidate;reset();return result;}
            if(candidate==null)error="不能仅录入修饰键，请重新录入组合";
            candidate=null;rejected=false;modifiers=0;return null;
        }
        void reset(){down.clear();candidate=null;rejected=false;modifiers=0;error=null;}
    }
}
