"""Offline deployment/start preflight. Consumes an explicit fresh inventory; never touches a device.

Inventory schema (deployment operator owns collection):
 {launcher_sha256, dispatcher_sha256, files:{absolute_path:sha256},
  disks:{path:{dev,ino,format,backing}}, vms:[full configs],
  stock_guard:{stock_sha256,rejected_argument,no_vm_started},
  dependencies_reviewed:bool}
No repair or installation is performed. A plan is not a readiness assertion.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def evaluate(manifest, inventory):
    errors = []
    launcher = inventory.get('launcher_sha256')
    desired = inventory.get('dispatcher_sha256')
    valid_digest = lambda v: isinstance(v, str) and len(v) == 64 and all(c in '0123456789abcdef' for c in v)
    if not valid_digest(desired):
        errors.append('compiled dispatcher SHA256 missing/invalid')
    if launcher != desired and launcher not in manifest['known_replaced_launchers']:
        errors.append('unknown launcher: refuse automatic overwrite or start')
    guard = inventory.get('stock_guard', {})
    if guard.get('stock_sha256') != manifest['stock']['sha256'] or guard.get('rejected_argument') != manifest['guard'].split('=')[0] or guard.get('no_vm_started') is not True:
        errors.append('stock guard rejection evidence missing (exit code is not readiness)')
    if inventory.get('dependencies_reviewed') is not True:
        errors.append('complete ELF dependency/LD_PRELOAD review required')
    if inventory.get('proc_fd_scan_verified') is not True:
        errors.append('root /proc fd inspection must be verified on the target')
    if inventory.get('private_lock_lifetime_verified') is not True:
        errors.append('target runtime must hold private lock FDs until exit; second flock must fail')
    observed = inventory.get('files', {})
    for p in manifest['profiles']:
        prefix = p['id'] + ': '
        if not p['enabled'] or p['blocked_by']:
            errors.append(prefix + 'profile disabled/unresolved enrollment blockers')
        for f in [p['runtime'], p['kernel'], p['initrd'], *p['libraries'], manifest['stock']]:
            if observed.get(f['path']) != f['sha256']:
                errors.append(prefix + 'hash mismatch/missing: ' + f['path'])
        disk = p['disk']
        actual = inventory.get('disks', {}).get(disk['path'], {})
        for key in ('format', 'backing'):
            if key not in actual or actual[key] != disk[key]:
                errors.append(prefix + 'disk identity/chain mismatch: ' + key)
        # FUSE dev/ino are observations in this collection namespace, not permanent IDs.
        if not isinstance(actual.get('dev'), int) or not isinstance(actual.get('ino'), int) or actual['ino'] <= 0:
            errors.append(prefix + 'current disk dev/inode missing')
        vms = [vm for vm in inventory.get('vms', []) if vm.get('id') == p['uuid']]
        if len(vms) != 1:
            errors.append(prefix + 'expected exactly one persisted UUID')
            continue
        vm = vms[0]
        if vm.get('auto_up') is not False:
            errors.append(prefix + 'auto_up must stay false until readiness acceptance')
        if vm.get('name') != p['display_name']:
            errors.append(prefix + 'control socket name mismatch')
        env = vm.get('environment_variables', [])
        selected = [x for x in env if isinstance(x, str) and x.split('=', 1)[0].strip() == 'DROIDVM_RUNTIME_PROFILE']
        if selected != ['DROIDVM_RUNTIME_PROFILE=' + p['id']]:
            errors.append(prefix + 'exactly one profile environment required')
        if vm.get('extra_options', []).count(manifest['guard']) != 1:
            errors.append(prefix + 'exactly one persistent stock-rejection guard required')
        if any('droidvm-profile-guard' in str(x) and x != manifest['guard'] for x in vm.get('extra_options', [])):
            errors.append(prefix + 'unknown guard version')
        disks = vm.get('disks', [])
        if len(disks) != 1 or disks[0].get('path') != disk['path'] or disks[0].get('readonly', False):
            errors.append(prefix + 'exact independent writable disk required')
        boot = vm.get('boot', {})
        # BootConfig stores protocol and a nested linux branch; no legacy top-level fallback.
        manual = boot.get('linux', {})
        if boot.get('protocol') != 'linux' or manual.get('source') != 'manual' or manual.get('kernel') != p['kernel']['path'] or manual.get('initrd') != p['initrd']['path']:
            errors.append(prefix + 'explicit manual boot identity mismatch')
    return {'ok': not errors, 'errors': errors, 'action': 'none' if errors else ('already-installed' if launcher == desired else 'explicit-known-version-replay-required'),
            'readiness': 'NOT VERIFIED: require daemon RUNNING + PID + native service/input + guest boot; stock may exit zero on argument failure'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'manifest.json')
    opts = parser.parse_args()
    result = evaluate(json.loads(opts.manifest.read_text(encoding='utf-8')), json.loads(opts.inventory.read_text(encoding='utf-8')))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    raise SystemExit(0 if result['ok'] else 78)
