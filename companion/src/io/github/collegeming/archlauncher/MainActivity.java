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
    private boolean launching;

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launch();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Arch Linux \u00b7 2272 \u00d7 1514");
        title.setTextSize(24);
        layout.addView(title);
        TextView note = new TextView(this);
        note.setText("\u8fde\u63a5\u73b0\u6709 DroidVM \u539f\u751f\u663e\u793a\u3002\u5f53\u524d\u7248\u672c\u6253\u5f00\u540e\u8bf7\u70b9\u51fb\u539f\u751f\u5168\u5c4f\u6309\u94ae\u3002\n\u9700\u8981\u6388\u4e88\u672c\u5e94\u7528 root\uff1b\u4ec5\u6821\u9a8c runtime \u5e76\u8fde\u63a5\u5065\u5eb7 daemon\uff0c\u4e0d\u6062\u590d\u6587\u4ef6\u3001\u4e0d\u51b7\u542f\u52a8 daemon\u3002runtime \u66f4\u65b0\u540e\u9700\u5b89\u88c5\u66f4\u65b0\u4e86\u6821\u9a8c\u6e05\u5355\u7684\u542f\u52a8\u5668\u3002");
        layout.addView(note);
        status = new TextView(this);
        layout.addView(status);
        open = new Button(this);
        open.setText("\u6253\u5f00 Arch Linux");
        open.setOnClickListener(v -> launch());
        layout.addView(open);
        setContentView(layout);
        launch();
    }

    private void launch() {
        if (launching) return;
        launching = true;
        open.setEnabled(false);
        status.setText("\u6b63\u5728\u68c0\u67e5 root\u3001runtime \u548c daemon\u2026");
        new Thread(() -> {
            // Only these source-defined labels reach the UI; never an exception/server string.
            String stage = "ROOT";
            try {
                if (!root("id -u").trim().equals("0"))
                    throw new Exception("\u672a\u83b7\u5f97 root \u6388\u6743\u3002");
                stage = "HASH_MANIFEST";
                JSONObject manifest;
                try (InputStream in = getAssets().open("runtime-manifest.json")) {
                    manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (!VM.equals(manifest.getString("vm_id")))
                    throw new Exception("\u542f\u52a8\u5668 VM \u6e05\u5355\u4e0d\u4e00\u81f4\u3002");
                JSONObject files = manifest.getJSONObject("files");
                // Stable numbers, independent of JSONObject iteration order. Never change the
                // expected hashes to accommodate an environment/namespace failure.
                String[] paths = {
                    "/data/data/cn.classfun.droidvm/usr/bin/crosvm",
                    "/data/data/cn.classfun.droidvm/usr/bin/crosvm.orig",
                    "/data/data/cn.classfun.droidvm/usr/bin/crosvm.dbg-gfxstream-wrapper",
                    "/data/local/tmp/droidvm-native-pair-v1/lib/libgfxstream_backend.so"
                };
                if (files.length() != paths.length) throw new Exception("invalid manifest");
                for (String path : paths)
                    if (!files.getString(path).matches("[a-f0-9]{64}"))
                        throw new Exception("invalid manifest");
                stage = "HASH_TOOL";
                if (!root("if [ -x /system/bin/sha256sum ]; then printf READY; fi").equals("READY"))
                    throw new Exception("hash tool unavailable");
                for (int i = 0; i < paths.length; i++) {
                    String path = paths[i];
                    stage = String.format(java.util.Locale.ROOT, "HASH_EXEC_%d", i + 1);
                    // Shell builtins plus one absolute executable: no PATH, cut or pipeline.
                    // MISSING/READ are emitted only by these branches, not copied from stderr.
                    String digest = root(String.format(java.util.Locale.ROOT,
                        "if [ ! -f '%1$s' ]; then printf MISSING; "
                        + "elif [ ! -r '%1$s' ]; then printf READ; "
                        + "else value=$(/system/bin/sha256sum '%1$s'); rc=$?; "
                        + "if [ \"$rc\" -ne 0 ]; then printf READ; else printf '%%s' \"$value\"; fi; fi",
                        path));
                    if (digest.equals("MISSING") || digest.equals("READ")) {
                        stage = String.format(java.util.Locale.ROOT, "HASH_%s_%d", digest, i + 1);
                        throw new Exception("runtime file unavailable");
                    }
                    stage = String.format(java.util.Locale.ROOT, "HASH_OUTPUT_%d", i + 1);
                    var match = java.util.regex.Pattern.compile(
                        String.format(java.util.Locale.ROOT, "^([a-fA-F0-9]{64})[ \\t]+\\*?%s$",
                            java.util.regex.Pattern.quote(path))).matcher(digest);
                    if (!match.matches()) throw new Exception("unexpected hash output");
                    stage = String.format(java.util.Locale.ROOT, "HASH_MISMATCH_%d", i + 1);
                    if (!files.getString(path).equalsIgnoreCase(match.group(1)))
                        throw new Exception("runtime hash mismatch");
                }
                // Read credentials only into memory; never include them in a command, log or UI.
                stage = "AUTH_CONFIG";
                String config = root(String.format(java.util.Locale.ROOT,
                    "cat %srun/droidvmd-port.txt; printf '\\n'; cat %srun/droidvmd-token.txt",
                    BASE, BASE)).trim();
                String[] values = config.split("\\s+", 2);
                if (values.length != 2) throw new Exception("daemon \u672a\u51c6\u5907\u597d\uff0c\u8bf7\u5148\u5b8c\u6210\u53d7\u63a7\u542f\u52a8\u3002");
                int port = Integer.parseInt(values[0]);
                stage = "CONNECT";
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 4000);
                    socket.setSoTimeout(5000);
                    stage = "AUTH";
                    request(socket, "auth", new JSONObject().put("token", values[1]));
                    stage = "PING";
                    request(socket, "ping", new JSONObject());
                    stage = "VM_GET";
                    JSONObject target = request(socket, "vm_get", new JSONObject().put("vm_id", VM))
                        .getJSONObject("data");
                    stage = "VM_CONFIG";
                    JSONObject screen = target.getJSONObject("screens").getJSONObject("gpu-0");
                    if (!VM.equals(target.optString("id"))
                        || !"arch-niri-mali".equals(target.optString("name"))
                        || !"CROSVM".equals(target.optString("backend"))
                        || !"GPU_GFXSTREAM".equals(target.optString("gpu_backend"))
                        || !"native".equals(screen.optString("exporter"))
                        || !screen.optBoolean("enabled") || !screen.optBoolean("input_enabled")
                        || screen.optInt("width") != 2272 || screen.optInt("height") != 1514
                        || !usesArchDisk(target)) throw new Exception("unexpected VM configuration");
                    stage = "VM_STATUS";
                    String state = request(socket, "vm_status", new JSONObject().put("vm_id", VM))
                        .getString("state");
                    if (!"running".equals(state) && !"stopped".equals(state))
                        throw new Exception("VM transition in progress");
                    // Read-only preflight, not a lock against another client starting a VM later.
                    // Treat every non-stopped peer as potentially owning the shared disk.
                    stage = "VM_LIST";
                    var peers = request(socket, "vm_list", new JSONObject()).getJSONArray("data");
                    for (int i = 0; i < peers.length(); i++) {
                        stage = "VM_LIST";
                        String peer = peers.getJSONObject(i).getString("id");
                        if (VM.equals(peer)) continue;
                        stage = "VM_PEER_STATUS";
                        String peerState = request(socket, "vm_status", new JSONObject().put("vm_id", peer))
                            .getString("state");
                        if ("stopped".equals(peerState)) continue;
                        stage = "VM_PEER_CONFIG";
                        JSONObject configPeer = request(socket, "vm_get", new JSONObject().put("vm_id", peer))
                            .getJSONObject("data");
                        stage = "VM_DISK";
                        if (usesArchDisk(configPeer)) throw new Exception("shared disk busy");
                    }
                }
                stage = "ACTIVITY";
                String activityResult = root(String.format(java.util.Locale.ROOT,
                    "am start -W --activity-single-top --activity-reorder-to-front -n cn.classfun.droidvm/.ui.vm.display.nativedisplay.display.VMNativeDisplayActivity --es vm_id %s --es vm_name arch-niri-mali --es screen gpu-0 --ez input_enabled true --el display_width 2272 --el display_height 1514", VM));
                String amResult = activityResult.toLowerCase(java.util.Locale.ROOT);
                if (amResult.contains("error") || amResult.contains("exception")
                    || !amResult.matches("(?s).*status:\\s*ok\\b.*"))
                    throw new Exception("Activity launch failed");
                runOnUiThread(() -> {
                    status.setText("\u5df2\u6253\u5f00\u539f\u751f\u663e\u793a\uff0c\u8bf7\u70b9\u51fb\u5176\u5168\u5c4f\u6309\u94ae\u3002Ctrl+Alt+F12 \u9000\u51fa\u72ec\u5360\u9700\u8981 daemon \u5df2\u914d\u7f6e\u9003\u751f\u952e\u3002");
                    launching = false;
                    finish();
                });
            } catch (Exception error) {
                // Do not surface command stdout/stderr, server messages or exception text: they
                // can include credentials or app-private state. Fail closed with a fixed stage.
                final String failedStage = stage;
                runOnUiThread(() -> {
                    status.setText(String.format(java.util.Locale.ROOT,
                        "\u672a\u5b8c\u6210 [E_%s]\u3002\u8bf7\u5c06\u6b64\u9636\u6bb5\u7801\u63d0\u4f9b\u7ed9\u6392\u67e5\u4eba\u5458\u3002\u4e0d\u4f1a\u6062\u590d runtime \u6216\u51b7\u542f\u52a8 daemon\u3002", failedStage));
                    launching = false;
                    open.setEnabled(true);
                });
            }
        }, "arch-launch-check").start();
    }

    private static boolean usesArchDisk(JSONObject config) throws Exception {
        var disks = config.getJSONArray("disks");
        for (int i = 0; i < disks.length(); i++) {
            String path = disks.getJSONObject(i).getString("path");
            if (path.equals("/data/data/cn.classfun.droidvm/files/arch.img")
                || path.equals("/data/user/0/cn.classfun.droidvm/files/arch.img")) return true;
        }
        return false;
    }

    private static String root(String command) throws Exception {
        // KernelSU app data isolation hides DroidVM's private directory in this app's
        // inherited mount namespace. Select the existing host namespace; do not alter mounts.
        // Keep su warnings out of data-bearing stdout (hashes, port/token, am result).
        Process process = new ProcessBuilder("su", "-M", "-c", command)
            .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null"))).start();
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
