"""Small regression guards for SDK-only packaging; no claim of GUI/device testing."""
from pathlib import Path
import sys
base = Path(sys.argv[1])
source = base / 'src/io/github/collegeming/archlauncher'
activity = (source/'DisplayActivity.java').read_text(encoding='utf-8')
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
assert 'MonotonicDeadline budget=new MonotonicDeadline(RELEASE_MS)' in broker
assert 'if(!sourceLifetime.current(sourceId)||service!=captured)return;' in activity
assert 'int state=gate.status(session);if(state!=0)return state;' in broker
assert 'for(int slot:touchSlots)' in broker
assert 'slot<32' not in broker
assert 'status.setVisibility(View.GONE)' in activity
assert 'if(releaseFailed)return ManagedStart.RELEASE_UNCONFIRMED;' in broker
assert '.getMethod("checkService",String.class)' in broker
assert not any(cmd in broker for cmd in ['"vm_stop"','"vm_kill"','"vm_create"'])
service = (source/'SessionService.java').read_text(encoding='utf-8')
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
