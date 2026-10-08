pipeline {
 agent { label 'trusted-deploy' }
 options { timestamps(); timeout(time:15,unit:'MINUTES'); disableConcurrentBuilds(); skipDefaultCheckout(true) }
 parameters { choice(name:'TARGET',choices:['dev','staging','production']) }
 stages {
  stage('Checkout smoke tests') {
   steps { deleteDir(); checkout([$class:'GitSCM',branches:[[name:'main']],userRemoteConfigs:[[url:'https://github.com/subhankar12-spec/boutique-platform.git',credentialsId:'github-read']]]) }
  }
  stage('Verify deployed release') {
   steps {
    script {
     def origins=[dev:'https://dev.boutique.example.com',staging:'https://staging.boutique.example.com',production:'https://production.boutique.example.com']
     withEnv(["BASE_URL=${origins[params.TARGET]}"]) {
      withCredentials([file(credentialsId:"kubeconfig-${params.TARGET}",variable:'KUBECONFIG')]) {
       sh '''set -eu
        for service in frontend catalogue cart orders; do
          kubectl -n "boutique-$TARGET" rollout status "deployment/$service" --timeout=180s
        done
        python3 tests/smoke/smoke.py
        kubectl -n "boutique-$TARGET" get deployments -o json > deployed-release.json
       '''
      }
     }
    }
   }
  }
 }
 post { success { archiveArtifacts artifacts:'deployed-release.json',fingerprint:true } }
}
