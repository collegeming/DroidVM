"""Reuse fixed DroidVM sources; never fetch mutable dependencies."""
from pathlib import Path
import sys
import shutil
source, target = map(Path, sys.argv[1:])
java = source / 'app/src/main/java/cn/classfun/droidvm'
out = target / 'src/io/github/collegeming/archlauncher'
files = ['ui/vm/display/nativedisplay/input/' + n + '.java' for n in ['EvdevEncoder','KeyCodeMapper','InputForwarder']]
files += ['ui/vm/display/nativedisplay/display/' + n + '.java' for n in ['DisplayProvider','CursorPositionStream','NativeSurfaceSource']]
files += ['ui/vm/display/base/DisplaySource.java']
for name in files:
    text = (java / name).read_text(encoding='utf-8')
    for p in ['ui.vm.display.nativedisplay.input','ui.vm.display.nativedisplay.display','ui.vm.display.base']:
        text = text.replace('cn.classfun.droidvm.' + p, 'io.github.collegeming.archlauncher')
    text = text.replace('cn.classfun.droidvm.lib.store.vm.NativeDisplay','io.github.collegeming.archlauncher.Channels')
    text = text.replace('cn.classfun.droidvm.lib.utils.StringUtils','io.github.collegeming.archlauncher.Strings')
    text = '\n'.join(l for l in text.splitlines() if not l.startswith('import androidx.annotation.'))
    for a in ['@NonNull ', '@Nullable ', '@NonNull', '@Nullable']: text = text.replace(a, '')
    # Avoid logging typed keys; the input sink returns actual delivery status to the session.
    text = text.replace('Log.d(TAG, fmt("key scan=%d down=%b delivered=%b", scanCode, pressed, ok));','')
    if name.endswith('DisplayProvider.java'):
        text = text.replace('private void onBinderReady(IBinder binder) {','private void onBinderReady(IBinder binder) {\n        if (executor.isShutdown()) return;')
        # CONNECTED must mean the actual main Surface was delivered, not just binder discovery.
        text = text.replace('        onConnected.accept(true);\n        applyPendingSurface();','        applyPendingSurface();')
        text = text.replace('    private void markSent() {\n        needsSend = false;','    private void markSent() {\n        needsSend = false;\n        onConnected.accept(true);')
        text = text.replace('            needsSend = false;\n            var svc = displayService;','            needsSend = false;\n            onConnected.accept(false);\n            var svc = displayService;')
        text = text.replace('    void shutdown() {\n        if (displayService != null) {','    void shutdown() {\n        if (displayService != null) {\n            try { displayService.removeSurface(false); } catch (Exception ignored) {}\n            try { displayService.removeSurface(true); } catch (Exception ignored) {}')
    (out / Path(name).name).write_text(text + '\n', encoding='utf-8')
for name in ['DisplayConfig.aidl','ICrosvmAndroidDisplayService.aidl']:
    dest = target / 'src/android/crosvm' / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'app/src/main/aidl/android/crosvm' / name, dest)
for name in ['LICENSE.txt','ADDITIONAL-PERMISSIONS']: shutil.copyfile(source/name,target/name)
(out/'Channels.java').write_text('package io.github.collegeming.archlauncher; final class Channels { static final int MULTITOUCH=0, KEYBOARD=1, MOUSE=2, TABLET=3; }\n')
(out/'InputMode.java').write_text('package io.github.collegeming.archlauncher; public enum InputMode { TOUCH, MOUSE, TABLET }\n')
(out/'Strings.java').write_text('package io.github.collegeming.archlauncher; final class Strings { static String fmt(String s,Object... a) { return String.format(java.util.Locale.ROOT,s,a); } }\n')
