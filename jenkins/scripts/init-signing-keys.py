#!/usr/bin/env python3
"""Create separate local release/evidence signing keys without revealing values."""
import os
from pathlib import Path
import subprocess


def main():
    directory=Path(__file__).resolve().parents[1]/'.delivery-secrets'
    if directory.is_symlink():
        raise SystemExit('Signing directory cannot be a symbolic link')
    directory.mkdir(mode=0o700,exist_ok=True)
    directory.chmod(0o700)
    os.umask(0o077)
    for purpose in ('artifact','evidence'):
        private=directory/f'release-{purpose}-signing-key.pem'
        public=directory/f'release-{purpose}-public-key.pem'
        if private.is_symlink() or public.is_symlink():
            raise SystemExit('Signing key files cannot be symbolic links')
        if not private.exists():
            subprocess.run(['openssl','genpkey','-algorithm','ED25519','-out',str(private)],check=True)
        subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True)
        private.chmod(0o600)
        public.chmod(0o600)
    print('Keys prepared in ignored jenkins/.delivery-secrets; upload through Jenkins File credentials.')


if __name__=='__main__':
    main()
