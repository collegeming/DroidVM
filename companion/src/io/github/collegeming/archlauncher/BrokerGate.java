package io.github.collegeming.archlauncher;

/** Root broker's actual lifecycle state; callers serialize access. No Android dependencies. */
final class BrokerGate {
    static final int DELIVERED=1, STALE=2, PAUSED=3, ESCAPE=4, INVALID=5, NOT_DELIVERED=6, IPC_ERROR=7, ACK_MISSING=9;
    private long sequence, generation;
    private int pauseReason=PAUSED;
    long attach(){generation=++sequence;pauseReason=0;return generation;}
    boolean current(long supplied){return SessionPolicy.current(generation,supplied);}
    int status(long supplied){return !current(supplied)?STALE:pauseReason;}
    boolean pause(long supplied,int reason){if(!current(supplied))return false;pauseReason=reason;return true;}
    boolean retire(long supplied){if(!current(supplied))return false;generation=0;pauseReason=PAUSED;return true;}
    long generation(){return generation;}
    static String label(int result){switch(result){
        case DELIVERED:return "DELIVERED";case STALE:return "STALE_GENERATION";case PAUSED:return "BROKER_PAUSED";
        case ESCAPE:return "ESCAPE_RELEASED";case INVALID:return "INVALID_BATCH";case NOT_DELIVERED:return "DAEMON_NOT_DELIVERED";
        case IPC_ERROR:return "IPC_ERROR";case ACK_MISSING:return "ACK_MISSING_DELIVERED";default:return result<=RpcFailure.AUTH?RpcFailure.label(result):"UNKNOWN";
    }}
}
