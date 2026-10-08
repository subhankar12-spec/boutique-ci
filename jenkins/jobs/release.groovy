['release','promote','verify'].each { job ->
 pipelineJob("boutique-${job}") {
  definition {
   cpsScm {
    scm { git { remote { url('https://github.com/subhankar12-spec/boutique-ci.git'); credentials('github-read') }; branch('*/main') } }
    scriptPath("pipelines/${job}.Jenkinsfile")
    lightweight(true)
   }
  }
 }
}
