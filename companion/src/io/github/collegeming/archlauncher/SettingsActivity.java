package io.github.collegeming.archlauncher;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

public class SettingsActivity extends Activity {
    private TextView status;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){status.setText(SessionService.active==null?"未连接 · grab未完成，实际held: 否":SessionService.active.status());main.postDelayed(this,1000);}};
    public void onCreate(Bundle state){super.onCreate(state);
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(32,40,32,24);
        TextView title=new TextView(this);title.setText("Arch Linux · 连接设置");title.setTextSize(25);body.addView(title);
        status=new TextView(this);body.addView(status);
        TextView note=new TextView(this);note.setText("连接 DroidVM 管理的两条固定路线。点击连接后，已运行 VM 仅附加；停止态校验 profile、guard 与受控 launcher SHA256 后启动，再等待原生显示。未知 launcher 会拒绝并提示准备 runtime，不会替换文件或静默回退。不会冷启动 daemon、创建独立 VMM 或停止 VM。连接后自动全屏。\n当前仅普通输入；exclusive 默认开启但系统级 grab 尚未完成，实际 held 始终为否。触摸屏不抓取。\n失焦、IME、显示断连都会释放输入；手动释放仅能显式恢复。Ctrl+Alt+F12 释放，支持左右修饰键。");body.addView(note);
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        Spinner route=new Spinner(this);route.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SessionPolicy.NAMES));route.setSelection(SessionPolicy.route(prefs.getInt("route",0)));body.addView(route);
        Spinner mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Touch","Mouse","Tablet"}));mode.setSelection(Math.max(0,Math.min(2,prefs.getInt("mode",0))));body.addView(mode);
        CheckBox exclusive=new CheckBox(this);exclusive.setText("请求 exclusive（首版尚未实现 grab）");exclusive.setChecked(prefs.getBoolean("exclusive",true));body.addView(exclusive);
        Button connect=new Button(this);connect.setText("连接 / 显式恢复输入（全屏）");connect.setOnClickListener(v->{
            int selected=route.getSelectedItemPosition();prefs.edit().putInt("route",selected).putInt("mode",mode.getSelectedItemPosition()).putBoolean("exclusive",exclusive.isChecked()).apply();
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
