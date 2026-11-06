"""Small regression guards for SDK-only packaging; no claim of GUI/device testing."""
from pathlib import Path
import sys
base = Path(sys.argv[1])
source = base / 'src/io/github/collegeming/archlauncher'
activity = (source/'DisplayActivity.java').read_text(encoding='utf-8')
service = (source/'SessionService.java').read_text(encoding='utf-8')


def check_capture_resume():
    # Static lifecycle guards; also runnable before prepare.py generates Android sources.
    request = service[service.index('    private void requestCaptureResume()'):service.index('    private void consumeCaptureResume()')]
    assert 'captureResumePending=true;consumeCaptureResume();' in request
    consume = service[service.index('    private void consumeCaptureResume()'):service.index('    private long localDrops;')]
    assert 'if(!captureResumePending||display==null||inputBlocked()||stopped)return;' in consume
    assert 'captureResumePending=false;display.allowPointerCapture();' in consume
    assert service.count('display.allowPointerCapture()') == 1
    connect = service[service.index('        if(CONNECT.equals(action))'):service.index('    private void startBroker()')]
    fresh, rest = connect.split('else if(requested!=route)', 1)
    rejected, existing = rest.split('\n            else {', 1)
    for accepted in (fresh, existing):
        assert 'manual=false;inputFault=false;requestCaptureResume();' in accepted
    assert 'requestCaptureResume()' not in rejected
    attach = service[service.index('    void attachDisplay('):service.index('    void removeDisplay(')]
    assert attach.index('display=activity;') < attach.index('consumeCaptureResume();') < attach.index('activity.sessionReady();')
    assert 'requestCaptureResume()' not in attach  # attach without CONNECT must not re-arm
    assert service.count('requestCaptureResume();') == 3  # fresh/existing CONNECT and explicit hotkey
    assert service.count('consumeCaptureResume();') == 2  # request and attach only
    assert service.count('manual=true;') == 4
    assert service.count('captureResumePending=false;manual=true;') == 4  # every manual release cancels pending intent
    disconnect = service[service.index('    private void disconnect()'):service.index('    public void onDestroy()')]
    assert 'captureResumePending=false;stopped=true;' in disconnect
    # Automatic resume/source readiness must preserve unexpected-loss suppression.
    assert activity.count('captureSuppressed=false;') == 1
    assert 'void allowPointerCapture(){captureSuppressed=false;}' in activity
    assert 'captureSuppressed=true;clearInput();' in activity
    debug = activity[activity.index('    private String captureDebugState()'):activity.index('    private void fit()')]
    for field in ('captureSuppressed', 'surface.isFocused()', 'surface.hasFocus()', 'surface.isAttachedToWindow()',
                  'captureEligible()', 'pointerCapture.debugState(ticket)', 'service.canInput()'):
        assert field in debug
    assert 'captureDebugState()' in activity[activity.index('    void updateStatus()'):activity.index('    private String captureDebugState()')]
    machine = (source/'PointerCaptureState.java').read_text(encoding='utf-8')
    debug_machine = machine[machine.index('    String debugState('):]
    for field in ('leasePresent=', 'ticketPresent=', 'leaseMatch=', 'eligible=', 'requested=', 'actual=', 'denied=', 'releasing='):
        assert field in debug_machine
    assert '.toString(' not in debug_machine and 'hashCode(' not in debug_machine
    assert 'requestPointerCapture(' not in debug and 'releasePointerCapture(' not in debug
    print('Capture resume contracts: explicit permission boundaries and read-only boolean capture diagnostics passed')


