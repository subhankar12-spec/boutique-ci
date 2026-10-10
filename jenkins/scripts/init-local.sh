#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
python3 - <<'INIT'
from pathlib import Path
import secrets
import subprocess
path=Path('.env')
existing=path.read_text() if path.exists() else ''
names={line.split('=',1)[0] for line in existing.splitlines() if '=' in line}
defaults={
    'JENKINS_ADMIN_USER':'admin',
    'JENKINS_ADMIN_PASSWORD':secrets.token_urlsafe(40),
    'JENKINS_PLATFORM_ADMIN_PASSWORD':secrets.token_urlsafe(40),
    'BOUTIQUE_CI_LIBRARY_REF':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
}
additions=''.join(f'{key}={value}\n' for key,value in defaults.items() if key not in names)
path.write_text(existing+('' if not existing or existing.endswith('\n') else '\n')+additions)
path.chmod(0o600)
INIT
echo 'Jenkins credentials preserved/generated in ignored jenkins/.env. Review the pinned library SHA before starting.'
