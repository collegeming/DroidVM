package io.github.collegeming.archlauncher;

/** Guest pixels = relative view pixels * guest/view extent * sensitivity. Wheel units are not pixels. */
final class PointerMotion {
    static int sensitivityPercent(int value){return Math.max(25,Math.min(300,value));}
    private double x,y,vertical,horizontal;
    int[] move(double dx,double dy,int guestWidth,int guestHeight,int viewWidth,int viewHeight,int sensitivity){
        double scale=sensitivityPercent(sensitivity)/100.0;
        x+=finite(dx)*Math.max(1,guestWidth)/Math.max(1,viewWidth)*scale;
        y+=finite(dy)*Math.max(1,guestHeight)/Math.max(1,viewHeight)*scale;
        int outX=whole(x),outY=whole(y);x-=outX;y-=outY;return new int[]{outX,outY};
    }
    int[] scroll(double v,double h){vertical+=finite(v);horizontal+=finite(h);int outV=whole(vertical),outH=whole(horizontal);vertical-=outV;horizontal-=outH;return new int[]{outV,outH};}
    void reset(){x=y=vertical=horizontal=0;}
    private static double finite(double value){return Double.isFinite(value)?value:0;}
    private static int whole(double value){return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,value));}
    static int buttonChanges(int oldButtons,int newButtons){return oldButtons^newButtons;}
}
