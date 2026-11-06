package io.github.collegeming.archlauncher;
/** Persist these stable IDs, not translated labels. Root raw reader and Android UI share matches. */
final class HotkeyPolicy {
    static final String[] LABELS={"Ctrl + Alt + F10","Ctrl + Alt + F11","Ctrl + Alt + F12"};
    static int preset(int value,int fallback){return value>=0&&value<LABELS.length?value:fallback;}
    static int scan(int preset){return new int[]{68,87,88}[preset(preset,2)];}
    static boolean match(int preset,int code,boolean ctrl,boolean alt){return ctrl&&alt&&code==scan(preset);}
    static boolean release(int preset,int code,boolean ctrl,boolean alt){return match(2,code,ctrl,alt)||match(preset,code,ctrl,alt);}
    static boolean release(int preset,int code,boolean[] pressed){return release(preset,code,pressed[29]||pressed[97],pressed[56]||pressed[100]);}
    // Emergency is release-only, even if selected as the enter key or both configured keys coincide.
    static boolean enter(int enter,int code,boolean ctrl,boolean alt){return !match(2,code,ctrl,alt)&&match(enter,code,ctrl,alt);}
    static final int NONE=0, RELEASE=1, ENTER=2;
    static int action(int enter,int release,int code,boolean ctrl,boolean alt,boolean active){
        if(match(2,code,ctrl,alt))return RELEASE;
        boolean releasing=release(release,code,ctrl,alt),entering=enter(enter,code,ctrl,alt);
        if(releasing&&active)return RELEASE;
        if(entering)return ENTER;
        return releasing?RELEASE:NONE;
    }
}
