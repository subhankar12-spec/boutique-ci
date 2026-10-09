#!/usr/bin/env python3
"""Download/verify a frozen Jenkins plugin set with official SHA256 checksums.

Use a controller-specific cache outside source control. No credentials or TLS
exceptions are accepted. This installs files; a successful controller boot and
Declarative validation are separate, mandatory upgrade checks.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download(row, destination, verify_only=False):
    if destination.is_file() and sha256(destination) == row['sha256']:
        return
    if verify_only:
        raise ValueError(f'Missing or altered artifact: {destination.name}')
    parsed = urllib.parse.urlsplit(row['url'])
    if parsed.scheme != 'https' or parsed.hostname != 'archives.jenkins.io' or parsed.username or parsed.query:
        raise ValueError('Artifacts must use HTTPS from the official Jenkins archive')
    descriptor, temporary = tempfile.mkstemp(prefix=destination.name + '.', dir=destination.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            with urllib.request.urlopen(row['url'], timeout=180) as response:
                if urllib.parse.urlsplit(response.url).hostname != 'archives.jenkins.io':
                    raise ValueError('Unexpected archive redirect; review network access')
                while chunk := response.read(65536):
                    stream.write(chunk)
        if sha256(temporary) != row['sha256']:
            raise ValueError(f'Official SHA256 mismatch: {destination.name}')
        temporary.chmod(0o644)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=Path(__file__).resolve().parents[1] / 'controller/plugins.lock.json')
    parser.add_argument('--directory', type=Path, required=True, help='Plugin destination, outside the repository')
    parser.add_argument('--include-core', action='store_true', help='Also download verified jenkins.war alongside the plugin directory')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        parser.error('--workers must be between 1 and 16')
    lock = json.loads(args.lock.read_text())
    if lock['schemaVersion'] != 1 or lock['checksumSource'] != 'official-update-center':
        parser.error('A checksum-verified official lock is required')
    args.directory.mkdir(parents=True, exist_ok=True)
    identities = set()
    requests = []
    for row in lock['plugins']:
        name = row['name']
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+', name) or name in identities:
            parser.error('Invalid/duplicate plugin name')
        identities.add(name)
        if not re.fullmatch(r'[0-9a-f]{64}', row['sha256']):
            parser.error(f'Invalid checksum for {name}')
        requests.append((row, args.directory / f'{name}.jpi'))
    if args.include_core:
        requests.append((lock['core'], args.directory.parent / 'jenkins.war'))
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = [executor.submit(download, row, path, args.verify_only) for row, path in requests]
            for future in concurrent.futures.as_completed(futures):
                future.result()
    except (OSError, ValueError) as exc:
        parser.exit(1, f'Jenkins artifact verification failed: {exc}\n')
    print(f'Verified {len(lock["plugins"])} pinned plugins' + (' and Jenkins core' if args.include_core else ''))


if __name__ == '__main__':
    main()
