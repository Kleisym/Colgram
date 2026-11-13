#!/usr/bin/env python3
"""Stage version-pinned Telegram native libraries, never the moving download URL."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile
import urllib.request
import zipfile


def sha256(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def send_request_descriptor(source):
    match = re.search(r"native\s+void\s+native_sendRequest\s*\(([^)]*)\)", source)
    if not match:
        raise ValueError("ConnectionsManager native_sendRequest declaration not found")
    primitive = {"boolean":"Z", "byte":"B", "char":"C", "short":"S", "int":"I", "long":"J", "float":"F", "double":"D"}
    try:
        return "(" + "".join(primitive[part.strip().split()[0]] for part in match[1].split(",")) + ")V"
    except KeyError as error:
        raise ValueError("native_sendRequest has an unsupported JNI type") from error


def ensure_native(repo_path, manifest_path=None, apk_override=None):
    repo = Path(repo_path).resolve()
    manifest_path = Path(manifest_path or Path(__file__).resolve().parents[1] / "config/telegram-native.json")
    config = json.loads(manifest_path.read_text(encoding="utf-8"))
    try:
        commit = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True, stderr=subprocess.PIPE).strip()
    except subprocess.CalledProcessError as error:
        raise ValueError("Telegram source commit cannot be verified") from error
    if commit != config["telegram_commit"]:
        raise ValueError("Telegram source commit differs from the pinned native commit")
    properties = (repo / "gradle.properties").read_text(encoding="utf-8")
    version = re.search(r"^APP_VERSION_NAME=(.+)$", properties, re.M)
    if not version or version[1].strip().split("-", 1)[0] != config["telegram_version"]:
        raise ValueError("Telegram source version differs from the pinned native version")
    source = (repo / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java").read_text(encoding="utf-8")
    if send_request_descriptor(source) != config["required_jni_descriptor"]:
        raise ValueError("Java/native JNI contract changed: update the pinned native library before building")
    destination = repo / "TMessagesProj/src/main/jniLibs"
    entries = config["entries"]
    targets = {}
    for member, expected in entries.items():
        parts = Path(member).parts
        if len(parts) != 3 or parts[0] != "lib" or parts[2] != "libtmessages.49.so" or parts[1] not in ("arm64-v8a","armeabi-v7a","x86","x86_64"):
            raise ValueError("Invalid native snapshot member: " + member)
        path = destination.joinpath(*parts[1:])
        if not path.resolve().is_relative_to(repo):
            raise ValueError("Native destination escaped the checkout")
        targets[member] = path
    if all(path.is_file() and sha256(path) == entries[member]["sha256"] for member,path in targets.items()):
        print("NATIVE PIN: PASS (all ABI hashes already match " + config["telegram_version"] + ")")
        return targets
    override = apk_override or os.environ.get("COLGRAM_NATIVE_APK")
    cache = manifest_path.resolve().parents[1] / ".native-cache"
    apk = Path(override).resolve() if override else cache / (config["apk_sha256"] + ".apk")
    if override and not apk.is_file():
        raise ValueError("COLGRAM_NATIVE_APK does not name an APK file")
    if not apk.is_file():
        cache.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(config["apk_url"], headers={"User-Agent":"Colgram-native-bootstrap"})
        with tempfile.NamedTemporaryFile(dir=cache, suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    while chunk := response.read(1024 * 1024): output.write(chunk)
                output.flush()
                if sha256(temporary) != config["apk_sha256"]:
                    raise ValueError("Pinned native APK digest mismatch")
            except BaseException:
                output.close(); temporary.unlink(missing_ok=True)
                raise
        temporary.replace(apk)
    if sha256(apk) != config["apk_sha256"]:
        raise ValueError("Pinned native APK digest mismatch")
    # Authenticate and validate every member before replacing even one library.
    payloads = {}
    with zipfile.ZipFile(apk) as archive:
        for member, expected in entries.items():
            payload = archive.read(member)
            if len(payload) != expected["bytes"] or hashlib.sha256(payload).hexdigest() != expected["sha256"]:
                raise ValueError("Native member digest mismatch: " + member)
            if not payload.startswith(b"\x7fELF") or config["required_jni_descriptor"].encode() + b"\x00" not in payload:
                raise ValueError("Native member has an incompatible ELF/JNI contract: " + member)
            payloads[member] = payload
    for member,path in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_file() and path.read_bytes() != payloads[member]:
            backup = repo / ".native-backup" / sha256(path) / path.parent.name / path.name
            backup.parent.mkdir(parents=True, exist_ok=True)
            if not backup.exists(): backup.write_bytes(path.read_bytes())
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".new", delete=False) as output:
            output.write(payloads[member]); staged=Path(output.name)
        staged.replace(path)
    print("NATIVE PIN: PASS (staged " + config["telegram_version"] + "; JNI " + config["required_jni_descriptor"] + ")")
    return targets


if __name__ == "__main__":
    import sys
    ensure_native(sys.argv[1])
