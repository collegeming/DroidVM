package io.github.collegeming.archlauncher;

/** Shared by the main-thread owner and input worker. No network call is made under this lock. */
final class InputLease {
    static final class Ticket {
        final long generation, epoch;
        Ticket(long generation,long epoch){this.generation=generation;this.epoch=epoch;}
    }
    static final class Attempt {
        final long epoch;
        final boolean enable;
        Attempt(long epoch,boolean enable){this.epoch=epoch;this.enable=enable;}
    }
    private long epoch;
    private boolean wanted;
    private Attempt pending;
    private Ticket active;
    synchronized void want(boolean value){wanted=value;if(!value)close();}
    synchronized void close(){++epoch;active=null;wanted=false;}
    synchronized Attempt begin(boolean enable){active=null;pending=new Attempt(++epoch,enable);return pending;}
    synchronized boolean pending(){return pending!=null;}
    synchronized boolean complete(Attempt attempt,long generation){
        if(pending!=attempt)return false;
        pending=null;
        if(attempt.epoch==epoch&&attempt.enable&&wanted&&generation>0)active=new Ticket(generation,epoch);
        return true;
    }
    synchronized boolean fail(Attempt attempt){if(pending!=attempt)return false;pending=null;return true;}
    synchronized Ticket ticket(){return active;}
    synchronized boolean current(Ticket ticket){return ticket!=null&&active==ticket&&wanted;}
    synchronized boolean wanted(){return wanted;}
}
