package io.github.collegeming.archlauncher;
public final class BrokerGateTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        BrokerGate gate=new BrokerGate();
        check(gate.status(0)==BrokerGate.STALE);
        long old=gate.attach();check(gate.status(old)==0);
        check(gate.pause(old,BrokerGate.PAUSED));check(gate.status(old)==BrokerGate.PAUSED);
        check(gate.retire(old));long current=gate.attach();check(current>old);
        check(!gate.pause(old,BrokerGate.PAUSED));check(gate.status(current)==0);
        check(!gate.retire(old));check(gate.status(current)==0);
        check(gate.status(old)==BrokerGate.STALE);
        check(gate.pause(current,BrokerGate.ESCAPE));check(gate.status(current)==BrokerGate.ESCAPE);
        long resumed=gate.attach();check(gate.status(resumed)==0);
        gate.pause(resumed,BrokerGate.NOT_DELIVERED);check(gate.status(resumed)==BrokerGate.NOT_DELIVERED);
        check(!BrokerGate.label(BrokerGate.NOT_DELIVERED).equals(BrokerGate.label(BrokerGate.ESCAPE)));
        for(int i=0;i<1000;i++){
            long prior=gate.generation();long next=gate.attach();
            check(!gate.retire(prior));check(!gate.pause(prior,BrokerGate.PAUSED));check(gate.status(next)==0);
            check(gate.pause(next,BrokerGate.PAUSED));check(gate.retire(next));
        }
        System.out.println("Broker lifecycle: release/reattach/write readiness, old death/release isolation, escape/failure distinction passed");
    }
}
