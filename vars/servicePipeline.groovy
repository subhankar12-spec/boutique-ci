def call(Map config) {
    def allowed = ['frontend', 'catalogue', 'cart', 'orders', 'incident-bridge']
    if (!(config.service in allowed)) { error('Unsupported service') }
    def isRelease = { env.BOUTIQUE_CONTROLLER_ROLE == 'release' && env.BRANCH_NAME == 'main' && !env.CHANGE_ID }
    pipeline {
        agent none
        options {
            timestamps()
            timeout(time: 60, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '30'))
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
            copyArtifactPermission('boutique-promote,boutique-rollback,boutique-bootstrap')
        }
        parameters { booleanParam(name: 'DELIVER_TO_DEV', defaultValue: true, description: 'Disable only for the initial four-service bootstrap release') }
        environment {
            SERVICE = "${config.service}"
            REGISTRY = 'ghcr.io'
            GITHUB_OWNER = 'subhankar12-spec'
        }
        stages {
            stage('Validate, build and publish') {
                agent { label "${env.BOUTIQUE_CONTROLLER_ROLE == 'release' ? 'trusted-release' : 'isolated-builder'}" }
                stages {
                    stage('Checkout') {
                        steps {
                            deleteDir(); checkout scm
                            script {
                                if (env.BOUTIQUE_CONTROLLER_ROLE == 'release' && !isRelease()) {
                                    error('Release controller accepts protected main branch builds only')
                                }
                                env.SOURCE_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                                env.BUILD_CONTEXT = config.service == 'incident-bridge' ? 'monitoring/incident-bridge' : '.'
                                env.IMAGE_TAG = "${env.REGISTRY}/${env.GITHUB_OWNER}/boutique-${config.service}:${env.SOURCE_COMMIT}"
                            }
                        }
                    }
                    stage('Validate protected source') {
                        when { expression { isRelease() } }
                        steps { sh 'git fetch origin main && git merge-base --is-ancestor "$SOURCE_COMMIT" origin/main' }
                    }
                    stage('Source secret scan') {
                        steps { sh 'trivy fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL .' }
                    }
                    stage('Monitoring configuration') {
                        when { expression { config.service == 'incident-bridge' } }
                        steps { sh 'bash monitoring/validate.sh' }
                    }
                    stage('AWS trust bundle') {
                        when { expression { isRelease() && config.service == 'orders' } }
                        steps { sh 'cd certs && sha256sum -c global-bundle.sha256 && openssl crl2pkcs7 -nocrl -certfile global-bundle.pem | openssl pkcs7 -print_certs -noout >/dev/null' }
                    }
                    stage('Helm validation and packaging') {
                        when { expression { config.service != 'incident-bridge' } }
                        steps {
                            sh '''set -eu
                                helm lint --strict helm
                                helm template "boutique-$SERVICE" helm --namespace boutique-dev > chart-render.yaml
                                kubeconform -strict -summary -kubernetes-version 1.34.0 chart-render.yaml
                                mkdir -p chart-package
                                helm package helm --version "0.1.0-$SOURCE_COMMIT" --app-version "$SOURCE_COMMIT" --destination chart-package
                                cp "chart-package/boutique-$SERVICE-0.1.0-$SOURCE_COMMIT.tgz" chart.tgz
                            '''
                            archiveArtifacts artifacts:'chart.tgz,chart-render.yaml',fingerprint:true
                        }
                    }
                    stage('Test and build') {
                        steps {
                            sh '''
                        set -eu
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        mkdir -p "$DOCKER_CONFIG"
                        docker build --pull --tag "$IMAGE_TAG" "$BUILD_CONTEXT"
                        printf '%s\n' "$IMAGE_TAG" > image.txt
                    '''
                        }
                    }
                    stage('Durable queue integration') {
                        when { expression { config.service == 'incident-bridge' } }
                        steps { sh 'python3 monitoring/incident-bridge/run-postgres-tests.py --image "$IMAGE_TAG"' }
                    }
                    stage('Security and SBOM') {
                        steps {
                            sh '''
                        set -eu
                        trivy image --format json --output image-scan.json --ignorefile .trivyignore.yaml --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$IMAGE_TAG"
                        syft "$IMAGE_TAG" -o cyclonedx-json=sbom.json
                    '''
                        }
                    }
                    stage('Publish tested artifact') {
                        when { expression { isRelease() } }
                        steps {
                            withCredentials([usernamePassword(credentialsId: 'ghcr-publish', usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_TOKEN')]) {
                                sh '''
                            set +x
                            set -eu
                            export DOCKER_CONFIG="$WORKSPACE/.docker"
                            export HELM_REGISTRY_CONFIG="$DOCKER_CONFIG/helm-registry.json"
                            trap 'docker logout "$REGISTRY" >/dev/null 2>&1 || true; helm registry logout "$REGISTRY" >/dev/null 2>&1 || true' EXIT
                            printf '%s' "$REGISTRY_TOKEN" | docker login "$REGISTRY" -u "$REGISTRY_USER" --password-stdin
                            if docker manifest inspect "$IMAGE_TAG" >/dev/null 2> registry-check.log; then
                                echo 'Commit tag already exists; refusing to overwrite it. Promote its recorded release instead.'
                                exit 1
                            fi
                            if ! grep -Eqi 'manifest unknown|no such manifest|NAME_UNKNOWN|MANIFEST_UNKNOWN' registry-check.log; then
                                echo 'Cannot establish registry tag availability; refusing publication.'
                                exit 1
                            fi
                            if [ "$SERVICE" != incident-bridge ]; then
                                printf '%s' "$REGISTRY_TOKEN" | helm registry login "$REGISTRY" -u "$REGISTRY_USER" --password-stdin
                                if helm show chart "oci://$REGISTRY/$GITHUB_OWNER/charts/boutique-$SERVICE" --version "0.1.0-$SOURCE_COMMIT" >/dev/null 2> chart-registry-check.log; then
                                    echo 'Chart version already exists; refusing to overwrite it.'; exit 1
                                fi
                                if ! grep -Eqi 'manifest unknown|not found|NAME_UNKNOWN|MANIFEST_UNKNOWN|404' chart-registry-check.log; then
                                    echo 'Cannot establish chart version availability; refusing publication.'; exit 1
                                fi
                            fi
                            docker push "$IMAGE_TAG"
                            docker inspect --format '{{index .RepoDigests 0}}' "$IMAGE_TAG" > image-digest.txt
                            if [ "$SERVICE" != incident-bridge ]; then
                                helm push "chart-package/boutique-$SERVICE-0.1.0-$SOURCE_COMMIT.tgz" "oci://$REGISTRY/$GITHUB_OWNER/charts" > chart-push.log 2>&1
                            fi
                        '''
                            }
                            script {
                                env.RELEASE_IMAGE = readFile('image-digest.txt').trim()
                                if (!(env.RELEASE_IMAGE ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/)) { error('Missing immutable registry digest') }
                            }
                            sh '''python3 - <<'RELEASE'
import hashlib,json,os,re
from pathlib import Path
record={"schema_version":2,"service":os.environ["SERVICE"],"image":os.environ["RELEASE_IMAGE"],"source_commit":os.environ["SOURCE_COMMIT"],"build_url":os.environ["BUILD_URL"],"tests_passed":True,"security_gate_passed":True,"sbom_sha256":hashlib.sha256(Path("sbom.json").read_bytes()).hexdigest(),"scan_sha256":hashlib.sha256(Path("image-scan.json").read_bytes()).hexdigest()}
if os.environ["SERVICE"]!="incident-bridge":
    matches=re.findall(r"Digest: (sha256:[a-f0-9]{64})",Path("chart-push.log").read_text())
    if len(matches)!=1:raise SystemExit("Missing chart OCI digest")
    record["chart"]={"name":"boutique-"+os.environ["SERVICE"],"repository":"oci://ghcr.io/subhankar12-spec/charts","version":"0.1.0-"+os.environ["SOURCE_COMMIT"],"package_sha256":hashlib.sha256(Path("chart.tgz").read_bytes()).hexdigest(),"oci_digest":matches[0]}
Path("release.json").write_text(json.dumps(record,indent=2)+"\\n")
RELEASE
                    '''
                            dir('delivery-tools') {
                                checkout([$class:'GitSCM', branches:[[name:'main']], userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git', credentialsId:'github-read']]])
                            }
                            script {
                                env.DEV_BASELINE_READY = sh(script: '''python3 - <<'BASELINE'
import sys
sys.path.insert(0,'delivery-tools/scripts')
from evidence import ROOT,selected_image
print(str(all('@sha256:' in selected_image(ROOT,s,'dev') for s in ('frontend','catalogue','cart','orders'))).lower())
BASELINE
                                ''', returnStdout:true).trim()
                                if (env.DEV_BASELINE_READY != 'true') {
                                    echo 'Initial dev application requires boutique-bootstrap; published artifacts are available for its aggregate release.'
                                }
                            }
                            withCredentials([file(credentialsId:'release-artifact-signing-key', variable:'RELEASE_SIGNING_KEY')]) {
                                sh 'python3 delivery-tools/scripts/release_attestation.py sign --record release.json --signing-key "$RELEASE_SIGNING_KEY" --output release-attestation.json'
                            }
                            archiveArtifacts artifacts: 'release.json,release-attestation.json,image-digest.txt,sbom.json,image-scan.json,chart.tgz', fingerprint: true, allowEmptyArchive: true
                        }
                    }

                }
                post {
                    always {
                        archiveArtifacts artifacts: 'image.txt,release.json,release-attestation.json,image-digest.txt,sbom.json,image-scan.json,chart.tgz', allowEmptyArchive: true
                        sh 'rm -rf .docker'
                    }
                }
            }
            stage('Deliver to dev') {
                when { expression { isRelease() && config.service != 'incident-bridge' && params.DELIVER_TO_DEV && env.DEV_BASELINE_READY == 'true' } }
                steps {
                    build job: 'boutique-promote', wait: true, propagate: true, parameters: [
                    string(name: 'TARGET', value: 'dev'),
                    string(name: 'SERVICE', value: config.service),
                    string(name: 'IMAGE', value: env.RELEASE_IMAGE),
                    string(name: 'RELEASE_BUILD', value: env.BUILD_NUMBER),
                    booleanParam(name: 'AUTO_MERGE_DEV', value: true)
                    ]
                }
            }
        }

    }
}
