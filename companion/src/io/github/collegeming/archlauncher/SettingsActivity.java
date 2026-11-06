package io.github.collegeming.archlauncher;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

public class SettingsActivity extends Activity {
    private TextView status;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){status.setText(SessionService.active==null?"未连接 · 键盘held: 0":SessionService.active.status());main.postDelayed(this,1000);}};
    public void onCreate(Bundle state){super.onCreate(state);
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(32,40,32,24);
        TextView title=new TextView(this);title.setText("Arch Linux · 连接设置");title.setTextSize(25);body.addView(title);
        status=new TextView(this);body.addView(status);
        TextView note=new TextView(this);note.setText("连接 DroidVM 管理的两条固定路线。点击连接后，已运行 VM 仅附加；停止态校验 profile、guard 与受控 launcher SHA256 后启动，再等待原生显示。未知 launcher 会拒绝并提示准备 runtime，不会替换文件或静默回退。不会冷启动 daemon、创建独立 VMM 或停止 VM。连接后自动全屏。\n键盘独占默认开启：仅 root EVIOCGRAB 成功的真实键盘计入 held，原始键直接转给 guest；未抓到的键盘继续普通转发。鼠标仍普通转发，未实现 pointer capture。触摸屏不抓取。\n失焦、IME、显示断连都会释放输入；手动释放仅能显式恢复。固定 Ctrl+Alt+F12 永远紧急释放，不可取消，也不会切换为进入。下面的进入/释放预设只控制输入，不启动 VM；仅在显示前台、Surface就绪、无IME时处理，不全局监听。将两项设为同一个非F12预设时，可在显示前台切换。Android保留的组合是否可达需实体键盘验证。");body.addView(note);
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        Spinner route=new Spinner(this);route.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SessionPolicy.NAMES));route.setSelection(SessionPolicy.route(prefs.getInt("route",0)));body.addView(route);
        Spinner mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Touch","Mouse","Tablet"}));mode.setSelection(Math.max(0,Math.min(2,prefs.getInt("mode",0))));body.addView(mode);
        CheckBox exclusive=new CheckBox(this);exclusive.setText("请求键盘独占（真实 held 状态回读）");exclusive.setChecked(prefs.getBoolean("exclusive",true));body.addView(exclusive);
        TextView enterLabel=new TextView(this);enterLabel.setText("进入 / 恢复输入快捷键（F12选择仍仅释放）");body.addView(enterLabel);
        Spinner enterHotkey=new Spinner(this);enterHotkey.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,HotkeyPolicy.LABELS));enterHotkey.setSelection(HotkeyPolicy.preset(prefs.getInt("enter_hotkey",1),1));body.addView(enterHotkey);
        TextView releaseLabel=new TextView(this);releaseLabel.setText("释放输入快捷键（额外保留 Ctrl+Alt+F12）");body.addView(releaseLabel);
        Spinner releaseHotkey=new Spinner(this);releaseHotkey.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,HotkeyPolicy.LABELS));releaseHotkey.setSelection(HotkeyPolicy.preset(prefs.getInt("release_hotkey",2),2));body.addView(releaseHotkey);
        CheckBox immersive=new CheckBox(this);immersive.setText("连接后默认沉浸显示（保留状态 / 设置入口）");immersive.setChecked(prefs.getBoolean("immersive",true));body.addView(immersive);
        Runnable save=()->prefs.edit().putInt("route",route.getSelectedItemPosition()).putInt("mode",mode.getSelectedItemPosition()).putBoolean("exclusive",exclusive.isChecked()).putInt("enter_hotkey",enterHotkey.getSelectedItemPosition()).putInt("release_hotkey",releaseHotkey.getSelectedItemPosition()).putBoolean("immersive",immersive.isChecked()).apply();
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
    protected void onResume(){super.onResume();main.post(poll);}
    protected void onPause(){main.removeCallbacks(poll);super.onPause();}
}
