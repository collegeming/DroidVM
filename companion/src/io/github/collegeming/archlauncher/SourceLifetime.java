package io.github.collegeming.archlauncher;
/** Guards already-posted provider callbacks after replacement/shutdown. Owner: main scheduler. */
final class SourceLifetime {
    private long sequence,active;
    long begin(){active=++sequence;return active;}
    void close(){active=0;}
    boolean current(long id){return active>0&&active==id;}
}
