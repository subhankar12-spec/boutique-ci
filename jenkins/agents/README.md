# Jenkins agents

The common Linux amd64 image contains Java 21 JDK, Docker 29.4.0 CLI/buildx,
Python/PyYAML, Git, OpenSSL, jq, AWS CLI, kubectl 1.34.0, kubeconform 0.6.7,
Helm 3.22.0, Terraform 1.11.4, Trivy 0.75.0, Syft 1.54.1, GitHub CLI 2.102.0,
Go 1.27.2 and Maven 3.9.16. Base/CLI images are digest-pinned; Debian package
dependencies come from a fixed signed snapshot. Official tool archives have
reviewed SHA256/SHA512 pins checked before extraction, followed by executable
hash/version checks. Pins require reviewed updates and image scans; they do
not establish that an old version remains free of vulnerabilities.

The snapshot endpoint `snapshot.debian.org` must be reachable during image
builds. This cloud connection returned HTTP 403 for that endpoint. All eight
downloaded tool distributions passed executable checks from `/tmp`, and Docker
BuildKit's definition check passed; the complete image has not been built or
scanned here because of restricted snapshot access and limited Docker storage.

To verify tools independently, run the installer and add its `bin` directory to PATH:

```bash
python3 jenkins/scripts/install-agent-tools.py --destination /absolute/path/tools
```

Java 21 must already be installed for Maven. `--verify-only` checks the stored executable
hashes and versions without downloads. Run
`python3 jenkins/scripts/test-agent-tools.py` for checksum, trusted-origin and
archive-traversal rejection checks. The Dockerfile-specific ignore file limits
its build context to the installer and build definition, excluding controller
credentials/configuration and Git.

Use separate disposable Linux VMs for validation and trusted release work. Never share workspaces, Docker daemons or credentials between those trust boundaries. The controllers have zero executors. Create inbound nodes with the appropriate labels: `isolated-builder`, `trusted-release`, `trusted-deploy`, `policy-check`, and `terraform-trusted`. Dispose of untrusted PR agents after every build.

Build the common agent image from the `boutique-ci` repository root:

```bash
docker build --pull --platform linux/amd64 -f jenkins/agents/Dockerfile -t boutique-agent:local .
```

Build agents connect to a dedicated rootless Docker daemon owned by their user. Follow Docker's supported distribution instructions to start that daemon; mount its Unix socket into the agent and set `DOCKER_HOST` to the mounted socket. Mounting a Docker socket gives control over that daemon, even with a read-only bind mount. Never mount the controller host's rootful Docker socket. Deploy, protected policy-check, and Terraform agents do not need a Docker socket. The policy-check node runs protected validation tools with only the scoped GitHub checks credential; keep it separate from arbitrary PR builds.

Docker-using container agents must mount their workspace at the same absolute path that the Docker daemon sees on its host. Integration checks bind configuration and temporary fixtures by that path. Alternatively, run the inbound agent directly on its dedicated VM. A private remote daemon without shared workspace files cannot run those bind-mounted checks.

## Connect locally over WebSocket

In Jenkins, create a permanent inbound node with one executor and the intended label. Save its generated agent secret in a mode-0600 file inside a private directory outside all checkouts. Set `BOUTIQUE_AGENT_SECRET_FILE` to that file and `BOUTIQUE_AGENT_WORKSPACE` to a private, writable directory dedicated to this node. The file and workspace must be readable/writable by the UID used below.

The controller Compose ports bind to loopback. On Linux, host networking lets a local agent container reach those ports. A host-gateway address cannot reach a listener bound only to loopback.

```bash
: "${BOUTIQUE_AGENT_SECRET_FILE:?Set the private agent-secret file path}"
: "${BOUTIQUE_AGENT_WORKSPACE:?Set this node's private workspace path}"
docker run --rm --name boutique-trusted-deploy --network host \
  --user "$(id -u):$(id -g)" \
  -e JENKINS_URL="${JENKINS_RELEASE_URL:-http://127.0.0.1:8091/}" \
  -e JENKINS_AGENT_NAME=boutique-trusted-deploy \
  -e JENKINS_WEB_SOCKET=true \
  -e JENKINS_SECRET_FILE=/run/secrets/agent-secret \
  -e JENKINS_AGENT_WORKDIR=/workspace \
  -v "$BOUTIQUE_AGENT_SECRET_FILE:/run/secrets/agent-secret:ro" \
  -v "$BOUTIQUE_AGENT_WORKSPACE:/workspace" \
  boutique-agent:local
```

For validation use its separately registered node name, the validation controller URL (port 8090), and a separate secret/workspace. WebSocket mode needs no exposed TCP remoting port. Agent secrets are generated for Jenkins nodes; they are not GitHub tokens or registry credentials.

## Remote agents

Expose each controller through authenticated TLS infrastructure and configure `JENKINS_VALIDATION_URL` / `JENKINS_RELEASE_URL` with the externally reachable HTTPS URLs before starting controller Compose. Remove `--network host` for remote agents and use their normal routed network. Import the actual organizational CA into the agent trust store when needed; do not disable certificate verification.

Trusted deploy agents need network access to the cluster API and application TLS origins. Terraform agents use short-lived workload/instance-role credentials and a route to private EKS endpoints. Configure scoped Jenkins identities and permissions, node restrictions, controller backups, and reviewed GitHub credentials before enabling real delivery. The image and controller smoke check validate tooling/configuration; they do not provision remote agent VMs.
