#!/usr/bin/env python3
"""Build, verify and archive the release without installing it on a device."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify():
    subprocess.run([
        str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleRelease",
        ":app:testDebugUnitTest", ":app:lint",
    ], cwd=ROOT, check=True)
    outputs = ROOT / "app/build/outputs"
    metadata = json.loads((outputs / "apk/release/output-metadata.json").read_text())
    require(len(metadata["elements"]) == 1, "Expected one arm64 release APK")
    element = metadata["elements"][0]
    apk = outputs / "apk/release" / element["outputFile"]
    reports = outputs / "mapping/release"
    for name in ("mapping.txt", "seeds.txt", "usage.txt", "configuration.txt", "resources.txt"):
        require((reports / name).is_file() and (reports / name).stat().st_size > 0,
                f"Missing R8 report: {name}")
    mapping = dict(re.findall(r"^([^ #\s].*?) -> ([^:]+):$",
                              (reports / "mapping.txt").read_text(), re.MULTILINE))
    seeds = (reports / "seeds.txt").read_text().splitlines()
    required = (
        "ai.onnxruntime.OrtEnvironment", "ai.onnxruntime.OrtSession",
        "ai.onnxruntime.OnnxTensor", "com.google.ai.edge.litert.CompiledModel",
        "com.google.ai.edge.litert.TensorBuffer",
        "com.google.ai.edge.litert.TensorType",
        "com.google.ai.edge.litert.TensorType$Layout",
        "com.google.ai.edge.litert.TensorType$ElementType",
        "com.google.ai.edge.litert.TensorBufferRequirements",
        "com.google.ai.edge.litert.LiteRtException",
        "com.radzivon.bartoshyk.avif.coder.HeifCoder",
    )
    for name in required:
        require(mapping.get(name) == name, f"JNI class removed or renamed: {name}")
    for name in required:
        if name.startswith("com.google.ai.edge.litert.TensorType") or name.endswith(
                ("TensorBufferRequirements", "LiteRtException")):
            require(name in seeds, f"Missing JNI retention rule: {name}")
    require(any(a.startswith("com.manga.translate.") and a != b for a, b in mapping.items()),
            "Application code was not obfuscated")
    update = json.loads((ROOT / "update.json").read_text())
    require(all(element[k] == update[k] for k in ("versionCode", "versionName")),
            "APK version does not match update.json")

    asset_count = 0
    with zipfile.ZipFile(apk) as package:
        names = set(package.namelist())
        require({n.split('/')[1] for n in names if n.startswith('lib/')} == {"arm64-v8a"},
                "Unexpected native ABI set")
        for lib in ("liblitert_jni.so", "libonnxruntime4j_jni.so", "libcoder.so"):
            require(f"lib/arm64-v8a/{lib}" in names, f"Missing native library: {lib}")
        # Compare source assets byte-for-byte, not just their filenames.
        assets = {}
        for directory in (ROOT / "app/src/main/assets", ROOT / "assets"):
            for source in directory.rglob('*'):
                if source.is_file() and not any(p.startswith('.') for p in source.relative_to(directory).parts):
                    name = 'assets/' + source.relative_to(directory).as_posix()
                    data_hash = digest(source.read_bytes())
                    require(name not in assets or assets[name] == data_hash,
                            f"Conflicting source assets: {name}")
                    assets[name] = data_hash
        for name, expected in assets.items():
            require(name in names, f"Missing asset: {name}")
            require(digest(package.read(name)) == expected, f"Changed asset: {name}")
            if name.endswith('.tflite'):
                require(package.getinfo(name).compress_type == zipfile.ZIP_STORED,
                        f"TFLite asset must remain uncompressed: {name}")
        asset_count = len(assets)
        require(any(n.endswith('.tflite') for n in assets), "No TFLite model found")
        require(any(n.startswith('assets/prompts/') for n in assets), "No prompts found")

    apk_hash = digest(apk.read_bytes())
    archive = outputs / "verified-release" / f"{element['versionName']}-{apk_hash[:16]}.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "versionName": element["versionName"], "versionCode": element["versionCode"],
        "apkSha256": apk_hash, "apkBytes": apk.stat().st_size, "verifiedAssets": asset_count,
        "commit": subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        "worktreeDirty": bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        "deviceTests": "Not performed; verify JNI execution and UI on an arm64 device.",
    }
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(apk, apk.name)
        for name in ("mapping.txt", "seeds.txt", "usage.txt", "configuration.txt", "resources.txt"):
            bundle.write(reports / name, 'mapping/' + name)
        bundle.writestr('verification.json', json.dumps(summary, indent=2) + '\n')
    print(f"PASS: {asset_count} assets, JNI retention, obfuscation, ABI and version checked.")
    print(f"Archive: {archive}")
    print("Device checks remain necessary; see dev_doc/Release构建与混淆.md")


if __name__ == '__main__':
    try:
        verify()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError,
            KeyError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
