package io.github.collegeming.archlauncher;

/** Explicit managed-start allowlist. No disk paths/names, no fallback to stock or runtime writes. */
final class StartPolicy {
    static final String GUARD="--droidvm-profile-guard=v1";
    static final String DISPATCHER_SHA="c866c124d0d6c8d806421421ef8d26f19731c128cb20f64e0372c91f3dfda9ae";
    static String profile(String id){
        if(SessionPolicy.IDS[0].equals(id))return "gfxstream-v1";
        if(SessionPolicy.IDS[1].equals(id))return "panvk-v1";
        return null;
    }
    static boolean configured(String id,String[] environment,String[] options){
        String expected=profile(id);if(expected==null||environment==null||options==null)return false;
        int profiles=0,guards=0;
        for(String env:environment){
            if(env==null)return false;
            if(env.startsWith("DROIDVM_RUNTIME_PROFILE=")){
                profiles++;if(!env.equals("DROIDVM_RUNTIME_PROFILE="+expected))return false;
            }
        }
        for(String option:options){
            if(option==null)return false;
            if(option.contains("droidvm-profile-guard")){if(!GUARD.equals(option))return false;guards++;}
        }
        return profiles==1&&guards==1;
    }
    static final String DURABLE_DISPATCHER_SHA="02a90a480895e7324d41679ef226063c137f573058ba6e2019e5374b89922ec0";
    static boolean knownDispatcher(String digest){return DISPATCHER_SHA.equals(digest)||DURABLE_DISPATCHER_SHA.equals(digest);}
    static boolean mayStart(String state){return "stopped".equals(state);}
    static boolean attachOnly(String state){return "running".equals(state);}
}
