#!/usr/bin/env python3
"""After a validated controller build, extract the resolved plugin versions.
Run: docker compose exec -T validation-controller python ... is not supported:
copy /var/jenkins_home/plugins locally, then pass that directory to this script.
"""
import sys,zipfile
from pathlib import Path
root=Path(sys.argv[1]);rows=[]
for p in sorted(root.glob('*.jpi')):
 with zipfile.ZipFile(p) as z:
  text=z.read('META-INF/MANIFEST.MF').decode().replace('\r\n','\n').replace('\n ','')
  fields=dict(line.split(': ',1) for line in text.splitlines() if ': ' in line)
  rows.append(fields['Short-Name']+':'+fields['Plugin-Version'])
if not rows:raise SystemExit('No plugins found; refusing an empty lock')
print('\n'.join(rows))
