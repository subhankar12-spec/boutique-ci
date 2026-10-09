// Fixed job definition from protected boutique-ci/main. Never load a PR Jenkinsfile.
pipeline {
    agent { label 'policy-check' }
    options { timestamps();timeout(time:15,unit:'MINUTES');skipDefaultCheckout(true);buildDiscarder(logRotator(numToKeepStr:'40')) }
    parameters { string(name:'PR_NUMBER',defaultValue:'',description:'GitOps PR number; repository and base branch are fixed') }
    stages {
        stage('Validate PR identity') {
            steps {
                script { if (!(params.PR_NUMBER ==~ /[1-9][0-9]*/)) { error('Expected GitOps PR number') } }
                deleteDir()
                withCredentials([string(credentialsId:'gitops-checks',variable:'GH_TOKEN')]) {
                    sh '''set +x
                        set -eu
                        gh api "repos/subhankar12-spec/boutique-gitops/pulls/$PR_NUMBER" > pr.json
                        python3 - <<'PR'
import json,re
from pathlib import Path
p=json.load(open("pr.json"))
if p.get("state")!="open" or p.get("base",{}).get("ref")!="main" or p.get("base",{}).get("repo",{}).get("full_name")!="subhankar12-spec/boutique-gitops":
    raise SystemExit("Expected an open PR targeting the fixed GitOps main branch")
for field,file in [("head","pr-head-sha.txt"),("base","pr-base-sha.txt")]:
    sha=p[field]["sha"]
    if not re.fullmatch(r"[a-f0-9]{40}",sha):raise SystemExit("Invalid PR commit")
    Path(file).write_text(sha+"\\n")
PR
                    '''
                    script {
                        env.POLICY_HEAD_SHA = readFile('pr-head-sha.txt').trim()
                        env.POLICY_BASE_SHA = readFile('pr-base-sha.txt').trim()
                    }
                    sh '''set +x
                        gh api --method POST "repos/subhankar12-spec/boutique-gitops/statuses/$POLICY_HEAD_SHA" -f state=pending -f context='boutique/gitops-policy' -f description='Checking signed delivery policy against current main' -f target_url="$BUILD_URL" >/dev/null
                    '''
                }
            }
        }
        stage('Checkout protected tools and candidate data') {
            steps {
                dir('trusted-tools') {
                    checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                }
                dir('candidate') {
                    checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                    // Read credentials authenticate Git only. No PR source is executed.
                    withCredentials([usernamePassword(credentialsId:'github-read',usernameVariable:'GIT_USER',passwordVariable:'READ_TOKEN')]) {
                        sh '''set +x
                            set -eu
                            ASKPASS=$(mktemp)
                            trap 'rm -f "$ASKPASS"' EXIT
                            printf '#!/bin/sh\ncase "$1" in *Username*) printf "%%s" "$GIT_USER";; *) printf "%%s" "$READ_TOKEN";; esac\n' > "$ASKPASS"
                            chmod 700 "$ASKPASS"
                            export GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0
                            if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; fi
                            git fetch origin +refs/heads/main:refs/remotes/origin/main "+refs/pull/$PR_NUMBER/head:refs/remotes/origin/policy-pr"
                            test "$(git rev-parse origin/main)" = "$POLICY_BASE_SHA"
                            test "$(git rev-parse origin/policy-pr)" = "$POLICY_HEAD_SHA"
                            git checkout --detach "$POLICY_BASE_SHA"
                            git -c user.name=boutique-policy -c user.email=boutique-policy@users.noreply.github.com merge --no-ff --no-edit "$POLICY_HEAD_SHA"
                        '''
                    }
                }
            }
        }
        stage('Validate signed delivery and rendered resources') {
            steps {
                withCredentials([file(credentialsId:'release-artifact-public-key',variable:'RELEASE_PUBLIC_KEY'),file(credentialsId:'release-evidence-public-key',variable:'EVIDENCE_PUBLIC_KEY')]) {
                    sh '''set -eu
                        python3 trusted-tools/scripts/check_delivery_change.py --root candidate --base "$POLICY_BASE_SHA" --release-public-key "$RELEASE_PUBLIC_KEY" --evidence-public-key "$EVIDENCE_PUBLIC_KEY"
                        python3 trusted-tools/scripts/check_rendered_images.py --root candidate
                        mkdir -p reports
                        for environment in dev staging production; do
                            python3 trusted-tools/scripts/render.py "$environment" --root candidate > "reports/$environment.yaml"
                            kubeconform -strict -summary -kubernetes-version 1.34.0 "reports/$environment.yaml"
                            if [ -d "candidate/lab-profiles/$environment" ]; then
                                python3 trusted-tools/scripts/render.py "$environment" --root candidate --profile lab > "reports/lab-$environment.yaml"
                                kubeconform -strict -summary -kubernetes-version 1.34.0 -skip Certificate,Issuer "reports/lab-$environment.yaml"
                            fi
                        done
                        for profile in nonprod production; do
                            python3 trusted-tools/scripts/render-monitoring.py "$profile" --root candidate --lab > "reports/lab-monitoring-$profile.yaml"
                            kubeconform -strict -summary -kubernetes-version 1.34.0 -skip Certificate "reports/lab-monitoring-$profile.yaml"
                        done
                        for chart in dependencies/homelab platform/external-secrets/monitoring; do
                            helm lint --strict "candidate/$chart"
                            helm template boutique-aux "candidate/$chart" > "reports/aux-$(basename "$chart").yaml"
                            kubeconform -strict -summary -kubernetes-version 1.34.0 -skip ExternalSecret,SecretStore "reports/aux-$(basename "$chart").yaml"
                        done
                        for profile in homelab nonprod production; do
                            python3 trusted-tools/scripts/render-monitoring.py "$profile" --root candidate > "reports/monitoring-$profile.yaml"
                            kubeconform -strict -summary -kubernetes-version 1.34.0 "reports/monitoring-$profile.yaml"
                        done
                    '''
                }
            }
        }
        stage('Confirm current PR base') {
            steps {
                withCredentials([string(credentialsId:'gitops-checks',variable:'GH_TOKEN')]) {
                    sh '''set +x
                        set -eu
                        gh api "repos/subhankar12-spec/boutique-gitops/pulls/$PR_NUMBER" > latest-pr.json
                        python3 - <<'CURRENT'
import json,os
p=json.load(open("latest-pr.json"))
if p.get("state")!="open" or p["head"]["sha"]!=os.environ["POLICY_HEAD_SHA"] or p["base"]["sha"]!=os.environ["POLICY_BASE_SHA"]:
    raise SystemExit("PR changed during verification; run the policy job again")
CURRENT
                    '''
                }
            }
        }
    }
    post {
        success {
            withCredentials([string(credentialsId:'gitops-checks',variable:'GH_TOKEN')]) {
                sh '''set +x
                    gh api --method POST "repos/subhankar12-spec/boutique-gitops/statuses/$POLICY_HEAD_SHA" -f state=success -f context='boutique/gitops-policy' -f description='Signed release, deployment evidence and manifests verified' -f target_url="$BUILD_URL" >/dev/null
                '''
            }
        }
        unsuccessful {
            script {
                if (env.POLICY_HEAD_SHA ==~ /[a-f0-9]{40}/) {
                    withCredentials([string(credentialsId:'gitops-checks',variable:'GH_TOKEN')]) {
                        sh returnStatus:true,script:'''set +x
                            gh api --method POST "repos/subhankar12-spec/boutique-gitops/statuses/$POLICY_HEAD_SHA" -f state=failure -f context='boutique/gitops-policy' -f description='Delivery policy failed or request became stale' -f target_url="$BUILD_URL" >/dev/null
                        '''
                    }
                }
            }
        }
        always { archiveArtifacts artifacts:'reports/*.yaml,pr-head-sha.txt,pr-base-sha.txt',allowEmptyArchive:true }
    }
}
