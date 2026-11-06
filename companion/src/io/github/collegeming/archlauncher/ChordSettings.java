package io.github.collegeming.archlauncher;
import android.content.SharedPreferences;
/** One preference value: schema version, normalized modifier mask and evdev trigger. No typed text. */
final class ChordSettings {
    static final String KEY="input_chord_v1";
    static ChordPolicy load(SharedPreferences prefs){
        ChordPolicy chord=ChordPolicy.decode(prefs.getString(KEY,""));
        if(chord==null){chord=ChordPolicy.migrate(prefs.getInt("enter_hotkey",1));save(prefs,chord);}
        return chord;
    }
    static void save(SharedPreferences prefs,ChordPolicy chord){prefs.edit().putString(KEY,chord.encode()).remove("enter_hotkey").remove("release_hotkey").apply();}
    static int mask(android.view.KeyEvent event){return (event.isCtrlPressed()?ChordPolicy.CTRL:0)|(event.isAltPressed()?ChordPolicy.ALT:0)|(event.isShiftPressed()?ChordPolicy.SHIFT:0)|(event.isMetaPressed()?ChordPolicy.META:0);}
    static String label(ChordPolicy chord){
        StringBuilder out=new StringBuilder();if((chord.modifiers&ChordPolicy.CTRL)!=0)out.append("Ctrl + ");if((chord.modifiers&ChordPolicy.ALT)!=0)out.append("Alt + ");if((chord.modifiers&ChordPolicy.SHIFT)!=0)out.append("Shift + ");if((chord.modifiers&ChordPolicy.META)!=0)out.append("Meta + ");
        for(int key=0;key<=android.view.KeyEvent.getMaxKeyCode();key++)if(KeyCodeMapper.androidToEvdev(key)==chord.trigger){out.append(android.view.KeyEvent.keyCodeToString(key).replace("KEYCODE_",""));return out.toString();}
        return out.append("scan ").append(chord.trigger).toString();
    }
}
