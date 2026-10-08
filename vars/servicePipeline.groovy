def call(Map config) {
    if (!(config.service in ['frontend', 'catalogue', 'cart', 'orders'])) {
        error('Unsupported service')
    }
    pipeline {
        agent { label 'isolated-builder' }
        options {
            timestamps()
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
        }
        environment { SERVICE = "${config.service}" }
        stages {
            stage('Checkout') {
                steps { deleteDir(); checkout scm }
            }
            stage('Source secret scan') {
                steps { sh 'trivy fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL .' }
            }
            stage('Test and build') {
                steps {
                    sh '''
                        set -eu
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        mkdir -p "$DOCKER_CONFIG"
                        IMAGE="boutique-$SERVICE:$(git rev-parse HEAD)"
                        docker build --pull --tag "$IMAGE" .
                        printf '%s\n' "$IMAGE" > image.txt
                    '''
                }
            }
            stage('Security and SBOM') {
                steps {
                    sh '''
                        set -eu
                        IMAGE=$(cat image.txt)
                        trivy image --format json --output image-scan.json --ignorefile .trivyignore.yaml --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$IMAGE"
                        syft "$IMAGE" -o cyclonedx-json=sbom.json
                    '''
                }
            }
        }
        post {
            always {
                archiveArtifacts artifacts: 'image.txt,sbom.json,image-scan.json', allowEmptyArchive: true
                sh 'rm -rf .docker'
            }
        }
    }
}
