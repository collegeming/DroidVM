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
    print('Capture resume contracts: CONNECT-before/after-attach, one-shot consumption, ordinary return, route rejection and manual release guards passed')


check_capture_resume()
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
