package io.github.collegeming.archlauncher;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;

/** Own native Surface; no official Activity is started. Touchscreen is never EVIOCGRAB'ed. */
public final class DisplayActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private SurfaceView surface,cursor;
    private FrameLayout area;
    private TextView status;
    private boolean statusExpanded;
    private NativeSurfaceSource source;
    private final SourceLifetime sourceLifetime=new SourceLifetime();
    private InputForwarder input;
    private boolean resumed,displayReady,ime,manualGate;
    private int width=2272,height=1514,buttons;
    private boolean mouseKnown, touchActive;
    private float mouseX,mouseY;
    private SessionService service;
    private final Runnable findSession=new Runnable(){public void run(){
        if(!resumed)return;
        if(SessionService.active!=null && service!=SessionService.active){if(service!=null)service.removeDisplay(DisplayActivity.this);disconnected();service=SessionService.active;service.attachDisplay(DisplayActivity.this);}
        updateStatus();main.postDelayed(this,500);
    }};
    public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        area=new FrameLayout(this);area.setBackgroundColor(0xff000000);
        surface=new SurfaceView(this);surface.setFocusableInTouchMode(true);area.addView(surface,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        cursor=new SurfaceView(this);cursor.setZOrderMediaOverlay(true);cursor.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);cursor.setX(-10000);cursor.setY(-10000);area.addView(cursor,new FrameLayout.LayoutParams(64,64));
        status=new TextView(this);status.setTextColor(0xffffffff);status.setBackgroundColor(0xdd202020);status.setTextSize(12);status.setPadding(16,12,16,12);status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLayout=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);statusLayout.bottomMargin=64;
        area.addView(status,statusLayout);
        LinearLayout controls=new LinearLayout(this);
        TextView info=new TextView(this);info.setText("状态");info.setTextColor(0xffffffff);info.setBackgroundColor(0x99202020);info.setPadding(16,12,16,12);
        info.setOnClickListener(v->{statusExpanded=!statusExpanded;status.setVisibility(statusExpanded?View.VISIBLE:View.GONE);updateStatus();});controls.addView(info);
        TextView settings=new TextView(this);settings.setText("设置");settings.setTextColor(0xffffffff);settings.setBackgroundColor(0x99202020);settings.setPadding(16,12,16,12);settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));controls.addView(settings);
        area.addView(controls,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END));
        area.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fit());
        area.setOnApplyWindowInsetsListener((v,insets)->{boolean next=insets.isVisible(WindowInsets.Type.ime());if(ime!=next){ime=next;refreshGate();}return insets;});
        surface.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){suspend();refreshGate();return true;}
            if(input==null||service==null||!service.canInput())return false;
            if(e.isFromSource(InputDevice.SOURCE_MOUSE))return mouse(e);
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)touchActive=true;
            else if(!touchActive)return true; // a new lease cannot adopt a finger already down
            MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[e.getPointerCount()];
            MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[e.getPointerCount()];
            for(int i=0;i<props.length;i++){props[i]=new MotionEvent.PointerProperties();coords[i]=new MotionEvent.PointerCoords();e.getPointerProperties(i,props[i]);e.getPointerCoords(i,coords[i]);coords[i].x=Math.max(0,Math.min(surface.getWidth()-1,coords[i].x));coords[i].y=Math.max(0,Math.min(surface.getHeight()-1,coords[i].y));}
            MotionEvent copy=MotionEvent.obtain(e.getDownTime(),e.getEventTime(),e.getAction(),props.length,props,coords,e.getMetaState(),e.getButtonState(),e.getXPrecision(),e.getYPrecision(),e.getDeviceId(),e.getEdgeFlags(),e.getSource(),e.getFlags());
            input.sendTouchEvent(copy,EvdevEncoder.NORMALIZED_ABS_MAX/(float)Math.max(1,surface.getWidth()-1),EvdevEncoder.NORMALIZED_ABS_MAX/(float)Math.max(1,surface.getHeight()-1));copy.recycle();if(e.getActionMasked()==MotionEvent.ACTION_UP)touchActive=false;return true;
        });
        surface.setOnGenericMotionListener((v,e)->mouse(e));
        setContentView(area);surface.requestFocus();
        getWindow().getDecorView().post(this::applyFullscreen);
    }
    void sessionReady(){
        if(source==null&&service!=null){
            final SessionService captured=service;
            final long sourceId=sourceLifetime.begin();
            source=new NativeSurfaceSource(surface,width,height,captured::displayBinder,main,new DisplaySource.Callbacks(){
                public void onContentSize(int w,int h){if(!sourceLifetime.current(sourceId)||service!=captured)return;width=w;height=h;fit();}
                public void onStateChanged(DisplaySource.State state){if(!sourceLifetime.current(sourceId)||service!=captured)return;displayReady=state==DisplaySource.State.CONNECTED;refreshGate();updateStatus();}
            });
            source.setCursorView(cursor);source.setCursorListener((x,y)->{if(!sourceLifetime.current(sourceId)||service!=captured)return;cursor.setX(surface.getX()+x*surface.getWidth()/(float)width);cursor.setY(surface.getY()+y*surface.getHeight()/(float)height);});
        }
        if(service!=null&&service.canInput()&&input==null)input=service.newForwarder();
        refreshGate();
    }
    void refreshGate(){
        if(service==null)return;
        boolean allowed=SessionPolicy.accepts(resumed,hasWindowFocus(),displayReady,ime,service.inputBlocked());
        if(!allowed){if(input!=null)clearInput();if(!manualGate){manualGate=true;service.gate(false);}}
        else{manualGate=false;service.gate(true);if(service.canInput()&&input==null)input=service.newForwarder();}
    }
    void clearInput(){if(input!=null){input.close();input=null;}buttons=0;mouseKnown=false;touchActive=false;}
    private void suspend(){clearInput();if(service!=null)service.gate(false);}
    void disconnected(){sourceLifetime.close();clearInput();if(source!=null){source.shutdown();source=null;}displayReady=false;status.setText("已断开；VM继续运行");}
    void updateStatus(){status.setText(service==null?"请先在设置连接路线":service.status()+"\nSurface: "+(displayReady?"已连接":"等待") );}
    private void fit(){int w=area.getWidth(),h=area.getHeight();if(w<=0||h<=0)return;if((long)w*height>(long)h*width)w=h*width/height;else h=w*height/width;surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));}
    private boolean mouse(MotionEvent e){
        if(input==null||service==null||!service.canInput()||!e.isFromSource(InputDevice.SOURCE_MOUSE))return false;
        int mode=getSharedPreferences("settings",0).getInt("mode",0);
        if(mode==2)input.sendAbsMove(Math.round(Math.max(0,Math.min(1,e.getX()/Math.max(1,surface.getWidth()-1)))*EvdevEncoder.NORMALIZED_ABS_MAX),Math.round(Math.max(0,Math.min(1,e.getY()/Math.max(1,surface.getHeight()-1)))*EvdevEncoder.NORMALIZED_ABS_MAX));
        else if(mouseKnown)input.sendMouseMove(Math.round((e.getX()-mouseX)*width/Math.max(1,surface.getWidth())),Math.round((e.getY()-mouseY)*height/Math.max(1,surface.getHeight())));
        mouseX=e.getX();mouseY=e.getY();mouseKnown=true;
        int next=e.getButtonState();int[] masks={MotionEvent.BUTTON_PRIMARY,MotionEvent.BUTTON_SECONDARY,MotionEvent.BUTTON_TERTIARY};short[] codes={EvdevEncoder.BTN_LEFT,EvdevEncoder.BTN_RIGHT,EvdevEncoder.BTN_MIDDLE};
        for(int i=0;i<3;i++)if(((buttons^next)&masks[i])!=0)input.sendPointerButton(codes[i],(next&masks[i])!=0);buttons=next;
        if(e.getActionMasked()==MotionEvent.ACTION_SCROLL)input.sendScroll(Math.round(e.getAxisValue(MotionEvent.AXIS_VSCROLL)),Math.round(e.getAxisValue(MotionEvent.AXIS_HSCROLL)));
        return true;
    }
    public boolean dispatchKeyEvent(KeyEvent e){
        if(e.getKeyCode()==KeyEvent.KEYCODE_F12 && e.isCtrlPressed()&&e.isAltPressed()){
            if(e.getAction()==KeyEvent.ACTION_DOWN)startService(new Intent(this,SessionService.class).setAction(SessionService.RELEASE));return true;
        }
        if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)startActivity(new Intent(this,SettingsActivity.class));return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_DOWN||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_UP)return super.dispatchKeyEvent(e);
        if(input!=null&&service!=null&&service.canInput()&&(e.getAction()==KeyEvent.ACTION_DOWN||e.getAction()==KeyEvent.ACTION_UP)){
            if(e.getRepeatCount()>0)return true;
            if(input.sendKeyEvent(e.getKeyCode(),e.getAction()==KeyEvent.ACTION_DOWN))return true;
        }
        return super.dispatchKeyEvent(e);
    }
    private void applyFullscreen(){
        if(isFinishing()||isDestroyed()||area==null)return;
        View decor=getWindow().getDecorView();
        if(!decor.isAttachedToWindow())return;
        WindowInsetsController ctl=decor.getWindowInsetsController();
        if(ctl!=null){ctl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);ctl.hide(WindowInsets.Type.systemBars());}
    }
    protected void onResume(){super.onResume();resumed=true;manualGate=false;main.post(findSession);getWindow().getDecorView().post(this::applyFullscreen);refreshGate();}
    protected void onPause(){resumed=false;main.removeCallbacks(findSession);suspend();super.onPause();}
    public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)applyFullscreen();refreshGate();}
    protected void onDestroy(){if(service!=null)service.removeDisplay(this);disconnected();super.onDestroy();}
}
