# Jenkins delivery

This repository provides the shared service pipeline, two-controller configuration and the common deployment jobs for the Boutique project. Each application service remains in its own repository. See [Jenkins setup](../boutique-platform/docs/jenkins-setup.md) for installation, credentials and branch protection.

`vars/servicePipeline.groovy` supplies both PR validation and protected-main release behavior. Validation runs on disposable agents attached to the validation controller. Protected-main builds run on the release controller, execute Dockerfile tests, scan source secrets and the runtime image, generate a CycloneDX SBOM, then package/publish the service-owned Helm chart and tested image to GHCR and sign their combined release record. Fixable HIGH/CRITICAL findings fail the image gate; unfixed findings remain visible for review. Source-SHA tags are not overwritten.

Four application services normally trigger dev delivery after publication once a complete digest-based dev baseline exists. For the first complete installation, build each with `DELIVER_TO_DEV=false`, then use `boutique-bootstrap` to collect their signed artifacts and create one complete dev change. Initialize staging and production through the same aggregate job with matching preceding-environment evidence before switching those environments to individual service promotion. `boutique-platform/main` also releases the incident adapter, including its real PostgreSQL queue integration tests; monitoring image selection remains an explicit reviewed GitOps change.

## Shared jobs

| Job | Behavior |
|---|---|
| `boutique-bootstrap` | Collect four signed releases and preceding-environment evidence, prepare an initial environment PR and verify each digest |
| `boutique-promote` | Verify release provenance and preceding-environment evidence, review production approval, merge a GitOps PR and verify rollout |
| `boutique-gitops-check` | Use protected tools to validate signed delivery records, current PR base and rendered resources; publish `boutique/gitops-policy` |
| `boutique-verify` | Wait for Argo/rollouts, check native runtime images, run HTTPS smoke tests and sign measured evidence |
| `boutique-rollback` | Restore a previously verified same-environment digest through a reviewed GitOps PR and fresh verification |
| `boutique-infrastructure` | Plan Terraform, archive a redacted action summary and optionally approve/apply the exact private saved plan |

The release and delivery parents use `agent none` outside their working stages, freeing executors before waiting for deployment verification. Promotion and rollback hold a common environment lock through verification. The policy job uses a separate `policy-check` executor while the deployment workspace waits for it. Provision that executor; assigning policy work to the occupied deploy executor would deadlock.

The two controllers form the credential boundary. PR-controlled code runs only on validation, which receives no registry publishing, GitOps write, private signing or cloud credentials. The shared library is configured as an untrusted Jenkins library with a reviewed pinned commit and version overrides disabled. Protect its source, trusted job definitions and policy tools with review and CODEOWNERS.

## Runtime and plugin maintenance

The controller pins Jenkins 2.580.1 and a checksum-locked set of 74 plugin artifacts. `jenkins/scripts/install-locked-plugins.py` verifies official SHA-256 values, rather than resolving floating plugin versions during every build. Review and exercise dependency upgrades before regenerating the lock with `scripts/lock-plugins.py`.

```bash
python3 jenkins/scripts/doctor.py
install -d -m 700 /tmp/boutique-jenkins-check
python3 jenkins/scripts/smoke-controller.py --directory /tmp/boutique-jenkins-check
```

The repeatable smoke command requires Java 21, a private external cache and official archive access. It checks the locked runtime, JCasC, plugin-backed Declarative/Job DSL behavior and isolated job coordination without real GitHub/cloud credentials. These checks have been exercised on the cloud runner. They do not establish GHCR publication, remote agent provisioning or Jenkins-to-cluster delivery. See [validation evidence](../boutique-platform/docs/validation.md) for the remaining acceptance work.

Helm chart packages and OCI digests are bound to the source commit in version-2 release attestations. Promotions move the exact signed package and image together; see [Helm delivery](../boutique-gitops/docs/helm-delivery.md). The infrastructure job also supports the once-per-account `audit` Terraform root.
