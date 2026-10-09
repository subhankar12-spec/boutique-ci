def call(Map config) {
    def action = config.action
    def service = (config.service ?: params.SERVICE)?.toString()
    def target = (config.target ?: params.TARGET)?.toString()
    def image = (config.image ?: params.IMAGE ?: 'See signed delivery records for all four images').toString()
    def autoMerge = config.containsKey('autoMerge') ? config.autoMerge : params.AUTO_MERGE_DEV
    if (!(action in ['promote','rollback','bootstrap'])) { error('Unsupported GitOps action') }
    if (!(service in ['frontend','catalogue','cart','orders','all']) || !(target in ['dev','staging','production'])) { error('Invalid GitOps service/environment') }
    if ((service == 'all') != (action == 'bootstrap')) { error('Bootstrap requires an aggregate four-service release') }
    if (autoMerge && target != 'dev') { error('Automatic merge is limited to dev') }
    writeFile file:'gitops-pr-body.md',text:"""${action.capitalize()} ${service} in ${target}.

Immutable image(s): ${image}
Release build: ${params.RELEASE_BUILD ?: 'See signed per-service records'}
Verification build: ${params.EVIDENCE_BUILD ?: 'See per-service signed records; none required for dev'}
Jenkins execution: ${env.BUILD_URL}

Review the selected digests, signed delivery records, rendered manifests and migration compatibility. The required boutique/gitops-policy check validates the current PR base using protected tools.
"""
    withCredentials([usernamePassword(credentialsId:'gitops-pr',usernameVariable:'GIT_USER',passwordVariable:'GH_TOKEN')]) {
        withEnv(["GITOPS_ACTION=${action}","DELIVERY_SERVICE=${service}","DELIVERY_TARGET=${target}"]) {
            sh '''set +x
                set -eu
                # Check server protection for every delivery, including manual prod merges.
                gh api repos/subhankar12-spec/boutique-gitops/branches/main/protection > branch-protection.json
                python3 - <<'PROTECTION'
import json
p=json.load(open("branch-protection.json"));checks=p.get("required_status_checks") or {}
contexts=set(checks.get("contexts") or [])|{c.get("context") for c in checks.get("checks",[])}
if "boutique/gitops-policy" not in contexts or checks.get("strict") is not True or (p.get("enforce_admins") or {}).get("enabled") is not True:
    raise SystemExit("GitOps main requires boutique/gitops-policy, strict up-to-date checks and administrator enforcement")
reviews=p.get("required_pull_request_reviews") or {}
if reviews.get("require_code_owner_reviews") is not True or reviews.get("dismiss_stale_reviews") is not True:
    raise SystemExit("GitOps main requires CODEOWNER approval and dismissal of stale reviews")
PROTECTION
                BRANCH="$GITOPS_ACTION/$DELIVERY_TARGET/$DELIVERY_SERVICE/$BUILD_NUMBER"
                git config user.name boutique-jenkins
                git config user.email boutique-jenkins@users.noreply.github.com
                git checkout -b "$BRANCH"
                git add services promotionrecords
                if git diff --cached --quiet; then
                    echo 'No GitOps change; use verification for the current deployment.'
                    exit 1
                fi
                git commit -m "$GITOPS_ACTION $DELIVERY_SERVICE in $DELIVERY_TARGET"
                ASKPASS=$(mktemp)
                trap 'rm -f "$ASKPASS"' EXIT
                printf '#!/bin/sh\ncase "$1" in *Username*) printf "%%s" "$GIT_USER";; *) printf "%%s" "$GH_TOKEN";; esac\n' > "$ASKPASS"
                chmod 700 "$ASKPASS"
                GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 git push origin "$BRANCH"
                gh pr create --repo subhankar12-spec/boutique-gitops --base main --head "$BRANCH" --title "$GITOPS_ACTION $DELIVERY_SERVICE in $DELIVERY_TARGET" --body-file gitops-pr-body.md > gitops-pr-url.txt
                gh pr view --repo subhankar12-spec/boutique-gitops "$(cat gitops-pr-url.txt)" --json number --jq .number > gitops-pr-number.txt
            '''
        }
    }
    def prNumber = readFile('gitops-pr-number.txt').trim()
    if (!(prNumber ==~ /[1-9][0-9]*/)) { error('Missing GitOps PR number') }
    // The checker needs a separate policy-check executor while this deployment
    // workspace remains allocated. Its definition and tools never come from a PR.
    try {
        build job:'boutique-gitops-check',wait:true,propagate:true,parameters:[string(name:'PR_NUMBER',value:prNumber)]
        withCredentials([usernamePassword(credentialsId:'gitops-pr',usernameVariable:'GIT_USER',passwordVariable:'GH_TOKEN')]) {
            if (autoMerge) {
                sh '''set +x
                    set -eu
                    gh pr merge --repo subhankar12-spec/boutique-gitops --auto --squash "$(cat gitops-pr-url.txt)"
                '''
            }
            timeout(time:20,unit:'MINUTES') {
                waitUntil(initialRecurrencePeriod:5000) {
                    sh '''set +x; gh pr view --repo subhankar12-spec/boutique-gitops "$(cat gitops-pr-url.txt)" --json state,mergeCommit > pr-state.json'''
                    def state = new groovy.json.JsonSlurperClassic().parseText(readFile('pr-state.json'))
                    if (state.state == 'CLOSED') { error('GitOps PR was closed without deployment') }
                    if (state.state == 'MERGED') {
                        env.MERGED_GITOPS_COMMIT = state.mergeCommit?.oid
                        if (!(env.MERGED_GITOPS_COMMIT ==~ /[a-f0-9]{40}/)) { error('Missing reviewed GitOps merge commit') }
                        return true
                    }
                    return false
                }
            }
        }
    } catch (failure) {
        // Expired/failed requests cannot leave a green stale PR awaiting a later
        // manual merge. Close only the PR this build created; never revert merges.
        withCredentials([usernamePassword(credentialsId:'gitops-pr',usernameVariable:'GIT_USER',passwordVariable:'GH_TOKEN')]) {
            sh returnStatus:true,script:'''set +x
                STATE=$(gh pr view --repo subhankar12-spec/boutique-gitops "$(cat gitops-pr-url.txt)" --json state --jq .state) || exit 0
                if [ "$STATE" = OPEN ]; then gh pr close --repo subhankar12-spec/boutique-gitops "$(cat gitops-pr-url.txt)"; fi
            '''
        }
        throw failure
    }
    return env.MERGED_GITOPS_COMMIT
}
