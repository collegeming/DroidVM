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
    // Main-thread-only, in-memory aggregate counters: entry x fixed key label x DOWN/UP.
    private static final String[] KEY_TEST_LABELS={"Alt","Meta","Tab","T"};
    private final int[] keyTestCounts=new int[16];
    private long keyTestUntil;
    private boolean keyTestSeen;
    private final Runnable keyTestTimeout=()->stopKeyTest();
    private final ChordPolicy.Cycle chordCycle=new ChordPolicy.Cycle();
    private NativeSurfaceSource source;
    private final SourceLifetime sourceLifetime=new SourceLifetime();
    private InputForwarder input;
    private boolean resumed,displayReady,ime,manualGate;
    private int width=2272,height=1514,buttons;
    private boolean mouseKnown, touchActive;
    private final PointerCaptureState pointerCapture=new PointerCaptureState();
    private final PointerMotion pointerMotion=new PointerMotion();
    private boolean captureSuppressed;
    // One focus attempt per new/recreated source, not per resume, lease, or status poll.
    private boolean surfaceFocusPending;
    private int pointerViewWidth,pointerViewHeight,pointerGuestWidth,pointerGuestHeight;
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
        surface=new SurfaceView(this){
            @Override public void onPointerCaptureChange(boolean captured){super.onPointerCaptureChange(captured);captureChanged(captured);}
        };surface.setFocusableInTouchMode(true);area.addView(surface,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        cursor=new SurfaceView(this);cursor.setZOrderMediaOverlay(true);cursor.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);cursor.setX(-10000);cursor.setY(-10000);area.addView(cursor,new FrameLayout.LayoutParams(64,64));
        status=new TextView(this);status.setTextColor(0xffffffff);status.setBackgroundColor(0xdd202020);status.setTextSize(12);status.setPadding(16,12,16,12);status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLayout=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);statusLayout.bottomMargin=64;
        area.addView(status,statusLayout);
        LinearLayout controls=new LinearLayout(this);
        TextView info=new TextView(this);info.setText("状态");info.setTextColor(0xffffffff);info.setBackgroundColor(0x99202020);info.setPadding(16,12,16,12);
        info.setContentDescription("状态；长按开始/结束组合键测试（最多10秒）；仅统计Alt/Meta/Tab/T，不记录其他键");
        info.setOnClickListener(v->{statusExpanded=!statusExpanded;status.setVisibility(statusExpanded?View.VISIBLE:View.GONE);updateStatus();});
        info.setOnLongClickListener(v->{toggleKeyTest();return true;});controls.addView(info);
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
        surface.setOnFocusChangeListener((v,focus)->{if(!focus){stopKeyTest();suspend();}refreshGate();});
        surface.setOnGenericMotionListener((v,e)->mouse(e));
        surface.setOnCapturedPointerListener((v,e)->capturedMouse(e));
        setContentView(area);surface.requestFocus();
        getWindow().getDecorView().post(this::applyFullscreen);
    }
    void sessionReady(){
        if(source==null&&service!=null){
            final SessionService captured=service;
            final long sourceId=sourceLifetime.begin();
            surfaceFocusPending=true;
            source=new NativeSurfaceSource(surface,width,height,captured::displayBinder,main,new DisplaySource.Callbacks(){
                public void onContentSize(int w,int h){if(!sourceLifetime.current(sourceId)||service!=captured)return;width=w;height=h;fit();}
                public void onStateChanged(DisplaySource.State state){if(!sourceLifetime.current(sourceId)||service!=captured)return;
                    boolean ready=state==DisplaySource.State.CONNECTED;
                    if(displayReady&&!ready)surfaceFocusPending=true; // provider lost/recreated its Surface
                    displayReady=ready;refreshGate();updateStatus();
                }
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
        restoreSurfaceFocus();
        updatePointerCapture();
    }
    private void restoreSurfaceFocus(){
        if(!surfaceFocusPending||captureSuppressed||isFinishing()||isDestroyed()
            ||service==null||!SessionPolicy.accepts(resumed,hasWindowFocus(),displayReady,ime,service.inputBlocked())
            ||!service.canInput()||input==null||surface==null||!surface.isAttachedToWindow())return;
        surfaceFocusPending=false; // consume before requestFocus's synchronous focus callback
        if(!surface.isFocused())surface.requestFocus();
    }
    void allowPointerCapture(){captureSuppressed=false;}
    void clearInput(){chordCycle.reset();stopPointerCapture();if(input!=null){input.close();input=null;}buttons=0;mouseKnown=false;touchActive=false;pointerMotion.reset();}
    private boolean captureEligible(){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        return !captureSuppressed&&prefs.getInt("mode",0)==1&&prefs.getBoolean("pointer_capture",false)
            &&resumed&&hasWindowFocus()&&displayReady&&!ime&&surface!=null&&surface.isFocused()&&input!=null&&service!=null&&service.canInput();
    }
    private void updatePointerCapture(){
        if(surface==null)return;
        Object ticket=service==null?null:service.inputLeaseIdentity();
        int action=pointerCapture.update(ticket,captureEligible());
        if(action==PointerCaptureState.RELEASE){surface.releasePointerCapture();if(!surface.hasPointerCapture())pointerCapture.changed(false);}
        else if(action==PointerCaptureState.REQUEST){
            releasePointerButtons();pointerMotion.reset();mouseKnown=false;
            surface.requestPointerCapture();
        }
    }
    private void stopPointerCapture(){
        pointerCapture.update(null,false);
        if(surface!=null){surface.releasePointerCapture();if(!surface.hasPointerCapture())pointerCapture.changed(false);}
    }
    private void captureChanged(boolean captured){
        if(isFinishing()||isDestroyed()){pointerCapture.update(null,false);if(captured)surface.releasePointerCapture();return;}
        boolean unexpected=pointerCapture.changed(captured);
        buttons=0;mouseKnown=false;pointerMotion.reset();
        if(captured&&(!captureEligible()||!pointerCapture.accepts(service==null?null:service.inputLeaseIdentity()))){stopPointerCapture();return;}
        if(unexpected){
            // Release guest buttons even if the platform removes capture without a focus callback.
            captureSuppressed=true;clearInput();if(service!=null)service.gate(false);
            main.post(()->{if(resumed)refreshGate();}); // ordinary mode, no automatic capture loop
        }
        if(!captured&&!unexpected&&resumed)main.post(this::updatePointerCapture);
        updateStatus();
    }
    private void suspend(){clearInput();if(service!=null)service.gate(false);}
    void disconnected(){sourceLifetime.close();surfaceFocusPending=false;clearInput();if(source!=null){source.shutdown();source=null;}displayReady=false;status.setText("已断开；VM继续运行");}
    void updateStatus(){status.setText((service==null?"请先在设置连接路线":service.status()+"\nSurface: "+(displayReady?"已连接":"等待")+" · Pointer capture: "+(surface!=null&&surface.hasPointerCapture()?"实际已捕获":"未捕获"))+captureDebugState()+keyTestState());}
    private void toggleKeyTest(){
        if(keyTestUntil!=0){stopKeyTest();return;}
        if(!resumed||!hasWindowFocus())return;
        java.util.Arrays.fill(keyTestCounts,0);
        keyTestSeen=true;keyTestUntil=SystemClock.uptimeMillis()+10000;
        main.postDelayed(keyTestTimeout,10000);
        statusExpanded=true;status.setVisibility(View.VISIBLE);updateStatus();
    }
    private void stopKeyTest(){
        main.removeCallbacks(keyTestTimeout);
        if(keyTestUntil==0)return;
        keyTestUntil=0;updateStatus();
    }
    private void observeKeyTest(KeyEvent event,int entry){
        if(keyTestUntil==0)return;
        if(!resumed||!hasWindowFocus()||SystemClock.uptimeMillis()>=keyTestUntil){stopKeyTest();return;}
        int action=event.getAction();
        if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return;
        int label;
        switch(event.getKeyCode()){
            case KeyEvent.KEYCODE_ALT_LEFT:case KeyEvent.KEYCODE_ALT_RIGHT:label=0;break;
            case KeyEvent.KEYCODE_META_LEFT:case KeyEvent.KEYCODE_META_RIGHT:label=1;break;
            case KeyEvent.KEYCODE_TAB:label=2;break;
            case KeyEvent.KEYCODE_T:label=3;break;
            default:return;
        }
        int slot=entry*8+label*2+(action==KeyEvent.ACTION_UP?1:0);
        if(keyTestCounts[slot]<Integer.MAX_VALUE)keyTestCounts[slot]++;
        updateStatus();
    }
    private String keyTestState(){
        String text="\n组合键测试：长按状态开始/结束（最多10秒）\n仅统计Alt/Meta/Tab/T，不记录其他键";
        if(!keyTestSeen)return text;
        StringBuilder result=new StringBuilder(text).append(keyTestUntil==0?" · 已停止":" · 进行中");
        for(int entry=0;entry<2;entry++){
            result.append(entry==0?"\ndispatchKeyEvent DOWN/UP: ":"\ndispatchKeyShortcutEvent DOWN/UP: ");
            for(int label=0;label<KEY_TEST_LABELS.length;label++){
                int slot=entry*8+label*2;
                result.append(KEY_TEST_LABELS[label]).append(' ').append(keyTestCounts[slot]).append('/').append(keyTestCounts[slot+1]).append(' ');
            }
        }
        return result.toString();
    }
    private String captureDebugState(){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        Object ticket=service==null?null:service.inputLeaseIdentity();
        return "\nCapture gate: suppressed="+captureSuppressed+" mouseMode="+(prefs.getInt("mode",0)==1)+" enabled="+prefs.getBoolean("pointer_capture",false)
            +"\nresumed="+resumed+" windowFocus="+hasWindowFocus()+" displayReady="+displayReady+" ime="+ime
            +"\nsurfacePresent="+(surface!=null)+" focused="+(surface!=null&&surface.isFocused())+" hasFocus="+(surface!=null&&surface.hasFocus())+" attached="+(surface!=null&&surface.isAttachedToWindow())
            +"\ninputPresent="+(input!=null)+" servicePresent="+(service!=null)+" canInput="+(service!=null&&service.canInput())+" manualGate="+manualGate+" eligible="+captureEligible()+" focusPending="+surfaceFocusPending
            +"\nCapture machine: "+pointerCapture.debugState(ticket);
    }
    private void fit(){int w=area.getWidth(),h=area.getHeight();if(w<=0||h<=0)return;if((long)w*height>(long)h*width)w=h*width/height;else h=w*height/width;surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));}
    private void pointerButtons(int next){
        int[] masks={MotionEvent.BUTTON_PRIMARY,MotionEvent.BUTTON_SECONDARY,MotionEvent.BUTTON_TERTIARY};short[] codes={EvdevEncoder.BTN_LEFT,EvdevEncoder.BTN_RIGHT,EvdevEncoder.BTN_MIDDLE};
        int changed=PointerMotion.buttonChanges(buttons,next);
        if(input!=null)for(int i=0;i<3;i++)if((changed&masks[i])!=0)input.sendPointerButton(codes[i],(next&masks[i])!=0);
        buttons=next;
    }
    private void releasePointerButtons(){pointerButtons(0);}
    private void relativeMove(double dx,double dy){
        int viewW=Math.max(1,surface.getWidth()),viewH=Math.max(1,surface.getHeight());
        if(pointerViewWidth!=viewW||pointerViewHeight!=viewH||pointerGuestWidth!=width||pointerGuestHeight!=height){pointerMotion.reset();pointerViewWidth=viewW;pointerViewHeight=viewH;pointerGuestWidth=width;pointerGuestHeight=height;}
        int sensitivity=PointerMotion.sensitivityPercent(getSharedPreferences("settings",0).getInt("mouse_sensitivity",100));
        int[] move=pointerMotion.move(dx,dy,width,height,viewW,viewH,sensitivity);input.sendMouseMove(move[0],move[1]);
    }
    private void wheel(MotionEvent event){int[] scroll=pointerMotion.scroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL),event.getAxisValue(MotionEvent.AXIS_HSCROLL));input.sendScroll(scroll[0],scroll[1]);}
    private boolean capturedMouse(MotionEvent event){
        Object ticket=service==null?null:service.inputLeaseIdentity();
        if(!captureEligible()||!surface.hasPointerCapture()||!pointerCapture.accepts(ticket))return true;
        if(!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE))return false;
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){suspend();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE||event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE){
            float dx=event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),dy=event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            // Android's standard captured mouse encodes relative deltas in AXIS_X/Y; some drivers expose RELATIVE_X/Y.
            boolean explicit=false;android.view.InputDevice device=event.getDevice();
            if(device!=null)explicit=device.getMotionRange(MotionEvent.AXIS_RELATIVE_X,event.getSource())!=null||device.getMotionRange(MotionEvent.AXIS_RELATIVE_Y,event.getSource())!=null;
            if(!explicit&&dx==0&&dy==0){dx=event.getX();dy=event.getY();}
            relativeMove(dx,dy);
        }
        pointerButtons(event.getButtonState());if(event.getActionMasked()==MotionEvent.ACTION_SCROLL)wheel(event);
        return true;
    }
    private boolean mouse(MotionEvent e){
        if(input==null||service==null||!service.canInput()||!e.isFromSource(InputDevice.SOURCE_MOUSE))return false;
        if(surface.hasPointerCapture())return true; // captured listener is the only sender while actually held
        int mode=getSharedPreferences("settings",0).getInt("mode",0);
        if(mode==2)input.sendAbsMove(Math.round(Math.max(0,Math.min(1,e.getX()/Math.max(1,surface.getWidth()-1)))*EvdevEncoder.NORMALIZED_ABS_MAX),Math.round(Math.max(0,Math.min(1,e.getY()/Math.max(1,surface.getHeight()-1)))*EvdevEncoder.NORMALIZED_ABS_MAX));
        else if(mouseKnown)relativeMove(e.getX()-mouseX,e.getY()-mouseY);
        mouseX=e.getX();mouseY=e.getY();mouseKnown=true;
        pointerButtons(e.getButtonState());if(e.getActionMasked()==MotionEvent.ACTION_SCROLL)wheel(e);
        return true;
    }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent e){
        observeKeyTest(e,1);
        return super.dispatchKeyShortcutEvent(e);
    }
    public boolean dispatchKeyEvent(KeyEvent e){
        observeKeyTest(e,0);
        if(resumed&&hasWindowFocus()&&displayReady&&!ime&&service!=null&&(e.getAction()==KeyEvent.ACTION_DOWN||e.getAction()==KeyEvent.ACTION_UP)){
            ChordPolicy chord=ChordSettings.load(getSharedPreferences("settings",0));
            int scan=KeyCodeMapper.androidToEvdev(e.getKeyCode());
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN,pending=chordCycle.pending();
            boolean toggle=chordCycle.event(chord,scan,ChordSettings.mask(e),down,e.getRepeatCount()>0);
            if(toggle){service.toggleByHotkey();return true;}
            // Consume the candidate trigger and its release cycle; never forward the trigger to guest.
            if(pending||chordCycle.pending()||chord.matches(scan,ChordSettings.mask(e)))return true;
        }else chordCycle.reset();
        if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)startActivity(new Intent(this,SettingsActivity.class));return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_DOWN||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_UP)return super.dispatchKeyEvent(e);
        if(input!=null&&service!=null&&service.canInput()&&(e.getAction()==KeyEvent.ACTION_DOWN||e.getAction()==KeyEvent.ACTION_UP)){
            if(e.getRepeatCount()>0)return true;
            if(service.sendPhysicalKey(e))return true;
            if(input.sendKeyEvent(e.getKeyCode(),e.getAction()==KeyEvent.ACTION_DOWN))return true;
        }
        return super.dispatchKeyEvent(e);
    }
    private void applyFullscreen(){
        if(isFinishing()||isDestroyed()||area==null)return;
        View decor=getWindow().getDecorView();
        if(!decor.isAttachedToWindow())return;
        WindowInsetsController ctl=decor.getWindowInsetsController();
        if(ctl!=null){ctl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(getSharedPreferences("settings",0).getBoolean("immersive",true))ctl.hide(WindowInsets.Type.systemBars());else ctl.show(WindowInsets.Type.systemBars());}
    }
    protected void onResume(){super.onResume();resumed=true;manualGate=false;main.post(findSession);getWindow().getDecorView().post(this::applyFullscreen);refreshGate();}
    protected void onPause(){resumed=false;stopKeyTest();main.removeCallbacks(findSession);suspend();super.onPause();}
    public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)stopKeyTest();if(focus)applyFullscreen();refreshGate();}
    protected void onDestroy(){stopKeyTest();if(service!=null)service.removeDisplay(this);disconnected();super.onDestroy();}
}
