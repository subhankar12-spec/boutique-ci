FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS plugins
COPY scripts/install-locked-plugins.py /tmp/install-locked-plugins.py
COPY controller/plugins.lock.json /tmp/plugins.lock.json
RUN python /tmp/install-locked-plugins.py --lock /tmp/plugins.lock.json --directory /plugins --workers 4
FROM jenkins/jenkins:2.580.1-lts-jdk21@sha256:a660310e39ade10631f774bacd5219de767dfd08947ea5c32a739b5e5bb382c1
COPY --from=plugins --chown=jenkins:jenkins /plugins/ /usr/share/jenkins/ref/plugins/
COPY --chown=jenkins:jenkins --chmod=644 controller/plugins.txt /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc/jenkins.yaml /usr/share/jenkins/ref/casc/jenkins.yaml
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc/jenkins.yaml
