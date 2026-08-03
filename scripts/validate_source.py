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

for source in root.rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    if "TODO(" in text or "FIXME" in text:
        print(f"Unresolved marker in {source.relative_to(root)}")
        sys.exit(1)

print(f"FranProbe source tree OK: {sum(1 for _ in root.rglob('*') if _.is_file())} files")
