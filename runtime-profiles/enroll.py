"""Produce an enabled candidate only from a fully reviewed offline inventory.
Does not alter the baseline, device, or installed launcher. Operator must rebuild,
review candidate hashes, and pass preflight again using the resulting binary hash.
"""
import argparse
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('preflight', ROOT / 'preflight.py')
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


def enroll(manifest, inventory):
    candidate = copy.deepcopy(manifest)
    for profile in candidate['profiles']:
        profile['enabled'] = True
        profile['blocked_by'] = []
    result = preflight.evaluate(candidate, inventory)
    if not result['ok']:
        raise ValueError('\n'.join(result['errors']))
    return candidate


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'manifest.json')
    parser.add_argument('--output', type=Path, required=True)
    opts = parser.parse_args()
    candidate = enroll(json.loads(opts.manifest.read_text(encoding='utf-8')), json.loads(opts.inventory.read_text(encoding='utf-8')))
    with opts.output.open('x', encoding='utf-8') as stream:
        json.dump(candidate, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
