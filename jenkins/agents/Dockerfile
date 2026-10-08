# Agent connects to a dedicated rootless Docker daemon; never mount a host socket from the controller.
FROM docker:28.1.1-cli AS dockercli
FROM aquasec/trivy:0.62.1 AS trivy
FROM anchore/syft:v1.26.1 AS syft
FROM jenkins/inbound-agent:3327.v868139a_d00e0-1-jdk21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-yaml git gh curl ca-certificates jq && rm -rf /var/lib/apt/lists/*
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins
COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy
COPY --from=syft /syft /usr/local/bin/syft
USER jenkins
