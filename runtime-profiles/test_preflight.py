import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent

def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

preflight = load('preflight')
enroller = load('enroll')

class Preflight(unittest.TestCase):
    def setUp(self):
        self.m = json.loads((ROOT / 'manifest.json').read_text(encoding='utf-8'))
        self.inv = {'launcher_sha256': self.m['known_replaced_launchers'][0], 'dispatcher_sha256': '1' * 64, 'files': {}, 'disks': {}, 'vms': [], 'dependencies_reviewed': True,
                    'stock_guard': {'stock_sha256': self.m['stock']['sha256'], 'rejected_argument': '--droidvm-profile-guard', 'no_vm_started': True}, 'proc_fd_scan_verified': True, 'private_lock_lifetime_verified': True}
        for p in self.m['profiles']:
            for f in [p['runtime'], p['kernel'], p['initrd'], *p['libraries'], self.m['stock']]:
                self.inv['files'][f['path']] = f['sha256']
            self.inv['disks'][p['disk']['path']] = copy.deepcopy(p['disk'])
            self.inv['vms'].append({'id': p['uuid'], 'name': p['display_name'], 'auto_up': False, 'environment_variables': ['DROIDVM_RUNTIME_PROFILE=' + p['id']], 'extra_options': [self.m['guard']], 'disks': [{'path': p['disk']['path']}], 'boot': {'protocol': 'linux', 'linux': {'source': 'manual', 'kernel': p['kernel']['path'], 'initrd': p['initrd']['path']}}})

    def test_baseline_disabled_candidate_can_enroll(self):
        self.assertFalse(preflight.evaluate(self.m, self.inv)['ok'])
        candidate = enroller.enroll(self.m, self.inv)
        self.assertTrue(preflight.evaluate(candidate, self.inv)['ok'])
        self.assertFalse(self.m['profiles'][0]['enabled'])
        self.assertEqual(preflight.evaluate(candidate, self.inv)['action'], 'explicit-known-version-replay-required')

    def test_unknown_launcher_never_overwritten(self):
        self.inv['launcher_sha256'] = '2' * 64
        with self.assertRaisesRegex(ValueError, 'unknown launcher'):
            enroller.enroll(self.m, self.inv)

    def test_guard_persisted_and_exact_profile(self):
        self.inv['vms'][0]['extra_options'] = []
        with self.assertRaisesRegex(ValueError, 'persistent stock-rejection guard'):
            enroller.enroll(self.m, self.inv)
        self.setUp()
        self.inv['vms'][0]['environment_variables'].append('DROIDVM_RUNTIME_PROFILE=panvk-v1')
        with self.assertRaisesRegex(ValueError, 'exactly one profile environment'):
            enroller.enroll(self.m, self.inv)

    def test_chain_and_auto_up_rejected(self):
        self.inv['disks'][self.m['profiles'][1]['disk']['path']]['backing'] = '/old/root'
        with self.assertRaisesRegex(ValueError, 'chain mismatch'):
            enroller.enroll(self.m, self.inv)
        self.setUp()
        self.inv['vms'][1]['auto_up'] = True
        with self.assertRaisesRegex(ValueError, 'auto_up'):
            enroller.enroll(self.m, self.inv)

    def test_dependency_and_hash_review_required(self):
        self.inv['dependencies_reviewed'] = False
        with self.assertRaisesRegex(ValueError, 'dependency'):
            enroller.enroll(self.m, self.inv)
        self.setUp()
        self.inv['files'][self.m['profiles'][1]['runtime']['path']] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            enroller.enroll(self.m, self.inv)

if __name__ == '__main__':
    unittest.main(verbosity=2)
