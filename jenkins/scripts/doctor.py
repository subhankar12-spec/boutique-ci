#!/usr/bin/env python3
"""Check Jenkins bootstrap prerequisites without starting jobs or sending alerts."""
import argparse
import json
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin-directory', type=Path)
    parser.add_argument('--check-network', action='store_true', help='Probe official HTTPS metadata/artifacts without downloading their bodies')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    failures = []
    lock_path = root / 'controller/plugins.lock.json'
    if not lock_path.exists():
        parser.exit(1, 'No verified plugin lock exists; bootstrap and test an upgrade before freezing it.\n')
    lock = json.loads(lock_path.read_text())
    declared = {line.strip() for line in (root / 'controller/plugins.txt').read_text().splitlines()
                if line.strip() and not line.startswith('#')}
    expected = {f"{row['name']}:{row['version']}" for row in lock['plugins']}
    if declared != expected:
        failures.append('plugins.txt differs from plugins.lock.json')
    else:
        print(f'Plugin declarations match {len(expected)} locked artifacts; core reference {lock["jenkinsVersion"]}')
    executable = shutil.which('java')
    if executable:
        result = subprocess.run([executable, '-version'], capture_output=True, text=True, check=False)
        version = re.search(r'version "(\d+)', result.stderr)
        if not version or int(version[1]) < 21:
            failures.append('Jenkins requires Java 21 or newer')
        else:
            print('Java prerequisite passed')
    else:
        failures.append('Java is unavailable (Docker controller builds supply it separately)')
    if args.plugin_directory:
        result = subprocess.run(['python3', str(root / 'scripts/install-locked-plugins.py'),
                                 '--directory', str(args.plugin_directory), '--verify-only'], check=False)
        if result.returncode:
            failures.append('Installed plugin checksum verification failed')
    if args.check_network:
        urls = [lock['metadataUrl'], lock['core']['url'], lock['plugins'][0]['url']]
        for url in urls:
            try:
                request = urllib.request.Request(url, method='HEAD')
                with urllib.request.urlopen(request, timeout=15) as response:
                    print(f'Official archive access passed: {urllib.parse.urlsplit(url).path}')
            except (OSError, urllib.error.URLError) as exc:
                failures.append(f'Official archive access failed: {urllib.parse.urlsplit(url).path}: {exc}')
    if failures:
        parser.exit(1, '\n'.join(failures) + '\n')
    print('Prerequisites passed. This does not prove that Jenkins jobs or deployments have executed.')


if __name__ == '__main__':
    main()
