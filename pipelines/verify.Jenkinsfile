pipeline {
    agent { label 'trusted-deploy' }
    options { timestamps();timeout(time:20,unit:'MINUTES');disableConcurrentBuilds();skipDefaultCheckout(true);copyArtifactPermission('boutique-promote,boutique-rollback,boutique-bootstrap') }
    parameters {
        choice(name:'TARGET',choices:['dev','staging','production'])
        choice(name:'SERVICE',choices:['frontend','catalogue','cart','orders'])
        string(name:'IMAGE',defaultValue:'',description:'Exact immutable image expected in the selected service')
        string(name:'GITOPS_COMMIT',defaultValue:'',description:'Full reviewed GitOps merge commit')
    }
    stages {
        stage('Validate verification target') {
            steps { script {
                if (!(params.TARGET in ['dev','staging','production']) || !(params.SERVICE in ['frontend','catalogue','cart','orders'])) { error('Invalid target/service') }
                if (!(params.GITOPS_COMMIT ==~ /[a-f0-9]{40}/)) { error('Full GitOps commit required') }
                if (!(params.IMAGE ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/) || !params.IMAGE.startsWith("ghcr.io/subhankar12-spec/boutique-${params.SERVICE}@")) { error('Expected service digest') }
                env.BASE_URL = env["BOUTIQUE_${params.TARGET.toUpperCase()}_ORIGIN"]
                if (!env.BASE_URL || !env.BASE_URL.startsWith('https://')) { error('Configure a trusted HTTPS origin for this environment on the release controller') }
            } }
        }
        stage('Checkout protected verification tools') {
            steps {
                deleteDir()
                dir('platform') { checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-platform.git',credentialsId:'github-read']]]) }
                dir('gitops') {
                    checkout([$class:'GitSCM',branches:[[name:params.GITOPS_COMMIT]],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                    sh 'if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; else git fetch origin main; fi; git merge-base --is-ancestor "$GITOPS_COMMIT" origin/main'
                }
            }
        }
        stage('Wait for Argo CD and rollout') {
            steps {
                withCredentials([file(credentialsId:"kubeconfig-${params.TARGET}",variable:'KUBECONFIG')]) {
                    sh '''set -eu
                        mkdir -p reports
                        python3 platform/scripts/wait-for-deployment.py --environment "$TARGET" --commit "$GITOPS_COMMIT" --service "$SERVICE" --image "$IMAGE" --gitops-root gitops --output reports/synchronized-commit.txt --timeout 480
                        for service in frontend catalogue cart orders; do
                            kubectl -n "boutique-$TARGET" rollout status "deployment/$service" --timeout=180s
                        done
                    '''
                }
            }
        }
        stage('Functional smoke and measured snapshots') {
            steps {
                withCredentials([file(credentialsId:"kubeconfig-${params.TARGET}",variable:'KUBECONFIG'),file(credentialsId:"boutique-ca-${params.TARGET}",variable:'SSL_CERT_FILE')]) {
                    sh '''set -eu
                        python3 platform/tests/smoke/smoke.py --environment "$TARGET" --report reports/smoke.json
                        kubectl -n "boutique-$TARGET" get deployments -o json > reports/deployments.json
                        kubectl -n "boutique-$TARGET" get pods -l "app.kubernetes.io/name=$SERVICE" -o json > reports/pods.json
                        kubectl -n argocd get application "boutique-$TARGET" -o json > reports/argocd.json
                    '''
                }
            }
        }
        stage('Bind snapshots to synchronized history') {
            steps {
                sh '''set -eu
                    git -C gitops fetch origin main
                    python3 - <<'REVISION'
import json,os,re,subprocess
from pathlib import Path
revision=json.loads(Path('reports/argocd.json').read_text())['status']['sync']['revision']
if not re.fullmatch(r'[a-f0-9]{40}',revision):
    raise SystemExit('Invalid synchronized GitOps revision')
for ancestor,descendant in ((os.environ['GITOPS_COMMIT'],revision),(revision,'origin/main')):
    subprocess.run(['git','-C','gitops','merge-base','--is-ancestor',ancestor,descendant],check=True)
subprocess.run(['git','-C','gitops','checkout','--detach',revision],check=True)
Path('reports/verified-commit.txt').write_text(revision+'\\n')
REVISION
                '''
            }
        }
        stage('Sign verified release evidence') {
            steps {
                withCredentials([file(credentialsId:'release-evidence-signing-key',variable:'EVIDENCE_SIGNING_KEY')]) {
                    sh '''set -eu
                        python3 gitops/scripts/release_evidence.py create --environment "$TARGET" --service "$SERVICE" --image "$IMAGE" --gitops-commit "$(cat reports/verified-commit.txt)" --deployment-json reports/deployments.json --pods-json reports/pods.json --argocd-json reports/argocd.json --smoke-json reports/smoke.json --build-url "$BUILD_URL" --signing-key "$EVIDENCE_SIGNING_KEY" --output verification-evidence.json
                    '''
                }
                archiveArtifacts artifacts:'verification-evidence.json,reports/*.json',fingerprint:true
            }
        }
    }
    post { unsuccessful { archiveArtifacts artifacts:'reports/*.json',allowEmptyArchive:true } }
}
