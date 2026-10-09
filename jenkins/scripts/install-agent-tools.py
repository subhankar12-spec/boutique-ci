#!/usr/bin/env python3
"""Install pinned official Linux amd64 tools; verify bytes before extraction.

Release SHA256/SHA512 values below were checked against publisher checksums.
Versions and hashes change together through review, never by following latest.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

TOOLS = {
    "kubectl": dict(version="1.34.0", url="https://dl.k8s.io/release/v1.34.0/bin/linux/amd64/kubectl", sha256="cfda68cba5848bc3b6c6135ae2f20ba2c78de20059f68789c090166d6abc3e2c", kind="binary", args=["version", "--client=true"]),
    "terraform": dict(version="1.11.4", url="https://releases.hashicorp.com/terraform/1.11.4/terraform_1.11.4_linux_amd64.zip", sha256="1ce994251c00281d6845f0f268637ba50c0005657eb3cf096b92f753b42ef4dc", kind="zip", member="terraform", args=["version"]),
    "kubeconform": dict(version="0.6.7", url="https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz", sha256="95f14e87aa28c09d5941f11bd024c1d02fdc0303ccaa23f61cef67bc92619d73", kind="tar", member="kubeconform", args=["-v"]),
    "trivy": dict(version="0.75.0", url="https://github.com/aquasecurity/trivy/releases/download/v0.75.0/trivy_0.75.0_Linux-64bit.tar.gz", sha256="c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f", kind="tar", member="trivy", args=["--version"]),
    "syft": dict(version="1.54.1", url="https://github.com/anchore/syft/releases/download/v1.54.1/syft_1.54.1_linux_amd64.tar.gz", sha256="c069905b391cc4c20a5ba65ad5c10be2a7ba074f8ea6ad203e24d14e303dad47", kind="tar", member="syft", args=["version"]),
    "gh": dict(version="2.102.0", url="https://github.com/cli/cli/releases/download/v2.102.0/gh_2.102.0_linux_amd64.tar.gz", sha256="bb766f710eef8ede859c18578c72c327597cd4c8a85b06001b1f3843c6019386", kind="tar", member="gh_2.102.0_linux_amd64/bin/gh", args=["--version"]),
    "go": dict(version="1.27.2", url="https://go.dev/dl/go1.27.2.linux-amd64.tar.gz", sha256="ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5", kind="tree", directory="go", executable="bin/go", args=["version"]),
    "mvn": dict(version="3.9.16", url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.tar.gz", sha512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6", kind="tree", directory="apache-maven-3.9.16", executable="bin/mvn", args=["--version"]),
}
ORIGINS = {"dl.k8s.io", "cdn.dl.k8s.io", "releases.hashicorp.com", "github.com",
           "release-assets.githubusercontent.com", "objects.githubusercontent.com",
           "go.dev", "dl.google.com", "repo.maven.apache.org"}


def trusted_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in ORIGINS or parsed.username or parsed.password:
        raise ValueError("Artifact URL must use an approved official HTTPS origin")


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        trusted_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def digest_file(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(tool, path):
    trusted_url(tool["url"])
    opener = urllib.request.build_opener(OfficialRedirects())
    with opener.open(tool["url"], timeout=60) as response, path.open("wb") as output:
        shutil.copyfileobj(response, output)
    algorithm = "sha512" if "sha512" in tool else "sha256"
    if digest_file(path, algorithm) != tool[algorithm]:
        raise ValueError("Official tool artifact checksum mismatch")


def install(name, tool, archive_path, root):
    executable = root / "bin" / name
    if tool["kind"] == "binary":
        shutil.copyfile(archive_path, executable)
    elif tool["kind"] == "zip":
        with zipfile.ZipFile(archive_path) as archive, archive.open(tool["member"]) as source, executable.open("wb") as output:
            shutil.copyfileobj(source, output)
    elif tool["kind"] == "tar":
        with tarfile.open(archive_path) as archive:
            member = archive.getmember(tool["member"])
            if not member.isfile():
                raise ValueError("Expected regular binary archive member")
            with archive.extractfile(member) as source, executable.open("wb") as output:
                shutil.copyfileobj(source, output)
    else:
        destination = root / "lib" / tool["directory"]
        if destination.exists():
            raise ValueError("Tool tree already exists; install into a clean destination")
        with tarfile.open(archive_path) as archive:
            if any(not member.name.startswith(tool["directory"] + "/") and member.name != tool["directory"] for member in archive.getmembers()):
                raise ValueError("Unexpected archive root")
            archive.extractall(root / "lib", filter="data")
        executable.symlink_to(destination / tool["executable"])
    executable.chmod(0o755)
    return executable


def verify(root, manifest):
    for name, row in manifest.items():
        executable = root / "bin" / name
        if digest_file(executable) != row["installed_sha256"]:
            raise ValueError("Missing or changed installed tool: " + name)
        result = subprocess.run([str(executable), *TOOLS[name]["args"]], capture_output=True, text=True, timeout=30, check=True)
        if TOOLS[name]["version"] not in result.stdout + result.stderr:
            raise ValueError("Unexpected tool runtime version: " + name)
        print(name + " " + TOOLS[name]["version"] + " verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--only", nargs="+", choices=sorted(TOOLS), default=list(TOOLS))
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("These reviewed artifacts support Linux amd64 only")
    root = args.destination.resolve()
    manifest_path = root / "tools-manifest.json"
    if args.verify_only:
        manifest = json.loads(manifest_path.read_text())
        if set(manifest) != set(args.only):
            raise ValueError("Installed tool set differs from requested set")
        verify(root, manifest)
        return
    (root / "bin").mkdir(parents=True, exist_ok=True)
    (root / "lib").mkdir(exist_ok=True)
    manifest = {}
    with tempfile.TemporaryDirectory(prefix="verified-agent-tools-") as temporary:
        for name in args.only:
            tool = TOOLS[name]
            artifact = Path(temporary) / name
            fetch(tool, artifact)
            executable = install(name, tool, artifact, root)
            artifact.unlink()
            manifest[name] = {"version": tool["version"], "url": tool["url"], "installed_sha256": digest_file(executable)}
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    verify(root, manifest)


if __name__ == "__main__":
    main()
