package io.github.collegeming.archlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** No daemon bootstrap, runtime writes, VM config changes, or credential logging. */
public final class MainActivity extends Activity {
    private static final String VM = "d570d1fd-afcf-407d-a82c-fccadc203cf1";
    private static final String BASE = "/data/data/cn.classfun.droidvm/";
    private TextView status;
    private Button open;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Arch Linux · 2272 × 1514");
        title.setTextSize(24);
        layout.addView(title);
        TextView note = new TextView(this);
        note.setText("连接现有 DroidVM 原生显示。当前版本打开后请点击原生全屏按钮。\n需要授予本应用 root；仅校验 runtime 并连接健康 daemon，不恢复文件、不冷启动 daemon。runtime 更新后需安装更新了校验清单的启动器。");
        layout.addView(note);
        status = new TextView(this);
        layout.addView(status);
        open = new Button(this);
        open.setText("打开 Arch Linux");
        open.setOnClickListener(v -> launch());
        layout.addView(open);
        setContentView(layout);
        launch();
    }

    private void launch() {
        open.setEnabled(false);
        status.setText("正在检查 root、runtime 和 daemon…");
        new Thread(() -> {
            try {
                if (!root("id -u").trim().equals("0"))
                    throw new Exception("未获得 root 授权。");
                JSONObject manifest;
                try (InputStream in = getAssets().open("runtime-manifest.json")) {
                    manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (!VM.equals(manifest.getString("vm_id")))
                    throw new Exception("启动器 VM 清单不一致。");
                JSONObject files = manifest.getJSONObject("files");
                StringBuilder check = new StringBuilder("set -e\n");
                for (var it = files.keys(); it.hasNext();) {
                    String path = it.next();
                    String hash = files.getString(path);
                    if (!path.matches("/[a-zA-Z0-9_./-]+") || !hash.matches("[a-f0-9]{64}"))
                        throw new Exception("runtime 校验清单无效。");
                    check.append("test \"$(sha256sum '").append(path)
                        .append("' | cut -d ' ' -f 1)\" = '").append(hash).append("'\n");
                }
                check.append("printf verified");
                if (!root(check.toString()).equals("verified"))
                    throw new Exception("runtime 校验未通过，请先完成受控部署。");
                // Read credentials only into memory; never include them in a command, log or UI.
                String config = root("cat " + BASE + "run/droidvmd-port.txt; printf '\\n'; cat "
                    + BASE + "run/droidvmd-token.txt").trim();
                String[] values = config.split("\\s+", 2);
                if (values.length != 2) throw new Exception("daemon 未准备好，请先完成受控启动。");
                int port = Integer.parseInt(values[0]);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 4000);
                    socket.setSoTimeout(5000);
                    request(socket, "auth", new JSONObject().put("token", values[1]));
                    request(socket, "ping", new JSONObject());
                    JSONObject target = request(socket, "vm_get", new JSONObject().put("vm_id", VM))
                        .getJSONObject("data");
                    JSONObject screen = target.getJSONObject("screens").getJSONObject("gpu-0");
                    if (!VM.equals(target.optString("id"))
                        || !"arch-niri-mali".equals(target.optString("name"))
                        || !"CROSVM".equals(target.optString("backend"))
                        || !"GPU_GFXSTREAM".equals(target.optString("gpu_backend"))
                        || !"native".equals(screen.optString("exporter"))
                        || !screen.optBoolean("enabled") || !screen.optBoolean("input_enabled")
                        || screen.optInt("width") != 2272 || screen.optInt("height") != 1514
                        || !usesArchDisk(target)) throw new Exception("unexpected VM configuration");
                    String state = request(socket, "vm_status", new JSONObject().put("vm_id", VM))
                        .getString("state");
                    if (!"running".equals(state) && !"stopped".equals(state))
                        throw new Exception("VM transition in progress");
                    // Read-only preflight, not a lock against another client starting a VM later.
                    // Treat every non-stopped peer as potentially owning the shared disk.
                    var peers = request(socket, "vm_list", new JSONObject()).getJSONArray("data");
                    for (int i = 0; i < peers.length(); i++) {
                        String peer = peers.getJSONObject(i).getString("id");
                        if (VM.equals(peer)) continue;
                        String peerState = request(socket, "vm_status", new JSONObject().put("vm_id", peer))
                            .getString("state");
                        if ("stopped".equals(peerState)) continue;
                        JSONObject configPeer = request(socket, "vm_get", new JSONObject().put("vm_id", peer))
                            .getJSONObject("data");
                        if (usesArchDisk(configPeer)) throw new Exception("shared disk busy");
                    }
                }
                String activityResult = root("am start -W --activity-single-top --activity-reorder-to-front -n cn.classfun.droidvm/.ui.vm.display.nativedisplay.display.VMNativeDisplayActivity"
                    + " --es vm_id " + VM + " --es vm_name arch-niri-mali --es screen gpu-0"
                    + " --ez input_enabled true --el display_width 2272 --el display_height 1514");
                String amResult = activityResult.toLowerCase(java.util.Locale.ROOT);
                if (amResult.contains("error") || amResult.contains("exception")
                    || !amResult.matches("(?s).*status:\\s*ok\\b.*"))
                    throw new Exception("Activity launch failed");
                runOnUiThread(() -> {
                    status.setText("已打开原生显示，请点击其全屏按钮。Ctrl+Alt+F12 退出独占需要 daemon 已配置逃生键。");
                    open.setEnabled(true);
                });
            } catch (Exception error) {
                // Do not surface command stdout/stderr, server messages or exception text: they
                // can include credentials or app-private state. Fail closed with fixed guidance.
                runOnUiThread(() -> {
                    status.setText("未启动：请检查 root 授权、匹配的 runtime 和已运行的健康 daemon。启动器不会恢复文件或冷启动 stock runtime。");
                    open.setEnabled(true);
                });
            }
        }, "arch-launch-check").start();
    }

    private static boolean usesArchDisk(JSONObject config) throws Exception {
        var disks = config.getJSONArray("disks");
        for (int i = 0; i < disks.length(); i++) {
            String path = disks.getJSONObject(i).getString("path");
            if (path.equals(BASE + "files/arch.img")
                || path.equals("/data/user/0/cn.classfun.droidvm/files/arch.img")) return true;
        }
        return false;
    }

    private static String root(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Exception("root timeout");
            }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0 || output.length > 65536)
                throw new Exception("root check failed");
            return new String(output, StandardCharsets.UTF_8).trim();
        } finally { process.destroy(); }
    }

    private static JSONObject request(Socket socket, String command, JSONObject body) throws Exception {
        String id = UUID.randomUUID().toString();
        body.put("type", "request").put("request_id", id).put("command", command);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.length).array());
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
        DataInputStream input = new DataInputStream(socket.getInputStream());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int length = Integer.reverseBytes(input.readInt());
            if (length < 1 || length > 8 * 1024 * 1024) throw new Exception("invalid frame");
            byte[] response = new byte[length];
            input.readFully(response);
            int end = length;
            while (end > 0 && response[end - 1] == 0) end--;
            JSONObject result = new JSONObject(new String(response, 0, end, StandardCharsets.UTF_8));
            if (!id.equals(result.optString("request_id"))) continue;
            if (!result.optBoolean("success")) throw new Exception("daemon check failed");
            return result;
        }
        throw new Exception("daemon deadline");
    }
}
