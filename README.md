# Jenkins delivery

`vars/servicePipeline.groovy` is PR validation only. Service Dockerfiles execute unit tests, then Jenkins checks source secrets, scans the built runtime with Trivy and generates a CycloneDX SBOM with Syft. Fixable HIGH/CRITICAL vulnerabilities fail the build. Unfixed findings remain visible and require review; the gate is not a clean bill of security.

Trusted pipelines:

- `pipelines/release.Jenkinsfile`: accepts a reviewed full source SHA reachable from main; builds/tests/scans once, publishes that same image, records digest and SBOM. Orders releases require a reviewed RDS trust bundle.
- `pipelines/promote.Jenkinsfile`: validates image ownership/digest and preceding environment, validates manifests, opens a GitOps PR; production has an approval gate.
- `pipelines/verify.Jenkinsfile`: checks rollout status and the full HTTP smoke suite; archives the deployed image selection.
- Terraform pipeline lives in `boutique-infrastructure/Jenkinsfile` so SCM checkout resolves the infrastructure source.

See `../boutique-platform/docs/jenkins-setup.md` for trust boundaries, credentials, branch protections and agent setup. Jenkins pipeline syntax is checked locally; plugin-backed Declarative validation and end-to-end jobs still require a running configured controller.

The plugin input currently requests current versions. After a successful controller bootstrap and compatibility check, use `scripts/lock-plugins.py` on its installed plugin directory and replace `jenkins/controller/plugins.txt` with that resolved list. Plugin locking cannot be completed while the update center is blocked; do not call controller builds reproducible before that step.

The trusted release pipeline accepts `SERVICE=incident-bridge`, using `boutique-platform/monitoring/incident-bridge` as its source context. Platform Jenkinsfile validates monitoring configs and tests/scans the adapter on an isolated builder. Monitoring image promotion is a reviewed GitOps change; app promotion remains limited to the four app services. No Jenkins jobs have been executed in this cloud runner.
