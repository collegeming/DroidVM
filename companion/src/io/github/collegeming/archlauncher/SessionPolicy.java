package io.github.collegeming.archlauncher;

/** No Android dependency: session identity and focus gates are tested on the build host. */
public final class SessionPolicy {
    public static final String[] IDS = {"d570d1fd-afcf-407d-a82c-fccadc203cf1", "ecb0505e-718b-49a2-b284-de72d735fde1"};
    public static final String[] NAMES = {"Arch Linux · gfxstream", "Arch Linux · PanVK"};
    public static int route(int route) { return route == 1 ? 1 : 0; }
    public static boolean allowed(String id) { return IDS[0].equals(id) || IDS[1].equals(id); }
    public static boolean accepts(boolean resumed, boolean focus, boolean display, boolean ime, boolean manual) {
        return resumed && focus && display && !ime && !manual;
    }
    public static boolean current(long active, long supplied) { return active > 0 && active == supplied; }
    public static boolean escape(int code, boolean ctrlLeft, boolean ctrlRight, boolean altLeft, boolean altRight) {
        return code == 88 && (ctrlLeft || ctrlRight) && (altLeft || altRight);
    }
}
