#!/usr/bin/env python3
"""Freeze every resolved Jenkins plugin, including transitive dependencies.

By default print plugins.txt to stdout. Supply --metadata and --json-output to
verify official update-center checksums and save an auditable lock manifest.
Generate a lock only after the controller boots without failed plugins.
"""
import argparse
import base64
import hashlib
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def manifest_fields(raw):
    text = raw.decode('utf-8').replace('\r\n', '\n')
    text = re.sub(r'\n ', '', text)
    return dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)


def lock_plugins(directory, metadata=None):
    rows = {}
    for path in sorted([*directory.glob('*.jpi'), *directory.glob('*.hpi')]):
        with zipfile.ZipFile(path) as archive:
            fields = manifest_fields(archive.read('META-INF/MANIFEST.MF'))
        name, version = fields['Short-Name'], fields['Plugin-Version']
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+', name) or any(c.isspace() for c in version):
            raise ValueError(f'Invalid plugin identity in {path.name}')
        if name in rows:
            raise ValueError(f'Duplicate plugin archive: {name}')
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        row = {'name': name, 'version': version, 'sha256': digest,
               'requiredCore': fields.get('Jenkins-Version', fields.get('Hudson-Version')),
               'dependencies': fields.get('Plugin-Dependencies', '')}
        if metadata is not None:
            expected = metadata['plugins'][name]
            if expected['version'] != version:
                raise ValueError(f'{name}: installed version differs from supplied update center')
            if base64.b64decode(expected['sha256'], validate=True).hex() != digest:
                raise ValueError(f'{name}: official SHA256 mismatch')
            row['url'] = f'https://archives.jenkins.io/plugins/{name}/{version}/{name}.hpi'
        rows[name] = row
    if not rows:
        raise ValueError('No plugins found; refusing an empty lock')
    for row in rows.values():
        for entry in filter(None, row['dependencies'].split(',')):
            identity, *attributes = entry.split(';')
            name, version = identity.split(':', 1)
            if 'resolution:=optional' not in attributes and name not in rows:
                raise ValueError(f"{row['name']}: required dependency {name}:{version} is missing")
    return list(rows.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--metadata', type=Path, help='Official update-center.actual.json used for resolution')
    parser.add_argument('--metadata-url', default='https://archives.jenkins.io/updates/stable/update-center.actual.json')
    parser.add_argument('--json-output', type=Path)
    parser.add_argument('--plugins-output', type=Path)
    parser.add_argument('--jenkins-version')
    args = parser.parse_args()
    try:
        metadata = json.loads(args.metadata.read_text()) if args.metadata else None
        rows = lock_plugins(args.directory, metadata)
        text = '\n'.join(f"{row['name']}:{row['version']}" for row in rows) + '\n'
        if args.plugins_output:
            args.plugins_output.write_text('# All resolved dependencies are pinned; regenerate after a tested upgrade.\n' + text)
        else:
            sys.stdout.write(text)
        if args.json_output:
            core = metadata.get('core') if metadata else None
            result = {'schemaVersion': 1, 'generatedAt': datetime.now(timezone.utc).isoformat(),
                      'metadataUrl': args.metadata_url if metadata else None,
                      'metadataSha256': hashlib.sha256(args.metadata.read_bytes()).hexdigest() if metadata else None,
                      'jenkinsVersion': args.jenkins_version or (core['version'] if core else None),
                      'checksumSource': 'official-update-center' if metadata else 'local-archives-only',
                      'plugins': rows}
            if core:
                result['core'] = {'version': core['version'],
                                  'sha256': base64.b64decode(core['sha256'], validate=True).hex(),
                                  'size': core['size'],
                                  'url': f"https://archives.jenkins.io/war-stable/{core['version']}/jenkins.war"}
            args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        parser.exit(1, f'Plugin lock failed: {exc}\n')


if __name__ == '__main__':
    main()
