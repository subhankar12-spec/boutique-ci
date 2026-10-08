pipeline {
    agent { label 'trusted-release' }
    options { timestamps(); timeout(time: 45, unit: 'MINUTES'); disableConcurrentBuilds(); skipDefaultCheckout(true) }
    parameters {
        choice(name: 'SERVICE', choices: ['frontend','catalogue','cart','orders'])
        string(name: 'COMMIT', defaultValue: '', description: 'Reviewed full source commit SHA')
    }
    environment {
        GITHUB_OWNER = 'subhankar12-spec'
        REGISTRY = 'ghcr.io'
    }
    stages {
        stage('Validate request') {
            steps {
                script {
                    if (!(params.COMMIT ==~ /[a-f0-9]{40}/)) { error('Full commit SHA required') }
                    if (!(params.SERVICE in ['frontend','catalogue','cart','orders'])) { error('Invalid service') }
                }
            }
        }
        stage('Checkout reviewed source') {
            steps {
                deleteDir()
                checkout([$class: 'GitSCM', branches: [[name: params.COMMIT]], userRemoteConfigs: [[url: "https://github.com/${env.GITHUB_OWNER}/boutique-${params.SERVICE}.git", credentialsId: 'github-read']]])
                sh '''set -eu
                    git fetch origin main
                    git merge-base --is-ancestor "$COMMIT" origin/main
                    test "$(git rev-parse HEAD)" = "$COMMIT"
                '''
            }
        }
        stage('Source secret scan') {
            steps { sh 'trivy fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL .' }
        }
        stage('AWS trust bundle') {
            when { expression { params.SERVICE == 'orders' } }
            steps { sh 'test -s certs/global-bundle.pem || { echo "Fetch, review and commit the RDS CA bundle before releasing orders"; exit 1; }' }
        }
        stage('Build, test and scan release artifact') {
            steps {
                sh '''set -eu
                    export DOCKER_CONFIG="$WORKSPACE/.docker"
                    mkdir -p "$DOCKER_CONFIG"
                    docker build --pull --tag "$REGISTRY/$GITHUB_OWNER/boutique-$SERVICE:$COMMIT" .
                    trivy image --format json --output image-scan.json --ignorefile .trivyignore.yaml --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$REGISTRY/$GITHUB_OWNER/boutique-$SERVICE:$COMMIT"
                    syft "$REGISTRY/$GITHUB_OWNER/boutique-$SERVICE:$COMMIT" -o cyclonedx-json=sbom.json
                '''
            }
        }
        stage('Publish the tested image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-publish', usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_TOKEN')]) {
                    sh '''set +x
                        set -eu
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        printf '%s' "$REGISTRY_TOKEN" | docker login "$REGISTRY" -u "$REGISTRY_USER" --password-stdin
                        IMAGE="$REGISTRY/$GITHUB_OWNER/boutique-$SERVICE:$COMMIT"
                        docker push "$IMAGE"
                        docker inspect --format '{{index .RepoDigests 0}}' "$IMAGE" > image-digest.txt
                        docker logout "$REGISTRY"
                    '''
                }
            }
        }
        stage('Record release') {
            steps { archiveArtifacts artifacts: 'image-digest.txt,sbom.json,image-scan.json', fingerprint: true }
        }
    }
    post { always { archiveArtifacts artifacts: 'image-scan.json', allowEmptyArchive: true; sh 'rm -rf .docker' } }
}
