#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then echo 'Existing Jenkins credentials preserved.'; exit 0; fi
umask 077
python3 - <<'INIT'
from pathlib import Path
import secrets
Path('.env').write_text('JENKINS_ADMIN_USER=admin\nJENKINS_ADMIN_PASSWORD='+secrets.token_urlsafe(40)+'\n')
INIT
echo 'Jenkins admin credentials generated in ignored jenkins/.env. Read locally; never commit.'
