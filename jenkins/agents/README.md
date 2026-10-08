# Agent prerequisites

Assign disposable Linux VMs separately to the validation controller and release controller. Do not reuse workspaces, Docker daemons or credentials across those trust boundaries. Labels: `isolated-builder`, `trusted-release`, `trusted-deploy`, `terraform-trusted`. The controller has zero executors.

Build agents need Git, Docker CLI + buildx, a dedicated rootless Docker daemon, Trivy and Syft. Start rootless Docker following Docker's supported distribution installation guide; expose its local Unix socket to the agent UID only. A containerized agent may access that VM's dedicated rootless socket; never expose the controller host's rootful Docker socket. Dispose of untrusted PR agents after each build. This repository does not provision agent VMs automatically.

Deployment agents need Git, GitHub CLI, Python with PyYAML, kubectl and kubeconform. Terraform agents need Terraform 1.11+, AWS CLI and a short-lived instance/workload role. The Dockerfile provides the common build toolset; add verified deployment tools using the platform installer and kubeconform's release checksum. Do not assume the build image contains Terraform or kubectl.

Configure inbound agents over HTTPS/WebSocket in remote deployments, with agent secrets supplied through the Jenkins credential system. The local controller Compose ports bind to loopback. Production controllers need TLS ingress, SSO, scoped job authorization, backup storage and a tested plugin lock.
