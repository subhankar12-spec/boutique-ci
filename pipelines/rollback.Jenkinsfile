@Library('boutique-ci') _
pipeline {
    agent none
    options { timestamps(); timeout(time:45,unit:'MINUTES');disableConcurrentBuilds();skipDefaultCheckout(true); lock(resource: "boutique-delivery-${params.TARGET}") }
    parameters {
        choice(name:'TARGET',choices:['dev','staging','production'])
        choice(name:'SERVICE',choices:['frontend','catalogue','cart','orders'])
        string(name:'IMAGE',defaultValue:'',description:'Previous tested digest to restore')
        string(name:'RELEASE_BUILD',defaultValue:'',description:'Original successful service/main release build')
        string(name:'EVIDENCE_BUILD',defaultValue:'',description:'Successful same-environment verification of the previous digest')
    }
    stages {
        stage('Prepare and merge delivery change') {
            agent { label 'trusted-deploy' }
            stages {
                stage('Validate request') {
                    steps { script {
                            if (!(params.TARGET in ['dev','staging','production']) || !(params.SERVICE in ['frontend','catalogue','cart','orders'])) { error('Invalid target/service') }
                            if (!(params.EVIDENCE_BUILD ==~ /[1-9][0-9]*/)) { error('Previous successful verification build is required') }
                            if (!(params.IMAGE ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/) || !params.IMAGE.startsWith("ghcr.io/subhankar12-spec/boutique-${params.SERVICE}@")) { error('Expected service digest') }
                        } }
                }
                stage('Checkout trusted GitOps source') {
                    steps {
                        deleteDir()
                        checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                        sh 'if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; else git fetch origin main; fi'
                    }
                }
                stage('Verify original release') { steps { releaseArtifact() } }
                stage('Retrieve previous verification') { steps { copyArtifacts projectName:'boutique-verify',selector:specific(params.EVIDENCE_BUILD),filter:'verification-evidence.json',target:'evidence',flatten:true } }
                stage('Plan recovery') {
                    steps {
                        withCredentials([file(credentialsId:'release-evidence-public-key',variable:'EVIDENCE_PUBLIC_KEY'),file(credentialsId:'release-artifact-public-key',variable:'RELEASE_PUBLIC_KEY')]) {
                            sh '''set -eu
                        CURRENT_IMAGE=$(python3 -c 'import sys;sys.path.insert(0,"scripts");from evidence import ROOT,selected_image;import os;print(selected_image(ROOT,os.environ["SERVICE"],os.environ["TARGET"]))')
                        python3 scripts/rollback.py "$SERVICE" "$TARGET" "$IMAGE" --chart-package release-artifacts/chart.tgz --release-record release-artifacts/release-attestation.json --release-public-key "$RELEASE_PUBLIC_KEY" --evidence evidence/verification-evidence.json --public-key "$EVIDENCE_PUBLIC_KEY" --current-image "$CURRENT_IMAGE" --max-age-hours 720
                        ./scripts/validate.sh
                        git add environments promotionrecords
                        git diff --cached -- environments promotionrecords > planned-change.diff
                    '''
                        }
                        archiveArtifacts artifacts:'planned-change.diff,release-artifacts/release.json,release-artifacts/release-attestation.json',fingerprint:true
                    }
                }
                stage('Production recovery approval') {
                    when { expression { params.TARGET == 'production' } }
                    steps { input message:'Review previous verification, database migration compatibility, and the rollback diff. Authorize production recovery.',submitter:'release-manager' }
                }
                stage('Merge reviewed recovery') { steps { gitopsPullRequest(action:'rollback') } }

            }
            post { always { archiveArtifacts artifacts:'planned-change.diff,gitops-pr-url.txt',allowEmptyArchive:true } }
        }
        stage('Verify recovered deployment') {
            steps { build job:'boutique-verify',wait:true,propagate:true,parameters:[string(name:'TARGET',value:params.TARGET),string(name:'SERVICE',value:params.SERVICE),string(name:'IMAGE',value:params.IMAGE),string(name:'GITOPS_COMMIT',value:env.MERGED_GITOPS_COMMIT)] }
        }
    }

}
