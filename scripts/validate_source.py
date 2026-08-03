#!/usr/bin/env python3
"""Lightweight source-tree validation that does not require Android SDK."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    ".github/workflows/android-build.yml",
    "app/src/main/java/ru/franprobe/app/engine/DiagnosticEngine.kt",
    "app/src/main/java/ru/franprobe/app/net/DnsCodec.kt",
    "app/src/main/java/ru/franprobe/app/net/NetworkTools.kt",
    "app/src/main/java/ru/franprobe/app/report/ReportExporter.kt",
]
missing = [name for name in required if not (root / name).is_file()]
if missing:
    print("Missing required files:")
    print("\n".join(f"- {name}" for name in missing))
    sys.exit(1)

for xml in (root / "app/src/main").rglob("*.xml"):
    ET.parse(xml)


workflow = (root / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
required_workflow_tokens = [
    "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
    "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
    "actions/upload-artifact@v7",
    "testDebugUnitTest lintDebug assembleDebug",
]
for token in required_workflow_tokens:
    if token not in workflow:
        print(f"Workflow is missing required token: {token}")
        sys.exit(1)

app_build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
readme = (root / "README.md").read_text(encoding="utf-8")
consistency_tokens = {
    "app/build.gradle.kts": [
        "compileSdk = 36",
        "targetSdk = 36",
        "versionCode = 20003",
        'versionName = "2.0.3"',
        'androidx.compose:compose-bom:2025.08.00',
        'androidx.core:core-ktx:1.17.0',
        'androidx.activity:activity-compose:1.11.0',
        'androidx.lifecycle:lifecycle-runtime-ktx:2.9.4',
        'tasks.register("verifyApi36Dependencies")',
    ],
    ".github/workflows/android-build.yml": [
        '"platforms;android-36"',
        '"build-tools;36.0.0"',
        "FranProbe-2.0.3-debug.apk",
        "verifyApi36Dependencies",
        "dependency-report.txt",
    ],
    "README.md": [
        "# FranProbe 2.0.3",
        "Android SDK Platform 36",
    ],
}
contents = {
    "app/build.gradle.kts": app_build,
    ".github/workflows/android-build.yml": workflow,
    "README.md": readme,
}
for file_name, tokens in consistency_tokens.items():
    for token in tokens:
        if token not in contents[file_name]:
            print(f"Version/SDK mismatch in {file_name}: missing {token}")
            sys.exit(1)

for source in root.rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    if "TODO(" in text or "FIXME" in text:
        print(f"Unresolved marker in {source.relative_to(root)}")
        sys.exit(1)

print(f"FranProbe source tree OK: {sum(1 for _ in root.rglob('*') if _.is_file())} files")
