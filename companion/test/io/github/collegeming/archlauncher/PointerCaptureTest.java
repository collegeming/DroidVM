package io.github.collegeming.archlauncher;
public final class PointerCaptureTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        PointerCaptureState state=new PointerCaptureState();Object first=new Object(),second=new Object();
        check(state.update(first,false)==0);check(state.update(first,true)==PointerCaptureState.REQUEST);
        check(!state.actual()&&!state.accepts(first));check(state.update(first,true)==0); // requested != held
        state.changed(true);check(state.actual()&&state.accepts(first));
        check(state.update(second,true)==PointerCaptureState.RELEASE);check(!state.accepts(first)&&!state.accepts(second));
        check(state.update(second,true)==0);state.changed(false);check(state.update(second,true)==PointerCaptureState.REQUEST);
        state.changed(true);check(state.accepts(second));check(state.update(null,false)==PointerCaptureState.RELEASE);
        check(!state.accepts(second));check(!state.changed(false));check(state.update(null,false)==0);
        check(state.update(first,true)==PointerCaptureState.REQUEST);state.changed(true);check(state.changed(false));
        check(state.update(first,true)==0); // unexpected loss is not a retry loop
        PointerMotion motion=new PointerMotion();int total=0;for(int i=0;i<10;i++)total+=motion.move(.1,0,1000,500,500,250,100)[0];check(total==2);
        motion.reset();int[] diagonal=motion.move(10,-10,2000,1000,1000,500,150);check(diagonal[0]==30&&diagonal[1]==-30);
        check(PointerMotion.sensitivityPercent(0)==25&&PointerMotion.sensitivityPercent(999)==300);
        motion.reset();int[] wheel={0,0};for(int i=0;i<4;i++){int[] step=motion.scroll(.25,-.25);wheel[0]+=step[0];wheel[1]+=step[1];}check(wheel[0]==1&&wheel[1]==-1);
        check(PointerMotion.buttonChanges(0,1)==1);check(PointerMotion.buttonChanges(1,7)==6);check(PointerMotion.buttonChanges(7,0)==7);
        motion.reset();int[] invalid=motion.move(Double.NaN,Double.POSITIVE_INFINITY,100,100,100,100,100);check(invalid[0]==0&&invalid[1]==0);
        System.out.println("Pointer capture: requested/actual separation, lease revocation/stale callbacks/no retry loop; guest/view sensitivity, fractional deltas, buttons and dual-axis wheel passed");
    }
}
