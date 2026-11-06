package io.github.collegeming.archlauncher;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

public class SettingsActivity extends Activity {
    private TextView status,chordLabel,recordNote;
    private boolean recording;
    private final ChordPolicy.Recorder recorder=new ChordPolicy.Recorder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){status.setText(SessionService.active==null?"未连接 · 键盘held: 0":SessionService.active.status());main.postDelayed(this,1000);}};
    public void onCreate(Bundle state){super.onCreate(state);
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(32,40,32,24);
        TextView title=new TextView(this);title.setText("Arch Linux · 连接设置");title.setTextSize(25);body.addView(title);
        status=new TextView(this);body.addView(status);
        TextView note=new TextView(this);note.setText("连接 DroidVM 管理的两条固定路线。点击连接后，已运行 VM 仅附加；停止态校验 profile、guard 与受控 launcher SHA256 后启动，再等待原生显示。未知 launcher 会拒绝并提示准备 runtime，不会替换文件或静默回退。不会冷启动 daemon、创建独立 VMM 或停止 VM。连接后自动全屏。\n键盘独占默认开启：仅 root EVIOCGRAB 成功的真实键盘计入 held，原始键直接转给 guest；未抓到的键盘继续普通转发。鼠标仍普通转发，未实现 pointer capture。触摸屏不抓取。\n失焦、IME、显示断连都会释放输入；手动释放仅能显式恢复。下面仅有一个“沉浸输入快捷键”，同键开启/释放输入，不启动 VM。可录入Ctrl/Alt/Shift/Meta与字母、F键等组合，左右修饰键归一。F12也可配置为同键切换，不再另设固定释放键。进入仅显示前台、Surface就绪、无IME时处理，不全局监听；完整松开组合后才进入，避免同周期重抓。触屏设置、通知、HOME/失焦仍可释放。Android保留的组合是否可达需实体键盘验证。");body.addView(note);
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        Spinner route=new Spinner(this);route.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SessionPolicy.NAMES));route.setSelection(SessionPolicy.route(prefs.getInt("route",0)));body.addView(route);
        Spinner mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Touch","Mouse","Tablet"}));mode.setSelection(Math.max(0,Math.min(2,prefs.getInt("mode",0))));body.addView(mode);
        CheckBox exclusive=new CheckBox(this);exclusive.setText("请求键盘独占（真实 held 状态回读）");exclusive.setChecked(prefs.getBoolean("exclusive",true));body.addView(exclusive);
        chordLabel=new TextView(this);chordLabel.setText("沉浸输入快捷键："+ChordSettings.label(ChordSettings.load(prefs)));body.addView(chordLabel);
        recordNote=new TextView(this);recordNote.setText("同一个组合开启 / 释放输入；设置无需连接即可保存。");body.addView(recordNote);
        Button record=new Button(this);record.setText("录入 / 重录快捷键");record.setOnClickListener(v->{recording=true;recorder.reset();recordNote.setText("请按下至少一个修饰键和一个触发键，然后松开全部按键；仅记录此组合，不记录其它文字。");});body.addView(record);
        Button cancelRecord=new Button(this);cancelRecord.setText("取消录入（保留原组合）");cancelRecord.setOnClickListener(v->{recording=false;recorder.reset();recordNote.setText("已取消，原组合不变。");});body.addView(cancelRecord);
        CheckBox immersive=new CheckBox(this);immersive.setText("连接后默认沉浸显示（保留状态 / 设置入口）");immersive.setChecked(prefs.getBoolean("immersive",true));body.addView(immersive);
        Runnable save=()->prefs.edit().putInt("route",route.getSelectedItemPosition()).putInt("mode",mode.getSelectedItemPosition()).putBoolean("exclusive",exclusive.isChecked()).putBoolean("immersive",immersive.isChecked()).apply();
        Button saveButton=new Button(this);saveButton.setText("保存设置（无需连接）");saveButton.setOnClickListener(v->{save.run();Toast.makeText(this,"已保存；root热键在下次进入输入时生效",Toast.LENGTH_SHORT).show();});body.addView(saveButton);
        Button connect=new Button(this);connect.setText("连接 / 显式恢复输入（全屏）");connect.setOnClickListener(v->{
            int selected=route.getSelectedItemPosition();save.run();
            startForegroundService(new Intent(this,SessionService.class).setAction(SessionService.CONNECT).putExtra("route",selected));
            startActivity(new Intent(this,DisplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });body.addView(connect);
        Button back=new Button(this);back.setText("返回显示（不恢复手动释放）");back.setOnClickListener(v->startActivity(new Intent(this,DisplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));body.addView(back);
        Button release=new Button(this);release.setText("释放输入");release.setOnClickListener(v->{if(SessionService.active!=null)startService(new Intent(this,SessionService.class).setAction(SessionService.RELEASE));});body.addView(release);
        Button disconnect=new Button(this);disconnect.setText("断开连接（不关闭 VM）");disconnect.setOnClickListener(v->{if(SessionService.active!=null)startService(new Intent(this,SessionService.class).setAction(SessionService.DISCONNECT));});body.addView(disconnect);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
    }
    public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(!recording)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode(),scan=KeyCodeMapper.androidToEvdev(key);
        if(key==android.view.KeyEvent.KEYCODE_HOME||key==android.view.KeyEvent.KEYCODE_BACK||key==android.view.KeyEvent.KEYCODE_POWER||key==android.view.KeyEvent.KEYCODE_VOLUME_UP||key==android.view.KeyEvent.KEYCODE_VOLUME_DOWN||scan<0){recorder.reset();recordNote.setText("该系统键不可用于快捷键，请重新录入或取消。");return true;}
        if(event.getAction()!=android.view.KeyEvent.ACTION_DOWN&&event.getAction()!=android.view.KeyEvent.ACTION_UP)return true;
        ChordPolicy chord=recorder.event(scan,event.getAction()==android.view.KeyEvent.ACTION_DOWN,event.getRepeatCount()>0);
        if(chord!=null){ChordSettings.save(getSharedPreferences("settings",0),chord);recording=false;chordLabel.setText("沉浸输入快捷键："+ChordSettings.label(chord));recordNote.setText("已保存。同一组合开启 / 释放输入；下次进入输入时同步到root。");}
        else if(recorder.error!=null)recordNote.setText(recorder.error);
        return true;
    }
    public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&recording){recording=false;recorder.reset();recordNote.setText("录入因失焦已取消，原组合不变。");}}
    protected void onResume(){super.onResume();main.post(poll);}
    protected void onPause(){recording=false;recorder.reset();main.removeCallbacks(poll);super.onPause();}
}
