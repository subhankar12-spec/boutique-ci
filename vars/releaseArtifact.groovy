def call(Map config = [:]) {
    def service = (config.service ?: params.SERVICE)?.toString()
    def number = (config.build ?: params.RELEASE_BUILD)?.toString()
    def requestedImage = (config.image ?: params.IMAGE)?.toString()
    def target = (config.target ?: 'release-artifacts').toString()
    def tools = (config.tools ?: '.').toString()
    if (!(service in ['frontend','catalogue','cart','orders'])) { error('Invalid release service') }
    if (!(number ==~ /[1-9][0-9]*/)) { error('A protected-main quality-passed release build number is required') }
    if (!(target ==~ /release-artifacts(?:\/[a-z]+)?/)) { error('Invalid release artifact directory') }
    if (!(tools in ['.','trusted-tools'])) { error('Expected protected GitOps tools checkout') }
    if (requestedImage && (!(requestedImage ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/) || !requestedImage.startsWith("ghcr.io/subhankar12-spec/boutique-${service}@"))) { error('Expected service image digest') }
    dir(target) { deleteDir() }
    // Specific artifacts are usable after the quality phase is archived, even
    // while a publishing parent waits for its automatic delivery child.
    copyArtifacts projectName: "boutique-${service}/main", selector: specific(number),
        filter: 'release.json,release-attestation.json,image-digest.txt,sbom.json,image-scan.json,chart.tgz', target: target, flatten: true
    def image = readFile("${target}/image-digest.txt").trim()
    if (!(image ==~ /ghcr\.io\/subhankar12-spec\/boutique-[a-z-]+@sha256:[a-f0-9]{64}/) || !image.startsWith("ghcr.io/subhankar12-spec/boutique-${service}@") || (requestedImage && image != requestedImage)) { error('Published artifact image mismatch') }
    withCredentials([file(credentialsId:'release-artifact-public-key',variable:'RELEASE_PUBLIC_KEY')]) {
        withEnv(["ARTIFACT_DIRECTORY=${target}","ARTIFACT_TOOLS=${tools}","ARTIFACT_SERVICE=${service}","ARTIFACT_IMAGE=${image}"]) {
            sh '''set -eu
                python3 "$ARTIFACT_TOOLS/scripts/release_attestation.py" verify --attestation "$ARTIFACT_DIRECTORY/release-attestation.json" --public-key "$RELEASE_PUBLIC_KEY" --service "$ARTIFACT_SERVICE" --image "$ARTIFACT_IMAGE"
                python3 - <<'ARTIFACT'
import hashlib,json,os
from pathlib import Path
p=Path(os.environ["ARTIFACT_DIRECTORY"])
r=json.loads((p/"release.json").read_text())
signed=json.loads((p/"release-attestation.json").read_text())["payload"]
if r != signed:
    raise SystemExit("Plain release record differs from the verified signed payload")
if r["image"]!=os.environ["ARTIFACT_IMAGE"] or r["service"]!=os.environ["ARTIFACT_SERVICE"]:
    raise SystemExit("Release artifact identity mismatch")
for file,key in [("sbom.json","sbom_sha256"),("image-scan.json","scan_sha256")]:
    if hashlib.sha256((p/file).read_bytes()).hexdigest()!=signed[key]:
        raise SystemExit("Release report checksum differs from signed attestation")
if hashlib.sha256((p/"chart.tgz").read_bytes()).hexdigest()!=signed["chart"]["package_sha256"]:
    raise SystemExit("Chart artifact checksum differs from signed attestation")
ARTIFACT
            '''
        }
    }
    def record = new groovy.json.JsonSlurperClassic().parseText(readFile("${target}/release.json"))
    return [service:service,image:image,sourceCommit:record.source_commit,target:target,build:number]
}
