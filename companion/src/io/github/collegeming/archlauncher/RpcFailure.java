package io.github.collegeming.archlauncher;
/** Fixed codes only: never copy daemon text, credentials or exception messages to the UI. */
final class RpcFailure extends java.io.IOException {
    static final long AUTH=-20, GET=-21, PING=-22, STATUS=-23, START=-24, SESSION_CLOSED=-25, INPUT=-26;
    static final int REMOTE_REJECTED=1, TIMEOUT=2, EOF_IO=3, FRAME_INVALID=4, JSON_INVALID=5, FRAME_LIMIT=6;
    final long code;
    RpcFailure(long code){super("RPC_FAILURE");this.code=code;}
    // Keep legacy phase codes for existing callers; classified codes also fit the Binder int path.
    static RpcFailure classified(long phase,int reason){return new RpcFailure(phase*10-reason);}
    static long phase(long code){return code<=-201&&code>=-266?code/10:code;}
    static int reason(long code){return code<=-201&&code>=-266?(int)(-code%10):0;}
    static long command(String command){
        if("auth".equals(command))return AUTH;
        if("vm_get".equals(command))return GET;if("ping".equals(command))return PING;
        if("vm_status".equals(command))return STATUS;if("vm_start".equals(command))return START;if("vm_input".equals(command))return INPUT;return SESSION_CLOSED;
    }
    static String label(long code){
        int reason=reason(code);
        if(reason!=0){
            String suffix;
            switch(reason){
                case REMOTE_REJECTED:suffix="REMOTE_REJECTED";break;
                case TIMEOUT:suffix="TIMEOUT";break;
                case EOF_IO:suffix="EOF_IO";break;
                case FRAME_INVALID:suffix="FRAME_INVALID";break;
                case JSON_INVALID:suffix="JSON_INVALID";break;
                case FRAME_LIMIT:suffix="FRAME_LIMIT";break;
                default:return "RPC_FAILURE";
            }
            String prefix;
            switch((int)phase(code)){
                case (int)AUTH:prefix="AUTH";break;case (int)GET:prefix="VM_GET";break;
                case (int)PING:prefix="PING";break;case (int)STATUS:prefix="VM_STATUS";break;
                case (int)START:prefix="VM_START_REPLY";break;case (int)INPUT:prefix="VM_INPUT_REPLY";break;
                default:prefix="SESSION_CLOSED";
            }
            return prefix+"_"+suffix;
        }
        if(code==AUTH)return "AUTH_TIMEOUT_OR_IO";if(code==GET)return "VM_GET_TIMEOUT_OR_IO";
        if(code==PING)return "PING_TIMEOUT_OR_IO";if(code==STATUS)return "VM_STATUS_TIMEOUT_OR_IO";
        if(code==START)return "VM_START_REPLY_TIMEOUT_OR_IO";if(code==INPUT)return "VM_INPUT_REPLY_TIMEOUT_OR_IO";return "SESSION_CLOSED_AFTER_IO";
    }
}
