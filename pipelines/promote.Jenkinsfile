pipeline {
    agent { label 'trusted-deploy' }
    options { timestamps(); timeout(time: 30, unit: 'MINUTES'); disableConcurrentBuilds(); skipDefaultCheckout(true) }
    parameters {
        choice(name: 'TARGET', choices: ['dev','staging','production'])
        choice(name: 'SERVICE', choices: ['frontend','catalogue','cart','orders'])
        string(name: 'IMAGE', defaultValue: '', description: 'ghcr.io/subhankar12-spec/boutique-service@sha256:... from release build')
    }
    stages {
        stage('Validate') {
            steps { script {
                def prefix="ghcr.io/subhankar12-spec/boutique-${params.SERVICE}@sha256:"
                if (!params.IMAGE.startsWith(prefix) || !(params.IMAGE.substring(prefix.length()) ==~ /[a-f0-9]{64}/)) { error('Expected service image digest') }
            } }
        }
        stage('Checkout deployment configuration') {
            steps {
                deleteDir()
                checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-gitops.git',credentialsId:'github-read']]])
            }
        }
        stage('Verify promotion chain') {
            steps {
                sh '''set -eu
                    python3 scripts/promote.py "$SERVICE" "$TARGET" "$IMAGE"
                    ./scripts/validate.sh
                '''
            }
        }
        stage('Production gate') {
            when { expression { params.TARGET == 'production' } }
            steps {
                // Configure this group in Jenkins authorization. Jenkins admins can still override gates.
                input message: 'Confirm staging smoke tests passed for this release and review migration compatibility.', submitter: 'release-managers'
            }
        }
        stage('Open reviewed GitOps PR') {
            steps {
                withCredentials([usernamePassword(credentialsId:'gitops-pr',usernameVariable:'GIT_USER',passwordVariable:'GH_TOKEN')]) {
                    sh '''set +x
                        set -eu
                        BRANCH="promote/$TARGET/$SERVICE/$BUILD_NUMBER"
                        git config user.name 'boutique-jenkins'
                        git config user.email 'boutique-jenkins@users.noreply.github.com'
                        git checkout -b "$BRANCH"
                        git add services
                        if git diff --cached --quiet; then echo 'Image already selected'; exit 0; fi
                        git commit -m "Promote $SERVICE to $TARGET"
                        ASKPASS=$(mktemp)
                        trap 'rm -f "$ASKPASS"' EXIT
                        printf '#!/bin/sh\ncase "$1" in *Username*) printf "%%s" "$GIT_USER";; *) printf "%%s" "$GH_TOKEN";; esac\n' > "$ASKPASS"
                        chmod 700 "$ASKPASS"
                        GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 git push origin "$BRANCH"
                        gh pr create --base main --head "$BRANCH" --title "Promote $SERVICE to $TARGET" --body "Digest: $IMAGE. Jenkins build: $BUILD_URL. Review staging evidence and migrations before merging."
                    '''
                }
            }
        }
    }
}
