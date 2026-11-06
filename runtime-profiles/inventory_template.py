"""Print expected enrollment shape, NOT evidence. Replace with actual observations.
Booleans default false deliberately. Expected hashes are not fresh measurements.
"""
import json
from pathlib import Path
m = json.loads((Path(__file__).resolve().parent / 'manifest.json').read_text(encoding='utf-8'))
out = {'launcher_sha256': m['known_replaced_launchers'][0], 'dispatcher_sha256': 'REPLACE_WITH_DOWNLOADED_BASELINE_SHA256', 'files': {}, 'disks': {}, 'vms': [], 'dependencies_reviewed': False, 'proc_fd_scan_verified': False, 'private_lock_lifetime_verified': False, 'stock_guard': {'stock_sha256': m['stock']['sha256'], 'rejected_argument': '--droidvm-profile-guard', 'no_vm_started': False}}
for p in m['profiles']:
    for f in [p['runtime'], p['kernel'], p['initrd'], *p['libraries'], m['stock']]:
        out['files'][f['path']] = f['sha256']
    out['disks'][p['disk']['path']] = {k: p['disk'][k] for k in ('dev', 'ino', 'format', 'backing')}
    out['vms'].append({'id': p['uuid'], 'name': p['display_name'], 'auto_up': False, 'environment_variables': ['DROIDVM_RUNTIME_PROFILE=' + p['id']], 'extra_options': [m['guard']], 'disks': [{'path': p['disk']['path']}], 'boot': {'protocol': 'linux', 'linux': {'source': 'manual', 'kernel': p['kernel']['path'], 'initrd': p['initrd']['path']}}})
print(json.dumps(out, ensure_ascii=True, indent=2))
