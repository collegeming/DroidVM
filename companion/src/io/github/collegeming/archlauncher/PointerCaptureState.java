package io.github.collegeming.archlauncher;

/** Main-thread capture lifecycle. A request is not proof of actual capture. No automatic retry loop. */
final class PointerCaptureState {
    static final int NONE=0, REQUEST=1, RELEASE=2;
    private Object lease;
    private boolean eligible,requested,actual,denied,releasing;
    int update(Object nextLease,boolean allowed){
        if(!allowed||nextLease==null){
            boolean release=actual||requested;
            lease=null;eligible=false;requested=false;denied=false;releasing|=release;
            return release?RELEASE:NONE;
        }
        if(releasing)return NONE; // wait for actual loss acknowledgement before binding a new lease
        if(lease!=nextLease){
            if(lease!=null&&(actual||requested)){lease=null;eligible=false;requested=false;releasing=true;return RELEASE;}
            lease=nextLease;requested=false;denied=false;
        }
        eligible=true;
        if(!actual&&!requested&&!denied){requested=true;return REQUEST;}
        return NONE;
    }
    /** Returns true only for an unexpected loss of an actual active capture. */
    boolean changed(boolean captured){
        boolean lost=actual&&!captured&&eligible&&!releasing;
        actual=captured;
        if(!captured&&releasing){releasing=false;eligible=false;lease=null;return false;}
        if(!captured&&eligible){requested=false;denied=true;}
        return lost;
    }
    boolean accepts(Object ticket){return actual&&eligible&&lease==ticket&&ticket!=null;}
    boolean actual(){return actual;}
    boolean eligible(){return eligible;}
    /** Read-only diagnostics: never expose a lease value or mutate capture state. */
    String debugState(Object ticket){
        return "leasePresent="+(lease!=null)+" ticketPresent="+(ticket!=null)+" leaseMatch="+(lease!=null&&lease==ticket)
            +" eligible="+eligible+" requested="+requested+" actual="+actual+" denied="+denied+" releasing="+releasing;
    }
}
