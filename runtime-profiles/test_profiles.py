import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('build_manifest', ROOT / 'build_manifest.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

class Profiles(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.d = Path(cls.tmp.name)
        (cls.d / 'proc').mkdir()
        cls.manifest = json.loads((ROOT / 'manifest.json').read_text())
        cls.m = copy.deepcopy(cls.manifest)
        runtime = cls.d / 'fake-runtime'
        runtime.write_text('#!/usr/bin/python3\nimport os,sys,json,fcntl\nlocks=[]\nfor fd in os.listdir("/proc/self/fd"):\n try:\n  path=os.readlink("/proc/self/fd/"+fd)\n  if path.endswith(".lock"):\n   check=os.open(path,os.O_RDWR)\n   try: fcntl.flock(check,fcntl.LOCK_EX|fcntl.LOCK_NB); held=False\n   except BlockingIOError: held=True\n   os.close(check); locks.append(held)\n except FileNotFoundError: pass\nprint(json.dumps({"argv":sys.argv[1:],"fd":os.read(200,32).decode(),"lib":os.environ.get("LD_LIBRARY_PATH"),"profile":os.environ.get("DROIDVM_RUNTIME_PROFILE"),"locks":locks}))\nsys.exit(32)\n')
        runtime.chmod(0o755)
        cls.digest = hashlib.sha256(runtime.read_bytes()).hexdigest()
        cls.m['stock'] = {'path': str(runtime), 'sha256': cls.digest}
        for i, p in enumerate(cls.m['profiles']):
            disk = cls.d / f'disk{i}'
            disk.write_bytes(b'QFI\xfb' + b'\0' * 20 if i == 1 else b'raw-disk-data')
            p['disk'].update(path=str(disk), dev=disk.stat().st_dev, ino=disk.stat().st_ino)
            for key in ('kernel', 'initrd', 'runtime'):
                p[key] = {'path': str(runtime), 'sha256': cls.digest}
            p['libraries'] = []
            p['enabled'] = True
            p['blocked_by'] = []
        cls.src = cls.d / 'dispatcher.c'
        cls.src.write_text((ROOT / 'dispatcher.c').read_text())
        (cls.d / 'manifest.inc').write_text(build.compile_manifest(cls.m))
        cls.exe = cls.d / 'dispatcher'
        subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-DHASH_TOOL="/usr/bin/sha256sum"', '-DLOCK_ROOT="' + str(cls.d) + '"', '-DPROC_ROOT="' + str(cls.d / 'proc') + '"', str(cls.src), '-o', str(cls.exe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def args(self, index=1):
        p = self.m['profiles'][index]
        u = p['uuid']
        gpu = 'virglrenderer,context-types=drm,vulkan=false,egl=true,displays=[[mode=windowed[2272,1514],refresh-rate=60,dpi=[160,160]]]'
        if index == 0:
            gpu = gpu.replace('virglrenderer', 'gfxstream').replace('context-types=drm', 'context-types=gfxstream-vulkan').replace('vulkan=false', 'vulkan=true')
        return ['--extended-status', 'run', '--name', p['display_name'], '--mem', '2048', '--cpus', '4', '--initrd', p['initrd']['path'], '--socket', '/data/data/cn.classfun.droidvm/run/' + p['display_name'] + '.sock', '--block', p['disk']['path'] + ',lock=false', '--gpu', gpu, '--android-display-service', f'name=droidvm_disp_{u}_gpu-0,screen=gpu-0', '--input', f'keyboard[path=/data/data/cn.classfun.droidvm/run/dvmin_{u}_g0_kbd.sock,name=keyboard]', '--serial', 'type=file,hardware=serial,num=1,in-fd=200,out-fd=200', '--droidvm-profile-guard=v1', p['kernel']['path']]

    def run_dispatch(self, args, profile='panvk-v1'):
        env = dict(os.environ)
        env.pop('DROIDVM_RUNTIME_PROFILE', None)
        if profile is not None:
            env['DROIDVM_RUNTIME_PROFILE'] = profile
        r, w = os.pipe()
        os.write(w, b'serial inherited')
        os.close(w)
        os.dup2(r, 200, inheritable=True)
        os.close(r)
        try:
            return subprocess.run([str(self.exe), *args], env=env, text=True, capture_output=True, pass_fds=(200,))
        finally:
            os.close(200)

    def test_panvk_only_gpu_and_guard_change(self):
        args = self.args()
        result = self.run_dispatch(args)
        self.assertEqual(result.returncode, 32, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data['fd'], 'serial inherited')
        self.assertEqual(data['locks'], [True, True])
        expected = args.copy()
        expected.remove('--droidvm-profile-guard=v1')
        actual = data['argv']
        gi = actual.index('--gpu') + 1
        self.assertIn('context-types=virgl2:drm', actual[gi])
        self.assertIn('surfaceless=true', actual[gi])
        self.assertIn('fixed-blob-mapping=true', actual[gi])
        self.assertIn('displays=[[mode=windowed[2272,1514],refresh-rate=60,dpi=[160,160]]]', actual[gi])
        expected[gi] = actual[gi]
        self.assertEqual(actual, expected)

    def test_name_does_not_route_profile(self):
        args = self.args()
        args[args.index('--name') + 1] = 'Totally unrelated VM name with spaces'
        self.assertEqual(self.run_dispatch(args).returncode, 32)

    def test_gfxstream(self):
        result = self.run_dispatch(self.args(0), 'gfxstream-v1')
        self.assertEqual(result.returncode, 32, result.stderr)
        argv = json.loads(result.stdout)['argv']
        self.assertIn('fixed-blob-mapping=true', argv[argv.index('--gpu') + 1])
        self.assertEqual(argv[-2:], ['--pre-alloc', 'gfx-host-mb=1024'])

    def test_failclosed_mutations(self):
        for option in ('--block', '--initrd', '--socket', '--android-display-service', '--input'):
            with self.subTest(option=option):
                args = self.args()
                args[args.index(option) + 1] = '/arbitrary'
                self.assertEqual(self.run_dispatch(args).returncode, 78)
        for suffix in (['--config', '/tmp/override'], ['--gpu', 'context-types=drm'], ['--droidvm-profile-guard=v1'], ['--unknown-future-feature']):
            self.assertEqual(self.run_dispatch(self.args() + suffix).returncode, 78)
        self.assertEqual(self.run_dispatch(self.args(), '../arbitrary').returncode, 78)
        self.assertEqual(self.run_dispatch(self.args(), None).returncode, 78)
        args = self.args()
        args.remove('--droidvm-profile-guard=v1')
        self.assertEqual(self.run_dispatch(args).returncode, 78)

    def test_invalid_disk_format(self):
        disk = Path(self.m['profiles'][1]['disk']['path'])
        old = disk.with_suffix('.old')
        disk.rename(old)
        disk.write_bytes(b'changed')
        try:
            self.assertEqual(self.run_dispatch(self.args()).returncode, 78)
        finally:
            disk.unlink()
            old.rename(disk)

    def test_hash_and_disk_lock(self):
        import fcntl
        with open(self.d / 'unified-gpu-profile.lock', 'a') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(self.run_dispatch(self.args()).returncode, 78)
            self.assertEqual(self.run_dispatch(self.args(0), 'gfxstream-v1').returncode, 78)
        fdpath = self.d / 'proc' / '999999' / 'fd'
        fdpath.mkdir(parents=True)
        (fdpath / '7').symlink_to(self.m['profiles'][1]['disk']['path'])
        try:
            self.assertEqual(self.run_dispatch(self.args()).returncode, 78)
        finally:
            shutil.rmtree(fdpath.parent)
        runtime = Path(self.m['profiles'][1]['runtime']['path'])
        original = runtime.read_bytes()
        runtime.write_bytes(original + b'# modified\n')
        try:
            self.assertEqual(self.run_dispatch(self.args()).returncode, 78)
        finally:
            runtime.write_bytes(original)

    def test_stock_unrelated_route(self):
        result = self.run_dispatch(['--extended-status', 'run', '--name', 'Windows untouched'], None)
        self.assertEqual(result.returncode, 32, result.stderr)
        self.assertEqual(json.loads(result.stdout)['argv'], ['--extended-status', 'run', '--name', 'Windows untouched'])

    def test_production_profiles_not_silently_enabled(self):
        for p in self.manifest['profiles']:
            self.assertFalse(p['enabled'])
            self.assertTrue(p['blocked_by'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