def check_surface_focus():
    ready = activity[activity.index('    void sessionReady()'):activity.index('    void refreshGate()')]
    assert ready.index('surfaceFocusPending=true;') < ready.index('source=new NativeSurfaceSource')
    callback = ready[ready.index('public void onStateChanged('):]
    assert callback.index('if(!sourceLifetime.current(sourceId)||service!=captured)return;') < callback.index('surfaceFocusPending=true;')
    assert 'if(displayReady&&!ready)surfaceFocusPending=true;' in callback
    assert activity.count('surfaceFocusPending=true;') == 2  # only new source and connected -> not-ready
    refresh = activity[activity.index('    void refreshGate()'):activity.index('    private void restoreSurfaceFocus()')]
    assert refresh.index('input=service.newForwarder();') < refresh.index('restoreSurfaceFocus();') < refresh.index('updatePointerCapture();')
    restore = activity[activity.index('    private void restoreSurfaceFocus()'):activity.index('    void allowPointerCapture()')]
    for guard in ('!surfaceFocusPending', 'captureSuppressed', 'isFinishing()', 'isDestroyed()',
                  'SessionPolicy.accepts(resumed,hasWindowFocus(),displayReady,ime,service.inputBlocked())',
                  '!service.canInput()', 'input==null', 'surface==null', '!surface.isAttachedToWindow()'):
        assert guard in restore
    assert restore.index('surfaceFocusPending=false;') < restore.index('surface.requestFocus();')
    assert 'if(!surface.isFocused())surface.requestFocus();' in restore
    assert 'post(' not in restore and 'postDelayed(' not in restore
    assert activity.count('restoreSurfaceFocus();') == 1
    assert activity.count('surface.requestFocus();') == 2  # initial create and one-shot repair only
    assert 'surface.setOnFocusChangeListener((v,focus)->{if(!focus)suspend();refreshGate();});' in activity
    assert 'private void suspend(){clearInput();if(service!=null)service.gate(false);}' in activity
    gate = service[service.index('    void gate(boolean allowed)'):service.index('    android.os.IBinder displayBinder()')]
    assert 'lease.close();' in gate and 'display.clearInput();' in gate
    assert 'if(connected&&!canInput()&&!lease.pending()&&broker!=null)prepare(true);' in gate
    # A synchronous focus callback may re-enter refreshGate, but cannot re-arm the focus attempt.
    assert 'surfaceFocusPending=true;' not in refresh and 'surfaceFocusPending=true;' not in restore
    poll = activity[activity.index('    private final Runnable findSession='):activity.index('    public void onCreate(')]
    assert 'requestFocus' not in poll and 'restoreSurfaceFocus' not in poll
    disconnected = activity[activity.index('    void disconnected()'):activity.index('    void updateStatus()')]
    assert 'sourceLifetime.close();surfaceFocusPending=false;' in disconnected
    assert 'focusPending=' in activity
    print('Surface focus contracts: source/recreation-only one-shot repair, foreground/input/suppression guards and no polling retry passed')


check_capture_resume()
check_surface_focus()
if '--capture-resume-only' in sys.argv[2:]:
    sys.exit(0)
create = activity[activity.index('public void onCreate('):activity.index('    void sessionReady()')]
assert 'getInsetsController()' not in create
assert 'getWindowInsetsController()' not in create
assert create.index('setContentView(area)') < create.index('post(this::applyFullscreen)')
fullscreen = activity[activity.index('    private void applyFullscreen()'):activity.index('    protected void onResume()')]
assert 'if(!decor.isAttachedToWindow())return;' in fullscreen
assert 'if(ctl!=null)' in fullscreen
assert 'post(' not in fullscreen and 'postDelayed' not in fullscreen
broker = (source/'InputBroker.java').read_text(encoding='utf-8')
assert 'if(!gate.current(expected))return;' in broker
assert 'synchronized' not in broker
assert 'io.cleanup(session,reason,retire,new MonotonicDeadline(RELEASE_MS))' in broker
physical = (source/'PhysicalGrab.java').read_text(encoding='utf-8')
assert 'lease.arm(session,' in physical and 'lease.revoke(session,' in physical
assert 'physical.publish(captured)' in broker
assert 'private void queue(' not in broker
assert 'if(!sourceLifetime.current(sourceId)||service!=captured)return;' in activity
assert 'int state=gate.status(session);if(state!=0)return state;' in broker
assert 'for(int slot:touchSlots)' in broker
assert 'slot<32' not in broker
assert 'status.setVisibility(View.GONE)' in activity
assert 'setOnCapturedPointerListener' in activity
assert 'AXIS_RELATIVE_X' in activity and 'AXIS_RELATIVE_Y' in activity
assert 'surface.hasPointerCapture()' in activity
assert 'void clearInput(){chordCycle.reset();stopPointerCapture();' in activity
assert 'if(releaseFailed)return ManagedStart.RELEASE_UNCONFIRMED;' in broker
assert '.getMethod("checkService",String.class)' in broker
assert not any(cmd in broker for cmd in ['"vm_stop"','"vm_kill"','"vm_create"'])
assert '"su","-M","-c"' in service
assert 'if(!lease.current(ticket))' in service
assert 'cleanupQueued.compareAndSet(false,true))releaseRemote(b,t,g);return false;' in service
assert 'ownerPipe.close()' in service
assert 'CLASSPATH=' in service
provider = (source/'DisplayProvider.java').read_text(encoding='utf-8')
ready = provider[provider.index('    private void onBinderReady('):provider.index('    private void startConfigPoll(')]
assert 'onConnected.accept(true)' not in ready
mark = provider[provider.index('    private void markSent()'):provider.index('    private final class Callback')]
assert 'onConnected.accept(true)' in mark
shutdown = provider[provider.index('    void shutdown()'):]
assert 'removeSurface(false)' in shutdown and 'removeSurface(true)' in shutdown
assert 'if (executor.isShutdown()) return;' in ready
print('Source contracts: Activity Insets ordering/null-safety; real Surface readiness/teardown; root session generation/release guards passed')
