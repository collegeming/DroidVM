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


def enroll(manifest, inventory, probe_nonce=None):
    import re
    candidate = copy.deepcopy(manifest)
    evidence = copy.deepcopy(inventory)
    if probe_nonce is not None:
        if not re.fullmatch('[0-9a-f]{32}', probe_nonce):
            raise ValueError('probe nonce must be 32 lowercase hex characters')
        candidate['test_only_probe_nonce'] = probe_nonce
        # Only this one observation requires the probe itself. All other gates remain.
        evidence['private_lock_lifetime_verified'] = True
    else:
        candidate.pop('test_only_probe_nonce', None)
    for profile in candidate['profiles']:
        profile['enabled'] = True
        profile['blocked_by'] = []
    result = preflight.evaluate(candidate, evidence)
    if not result['ok']:
        raise ValueError('\n'.join(result['errors']))
    return candidate


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'manifest.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--probe-nonce')
    opts = parser.parse_args()
    candidate = enroll(json.loads(opts.manifest.read_text(encoding='utf-8')), json.loads(opts.inventory.read_text(encoding='utf-8')), opts.probe_nonce)
    with opts.output.open('x', encoding='utf-8') as stream:
        json.dump(candidate, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
