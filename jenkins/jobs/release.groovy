// The Pipeline seed defaults to manual builds while initial delivery is configured.
def suppressBuilds = binding.hasVariable('suppressAutomaticBuilds') && binding.getVariable('suppressAutomaticBuilds')
['frontend','catalogue','cart','orders','platform'].each { service ->
 multibranchPipelineJob("boutique-${service}") {
  branchSources {
   github {
    id("boutique-${service}-trusted-main")
    repoOwner('subhankar12-spec')
    repository("boutique-${service}")
    scanCredentialsId('github-read')
    buildOriginBranch(true)
    buildOriginPRMerge(false)
    buildForkPRMerge(false)
   }
  }
  configure { node ->
   def sourceNode = node / sources / data / 'jenkins.branch.BranchSource' / source
   // Explicit traits bypass GitHubSCMSource's legacy discovery migration.
   // Keep branch discovery alongside the main filter and remove obsolete flags.
   sourceNode.children().removeAll { child -> child instanceof groovy.util.Node && child.name() in [
    'includes','excludes','buildOriginBranch','buildOriginBranchWithPR',
    'buildOriginPRMerge','buildOriginPRHead','buildForkPRMerge','buildForkPRHead'
   ] }
   def traits = sourceNode / traits
   traits << 'org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait' { strategyId(3) }
   traits << 'jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait' { includes('main'); excludes('') }
   if (suppressBuilds) {
    def branchSource = node / sources / data / 'jenkins.branch.BranchSource'
    branchSource.children().removeAll { child -> child instanceof groovy.util.Node && child.name() == 'strategy' }
    branchSource.appendNode('strategy', [class: 'jenkins.branch.DefaultBranchPropertyStrategy'])
      .appendNode('properties').appendNode('jenkins.branch.NoTriggerBranchProperty')
      .appendNode('triggeredBranchesRegex', '^$')
   }
  }
  orphanedItemStrategy { discardOldItems { numToKeep(10) } }
  triggers { periodicFolderTrigger { interval('1d') } }
 }
}
['promote','verify','rollback','gitops-validate'].each { job ->
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
pipelineJob('boutique-infrastructure') {
 definition { cpsScm {
  scm { git { remote { url('https://github.com/subhankar12-spec/boutique-infrastructure.git'); credentials('github-read') }; branch('*/main') } }
  scriptPath('Jenkinsfile'); lightweight(true)
 } }
}
