@Library('boutique-ci') _
pipeline {
    agent none
    options { timestamps(); timeout(time: 45, unit: 'MINUTES'); disableConcurrentBuilds(); skipDefaultCheckout(true); lock(resource: "boutique-delivery-${params.TARGET}") }
    parameters {
        choice(name:'TARGET',choices:['dev','staging','production'])
        choice(name:'SERVICE',choices:['frontend','catalogue','cart','orders'])
        string(name:'IMAGE',defaultValue:'',description:'Immutable digest recorded by a successful main build')
        string(name:'RELEASE_BUILD',defaultValue:'',description:'Source service/main build number; artifact source is fixed')
        string(name:'EVIDENCE_BUILD',defaultValue:'',description:'Trusted boutique-verify build for preceding environment')
        booleanParam(name:'AUTO_MERGE_DEV',defaultValue:false,description:'Dev only; requires GitOps protected-main checks and repository auto-merge enabled')
    }
    stages {
        stage('Prepare and merge delivery change') {
            agent { label 'trusted-deploy' }
            stages {
                stage('Validate request') {
                    steps { script {
                            if (!(params.TARGET in ['dev','staging','production']) || !(params.SERVICE in ['frontend','catalogue','cart','orders'])) { error('Invalid target/service') }
                            if (!(params.IMAGE ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/) || !params.IMAGE.startsWith("ghcr.io/subhankar12-spec/boutique-${params.SERVICE}@")) { error('Expected service image digest') }
                            if (params.AUTO_MERGE_DEV && params.TARGET != 'dev') { error('Automatic merging is limited to dev') }
                            if (params.TARGET != 'dev' && !(params.EVIDENCE_BUILD ==~ /[1-9][0-9]*/)) { error('Preceding-environment verification build is required') }
                        } }
                }
                stage('Checkout trusted GitOps source') {
                    steps {
                        deleteDir()
                        checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                        sh 'if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; else git fetch origin main; fi'
                    }
                }
                stage('Verify published release') { steps { releaseArtifact() } }
                stage('Retrieve trusted verification') {
                    when { expression { params.TARGET != 'dev' } }
                    steps { copyArtifacts projectName:'boutique-verify', selector:specific(params.EVIDENCE_BUILD), filter:'verification-evidence.json', target:'evidence', flatten:true }
                }
                stage('Plan verified promotion') {
                    steps {
                        script {
                            withCredentials([file(credentialsId:'release-artifact-public-key',variable:'RELEASE_PUBLIC_KEY')]) {
                                if (params.TARGET == 'dev') {
                                    sh 'python3 scripts/promote.py "$SERVICE" "$TARGET" "$IMAGE" --chart-package release-artifacts/chart.tgz --release-record release-artifacts/release-attestation.json --release-public-key "$RELEASE_PUBLIC_KEY"'
                                } else {
                                    withCredentials([file(credentialsId:'release-evidence-public-key',variable:'EVIDENCE_PUBLIC_KEY')]) {
                                        sh 'python3 scripts/promote.py "$SERVICE" "$TARGET" "$IMAGE" --chart-package release-artifacts/chart.tgz --release-record release-artifacts/release-attestation.json --release-public-key "$RELEASE_PUBLIC_KEY" --evidence evidence/verification-evidence.json --public-key "$EVIDENCE_PUBLIC_KEY" --max-age-hours 24'
                                    }
                                }
                            }
                        }
                        sh './scripts/validate.sh && git add environments promotionrecords && git diff --cached -- environments promotionrecords > planned-change.diff'
                        archiveArtifacts artifacts:'planned-change.diff,release-artifacts/release.json,release-artifacts/release-attestation.json',fingerprint:true
                    }
                }
                stage('Production approval') {
                    when { expression { params.TARGET == 'production' } }
                    steps { input message:'Review the planned diff, signed staging verification and migration compatibility before production promotion.',submitter:'release-manager' }
                }
                stage('Merge reviewed GitOps change') { steps { gitopsPullRequest(action:'promote') } }

            }
            post { always { archiveArtifacts artifacts:'planned-change.diff,gitops-pr-url.txt,release-artifacts/release.json,release-artifacts/release-attestation.json',allowEmptyArchive:true } }
        }
        stage('Verify synchronized deployment') {
            steps { build job:'boutique-verify',wait:true,propagate:true,parameters:[string(name:'TARGET',value:params.TARGET),string(name:'SERVICE',value:params.SERVICE),string(name:'IMAGE',value:params.IMAGE),string(name:'GITOPS_COMMIT',value:env.MERGED_GITOPS_COMMIT)] }
        }
    }

}
