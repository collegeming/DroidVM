package io.github.collegeming.archlauncher;
/** Fixed codes only: never copy daemon text, credentials or exception messages to the UI. */
final class RpcFailure extends java.io.IOException {
    static final long AUTH=-20, GET=-21, PING=-22, STATUS=-23, START=-24, SESSION_CLOSED=-25, INPUT=-26;
    final long code;
    RpcFailure(long code){super("RPC_FAILURE");this.code=code;}
    static long command(String command){
        if("vm_get".equals(command))return GET;if("ping".equals(command))return PING;
        if("vm_status".equals(command))return STATUS;if("vm_start".equals(command))return START;if("vm_input".equals(command))return INPUT;return SESSION_CLOSED;
    }
    static String label(long code){
        if(code==AUTH)return "AUTH_TIMEOUT_OR_IO";if(code==GET)return "VM_GET_TIMEOUT_OR_IO";
        if(code==PING)return "PING_TIMEOUT_OR_IO";if(code==STATUS)return "VM_STATUS_TIMEOUT_OR_IO";
        if(code==START)return "VM_START_REPLY_TIMEOUT_OR_IO";if(code==INPUT)return "VM_INPUT_REPLY_TIMEOUT_OR_IO";return "SESSION_CLOSED_AFTER_IO";
    }
}
