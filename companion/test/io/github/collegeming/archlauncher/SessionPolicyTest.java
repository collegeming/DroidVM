package io.github.collegeming.archlauncher;
public final class SessionPolicyTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        check(SessionPolicy.allowed(SessionPolicy.IDS[0]));check(SessionPolicy.allowed(SessionPolicy.IDS[1]));check(!SessionPolicy.allowed("other"));
        for(int bits=0;bits<32;bits++)check(SessionPolicy.accepts((bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0)==(bits==7));
        check(!SessionPolicy.current(0,0));check(SessionPolicy.current(3,3));check(!SessionPolicy.current(4,3));
        for(int c=0;c<2;c++)for(int a=0;a<2;a++)check(SessionPolicy.escape(88,c==0,c==1,a==0,a==1));
        check(!SessionPolicy.escape(88,true,false,false,false));check(!SessionPolicy.escape(87,true,false,true,false));
        System.out.println("Session policy: routes, all focus gates, stale generations, left/right escape passed");
    }
}
