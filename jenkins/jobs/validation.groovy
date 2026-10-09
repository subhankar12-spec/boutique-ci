['frontend','catalogue','cart','orders','platform','gitops'].each { service ->
 multibranchPipelineJob("boutique-${service}") {
  branchSources {
   github {
    id("boutique-${service}")
    repoOwner('subhankar12-spec')
    repository("boutique-${service}")
    scanCredentialsId('github-read')
    buildOriginBranch(true)
    buildOriginPRMerge(true)
    buildForkPRMerge(false)
   }
  }
  orphanedItemStrategy { discardOldItems { numToKeep(10) } }
  triggers { periodicFolderTrigger { interval('1d') } }
 }
}
