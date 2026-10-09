@Library('boutique-ci') _
pipeline {
    agent none
    options { timestamps();timeout(time:60,unit:'MINUTES');disableConcurrentBuilds();skipDefaultCheckout(true);lock(resource:"boutique-delivery-${params.TARGET}") }
    parameters {
        choice(name:'TARGET',choices:['dev','staging','production'])
        string(name:'FRONTEND_BUILD',defaultValue:'',description:'Published protected-main frontend release build')
        string(name:'CATALOGUE_BUILD',defaultValue:'',description:'Published protected-main catalogue release build')
        string(name:'CART_BUILD',defaultValue:'',description:'Published protected-main cart release build')
        string(name:'ORDERS_BUILD',defaultValue:'',description:'Published protected-main orders release build')
        string(name:'FRONTEND_EVIDENCE_BUILD',defaultValue:'',description:'For staging/prod: trusted preceding-environment frontend verification build')
        string(name:'CATALOGUE_EVIDENCE_BUILD',defaultValue:'',description:'For staging/prod: trusted preceding-environment catalogue verification build')
        string(name:'CART_EVIDENCE_BUILD',defaultValue:'',description:'For staging/prod: trusted preceding-environment cart verification build')
        string(name:'ORDERS_EVIDENCE_BUILD',defaultValue:'',description:'For staging/prod: trusted preceding-environment orders verification build')
        booleanParam(name:'AUTO_MERGE_DEV',defaultValue:true,description:'Enable auto merge after required policy and review checks')
        booleanParam(name:'PAUSE_FOR_INITIAL_SYNC',defaultValue:true,description:'Pause after GitOps merge so the operator can create the initial target Argo Application')
    }
    stages {
        stage('Prepare initial complete application') {
            agent { label 'trusted-deploy' }
            stages {
                stage('Validate release build numbers') {
                    steps { script {
                        if (!(params.TARGET in ['dev','staging','production'])) { error('Invalid bootstrap environment') }
                        for (name in ['FRONTEND_BUILD','CATALOGUE_BUILD','CART_BUILD','ORDERS_BUILD']) {
                            if (!(params[name] ==~ /[1-9][0-9]*/)) { error("Expected published release build: ${name}") }
                        }
                        if (params.TARGET != 'dev') {
                            for (name in ['FRONTEND_EVIDENCE_BUILD','CATALOGUE_EVIDENCE_BUILD','CART_EVIDENCE_BUILD','ORDERS_EVIDENCE_BUILD']) {
                                if (!(params[name] ==~ /[1-9][0-9]*/)) { error("Expected trusted preceding-environment verification build: ${name}") }
                            }
                        }
                    } }
                }
                stage('Checkout protected deployment tools') {
                    steps {
                        deleteDir()
                        checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
                        sh 'if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; else git fetch origin main; fi'
                    }
                }
                stage('Collect four signed releases') {
                    steps { script {
                        def images = [:]
                        for (service in ['frontend','catalogue','cart','orders']) {
                            def buildParameter = "${service.toUpperCase()}_BUILD".toString()
                            def release = releaseArtifact(service:service,build:params[buildParameter],target:"release-artifacts/${service}")
                            images[service] = release.image
                        }
                        env.BOOTSTRAP_IMAGES_JSON = groovy.json.JsonOutput.toJson(images)
                        writeFile file:'bootstrap-images.json',text:env.BOOTSTRAP_IMAGES_JSON + '\n'
                    } }
                }
                stage('Collect four preceding-environment verifications') {
                    when { expression { params.TARGET != 'dev' } }
                    steps { script {
                        for (service in ['frontend','catalogue','cart','orders']) {
                            def evidenceParameter = "${service.toUpperCase()}_EVIDENCE_BUILD".toString()
                            copyArtifacts projectName:'boutique-verify',selector:specific(params[evidenceParameter]),filter:'verification-evidence.json',target:"evidence/${service}",flatten:true
                        }
                    } }
                }
                stage('Plan aggregate verified release') {
                    steps {
                        withCredentials([file(credentialsId:'release-artifact-public-key',variable:'RELEASE_PUBLIC_KEY'),file(credentialsId:'release-evidence-public-key',variable:'EVIDENCE_PUBLIC_KEY')]) {
                            script {
                                def images = new groovy.json.JsonSlurperClassic().parseText(env.BOOTSTRAP_IMAGES_JSON)
                                for (service in ['frontend','catalogue','cart','orders']) {
                                    withEnv(["BOOTSTRAP_SERVICE=${service}","BOOTSTRAP_IMAGE=${images[service]}"]) {
                                        sh '''set -eu
                                            if [ "$TARGET" = dev ]; then
                                                python3 scripts/promote.py "$BOOTSTRAP_SERVICE" "$TARGET" "$BOOTSTRAP_IMAGE" --release-record "release-artifacts/$BOOTSTRAP_SERVICE/release-attestation.json" --release-public-key "$RELEASE_PUBLIC_KEY"
                                            else
                                                python3 scripts/promote.py "$BOOTSTRAP_SERVICE" "$TARGET" "$BOOTSTRAP_IMAGE" --release-record "release-artifacts/$BOOTSTRAP_SERVICE/release-attestation.json" --release-public-key "$RELEASE_PUBLIC_KEY" --evidence "evidence/$BOOTSTRAP_SERVICE/verification-evidence.json" --public-key "$EVIDENCE_PUBLIC_KEY" --max-age-hours 24
                                            fi
                                        '''
                                    }
                                }
                            }
                        }
                        sh '''set -eu
                            ./scripts/validate.sh
                            python3 scripts/check_rendered_images.py
                            git add services promotionrecords
                            git diff --cached > planned-change.diff
                        '''
                        archiveArtifacts artifacts:'bootstrap-images.json,planned-change.diff,release-artifacts/*/release.json,release-artifacts/*/release-attestation.json',fingerprint:true
                    }
                }
                stage('Production bootstrap approval') {
                    when { expression { params.TARGET == 'production' } }
                    steps { input message:'Review all four signed releases, staging verification evidence, planned production diff and database migration compatibility before approving the initial production release.',submitter:'release-manager' }
                }
                stage('Merge reviewed bootstrap') {
                    steps { gitopsPullRequest(action:'bootstrap',service:'all',target:params.TARGET,image:env.BOOTSTRAP_IMAGES_JSON,autoMerge:params.TARGET == 'dev' && params.AUTO_MERGE_DEV) }
                }
            }
            post { always { archiveArtifacts artifacts:'bootstrap-images.json,planned-change.diff,gitops-pr-url.txt',allowEmptyArchive:true } }
        }
        stage('Initialize the first Argo CD synchronization') {
            when { expression { params.PAUSE_FOR_INITIAL_SYNC } }
            steps {
                input message:"The complete ${params.TARGET} release is merged. Run production-lab.py deploy --environment ${params.TARGET} to create boutique-${params.TARGET}, then continue after Argo CD starts synchronizing.",submitter:'release-manager'
            }
        }
        stage('Verify each deployed image') {
            steps { script {
                def images = new groovy.json.JsonSlurperClassic().parseText(env.BOOTSTRAP_IMAGES_JSON)
                for (service in ['frontend','catalogue','cart','orders']) {
                    build job:'boutique-verify',wait:true,propagate:true,parameters:[
                        string(name:'TARGET',value:params.TARGET),string(name:'SERVICE',value:service),
                        string(name:'IMAGE',value:images[service]),string(name:'GITOPS_COMMIT',value:env.MERGED_GITOPS_COMMIT)
                    ]
                }
            } }
        }
    }
}
