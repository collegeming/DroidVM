package io.github.collegeming.archlauncher;
/** Shared monitor for publishing a root generation, local arm and revocation. Never holds network IO. */
final class GrabLease {
    private long generation;
    private boolean revoked=true;
    synchronized void publish(long next,Runnable stopPrevious){stopPrevious.run();generation=next;revoked=false;}
    synchronized boolean arm(long session,Runnable localArm){
        if(session<=0||session!=generation||revoked)return false;
        localArm.run();return true;
    }
    synchronized boolean revoke(long session,Runnable localRelease){
        if(session<=0||session!=generation)return false;
        revoked=true;localRelease.run();return true;
    }
    synchronized boolean current(long session){return session>0&&session==generation&&!revoked;}
}
