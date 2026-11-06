"""Compile reviewed JSON into constants; never load user-selected paths at runtime."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def compile_manifest(data):
    assert data['schema'] == 1
    profiles = data['profiles']
    assert {p['id'] for p in profiles} == {'gfxstream-v1', 'panvk-v1'}
    assert len({p['uuid'] for p in profiles}) == len(profiles) == 2
    assert len({(p['disk']['dev'], p['disk']['ino']) for p in profiles}) == 2
    q = lambda s: json.dumps(s, ensure_ascii=False)
    rows = []
    files = []
    envs = []
    for p in profiles:
        assert p['disk']['backing'] is None
        assert p['disk']['format'] in ('raw', 'qcow2')
        assert not p['enabled'] or not p['blocked_by']
        paths = [p[k] for k in ('runtime', 'kernel', 'initrd')] + p['libraries']
        for f in paths:
            assert f['path'].startswith('/') and not any(c in f['path'] for c in '\n,')
            assert '..' not in f['path'].split('/')
            assert re.fullmatch('[0-9a-f]{64}', f['sha256'])
        run = '/data/data/cn.classfun.droidvm/run/'
        socket = run + p['display_name'] + '.sock'
        assert len(socket.encode()) < 108
        ident = p['uuid']
        service = f'name=droidvm_disp_{ident}_gpu-0,screen=gpu-0'
        rows.append('{' + ','.join([q(p['id']), q(p['uuid']), q(p['disk']['path']), q(p['disk']['backend_path']), str(p['disk']['dev']), str(p['disk']['ino']), q(p['kernel']['path']), q(p['initrd']['path']), q(socket), q(service), q(p['runtime']['path']), q(p['library_path']), str(int(p['enabled'])), str(len(files)), str(len(paths)), str(len(envs)), str(len(p['environment']))]) + '}')
        files.extend('{' + q(f['path']) + ',' + q(f['sha256']) + '}' for f in paths)
        envs.extend('{' + q(k) + ',' + q(v) + '}' for k, v in p['environment'].items())
    return ('static const struct Profile profiles[] = {' + ',\n'.join(rows) + '};\n'
            + 'static const struct FileHash files[] = {' + ',\n'.join(files) + '};\n'
            + 'static const struct Env envs[] = {' + ',\n'.join(envs) + '};\n'
            + '#define GUARD ' + q(data['guard']) + '\n'
            + '#define STOCK ' + q(data['stock']['path']) + '\n'
            + '#define STOCK_HASH ' + q(data['stock']['sha256']) + '\n'
            + '#define PROBE_NONCE ' + q(data.get('test_only_probe_nonce', '')) + '\n')

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, default=ROOT / 'manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'manifest.inc')
    opts = parser.parse_args()
    opts.output.write_text(compile_manifest(json.loads(opts.manifest.read_text(encoding='utf-8'))), encoding='utf-8')
