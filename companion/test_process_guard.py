"""Run the unchanged guard logic against a synthetic /proc tree, never the host's VMs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

source = Path(__file__).with_name('assets').joinpath('crosvm-process-guard.sh').read_text()
with tempfile.TemporaryDirectory() as temp:
    proc = Path(temp) / 'proc'
    proc.mkdir()
    script = source.replace('/proc', str(proc)).replace('/system/bin/readlink', shutil.which('readlink'))

    def process(pid, name, parent):
        directory = proc / str(pid)
        directory.mkdir()
        (directory / 'comm').write_text(name + '\n')
        (directory / 'stat').write_text(f'{pid} ({name}) S {parent} 0 0\n')
        (directory / 'exe').symlink_to('/unused/bin/' + name)

    def check(allowed, expected):
        result = subprocess.run(['sh'], input=script, text=True, capture_output=True,
                                env=dict(os.environ, DROIDVM_ALLOWED_PID=str(allowed)), check=True)
        assert result.stdout == expected, (allowed, result.stdout, expected)

    process(1, 'init', 0)
    process(2, 'ordinary-app', 1)
    check(0, 'CLEAR')
    process(100, 'crosvm.orig', 1)
    check(0, 'BUSY')
    check(100, 'CLEAR')
    process(101, 'crosvm-child', 100)
    process(102, 'crosvm-grandchild', 101)
    check(100, 'CLEAR')
    process(200, 'panvk-crosvm', 1)
    check(100, 'BUSY')
    shutil.rmtree(proc / '200')
    (proc / '101' / 'stat').unlink()
    check(100, 'UNREADABLE')
print('Process guard fixtures passed: empty, unmanaged, target, child, foreign, unreadable')
