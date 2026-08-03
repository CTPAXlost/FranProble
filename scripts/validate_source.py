#!/usr/bin/env python3
"""Source-tree validation that runs before Android/Gradle compilation."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]

required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    ".github/workflows/android-build.yml",
    "scripts/run_ci_checks.sh",
    "app/src/main/java/ru/franprobe/app/engine/DiagnosticEngine.kt",
    "app/src/main/java/ru/franprobe/app/model/DiagnosticModels.kt",
    "app/src/main/java/ru/franprobe/app/model/DnsModels.kt",
    "app/src/main/java/ru/franprobe/app/net/DnsCodec.kt",
    "app/src/main/java/ru/franprobe/app/net/NetworkTools.kt",
    "app/src/main/java/ru/franprobe/app/report/ReportExporter.kt",
    "app/src/main/java/ru/franprobe/app/ui/FranProbeApp.kt",
    "app/src/main/java/ru/franprobe/app/ui/MainActivity.kt",
    "app/src/main/java/ru/franprobe/app/ui/MainViewModel.kt",
    "app/src/test/java/ru/franprobe/app/net/DnsCodecTest.kt",
    "app/src/test/java/ru/franprobe/app/net/NetworkToolsTest.kt",
]
missing = [name for name in required if not (root / name).is_file()]
if missing:
    raise SystemExit("Missing required files:\n" + "\n".join(f"- {name}" for name in missing))

# Every XML resource and manifest must be well-formed.
for xml in (root / "app/src").rglob("*.xml"):
    try:
        ET.parse(xml)
    except ET.ParseError as error:
        raise SystemExit(f"Invalid XML {xml.relative_to(root)}: {error}")

app_build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
ci_script = (root / "scripts/run_ci_checks.sh").read_text(encoding="utf-8")
readme = (root / "README.md").read_text(encoding="utf-8")

def extract(pattern: str, text: str, label: str) -> str:
    match = re.search(pattern, text)
    if not match:
        raise SystemExit(f"Cannot determine {label}")
    return match.group(1)

version_name = extract(r'versionName\s*=\s*"([^"]+)"', app_build, "versionName")
version_code = int(extract(r'versionCode\s*=\s*(\d+)', app_build, "versionCode"))
compile_sdk = int(extract(r'compileSdk\s*=\s*(\d+)', app_build, "compileSdk"))
target_sdk = int(extract(r'targetSdk\s*=\s*(\d+)', app_build, "targetSdk"))

if version_name != "2.0.4" or version_code != 20004:
    raise SystemExit(f"Unexpected app version: {version_name} ({version_code})")
if compile_sdk != 36 or target_sdk != 36:
    raise SystemExit(f"SDK mismatch: compileSdk={compile_sdk}, targetSdk={target_sdk}")

required_app_tokens = [
    'androidx.compose:compose-bom:2025.08.00',
    'androidx.core:core-ktx:1.17.0',
    'androidx.activity:activity-compose:1.11.0',
    'androidx.activity:activity-ktx:1.11.0',
    'androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4',
    'com.squareup.okhttp3:okhttp:5.3.0',
]
for token in required_app_tokens:
    if token not in app_build:
        raise SystemExit(f"app/build.gradle.kts is missing pinned dependency: {token}")

for forbidden in [
    'tasks.register("verifyApi36Dependencies")',
    'lifecycle-runtime-ktx',
    'lifecycle-viewmodel-compose',
]:
    if forbidden in app_build:
        raise SystemExit(f"Obsolete/brittle build logic is still present: {forbidden}")

required_workflow_tokens = [
    '"platforms;android-36"',
    '"build-tools;36.0.0"',
    "scripts/run_ci_checks.sh",
    "FranProbe-2.0.4-debug.apk",
    "FranProbe-build-diagnostics",
    "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
    "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
]
for token in required_workflow_tokens:
    if token not in workflow:
        raise SystemExit(f"Workflow is missing required token: {token}")
if "continue-on-error" in workflow:
    raise SystemExit("Workflow must not hide failures with continue-on-error")
if 'temporary_project="$(mktemp -d)"' not in workflow:
    raise SystemExit("Gradle Wrapper must be generated in an isolated bootstrap project")
if "Validate complete source tree" in workflow:
    raise SystemExit("Source validation must run inside the aggregate CI pass, not stop the workflow early")
for bootstrap_log in ("bootstrap-wrapper.log", "bootstrap-android-sdk.log"):
    if bootstrap_log not in workflow:
        raise SystemExit(f"Workflow must preserve bootstrap log: {bootstrap_log}")

required_stages = [
    "00-environment",
    "01-source-validation",
    "02-clean",
    "03-dependencies",
    "04-core-insight",
    "05-lifecycle-insight",
    "06-compose-insight",
    "07-aar-metadata",
    "08-manifest",
    "09-resources",
    "10-kotlin-compile",
    "11-unit-test-compile",
    "12-unit-tests",
    "13-lint",
    "14-assemble-apk",
]
for stage in required_stages:
    if stage not in ci_script:
        raise SystemExit(f"CI aggregator is missing stage: {stage}")
if "exit 1" not in ci_script or "PIPESTATUS[0]" not in ci_script:
    raise SystemExit("CI aggregator does not preserve mandatory stage failures")
if 'rm -rf "$LOG_DIR"' in ci_script:
    raise SystemExit("CI aggregator must not erase bootstrap diagnostics")
if "! -name 'bootstrap-*.log' -delete" not in ci_script:
    raise SystemExit("CI aggregator must preserve bootstrap wrapper/SDK logs")

if f"# FranProbe {version_name}" not in readme:
    raise SystemExit("README version does not match app version")
if f"FranProbe-{version_name}-debug.apk" not in readme:
    raise SystemExit("README APK name does not match app version")

# Kotlin source sanity checks independent of Android SDK.
for source in root.rglob("*.kt"):
    raw = source.read_bytes()
    if b"\x00" in raw:
        raise SystemExit(f"NUL byte in {source.relative_to(root)}")
    text = raw.decode("utf-8")
    if "TODO(" in text or "FIXME" in text:
        raise SystemExit(f"Unresolved marker in {source.relative_to(root)}")
    if source.name != "NetworkTools.kt" and "FranProbe/2.0." in text:
        raise SystemExit(f"Hard-coded User-Agent/version in {source.relative_to(root)}")
    package_match = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
    if package_match:
        java_root = None
        for marker in ("app/src/main/java/", "app/src/test/java/"):
            normalized = source.as_posix()
            if marker in normalized:
                java_root = normalized.split(marker, 1)[1]
                break
        if java_root:
            expected_dir = package_match.group(1).replace(".", "/")
            if not java_root.startswith(expected_dir + "/"):
                raise SystemExit(
                    f"Package/path mismatch in {source.relative_to(root)}: {package_match.group(1)}"
                )

network_tools = (root / "app/src/main/java/ru/franprobe/app/net/NetworkTools.kt").read_text(encoding="utf-8")
if "FranProbe/${BuildConfig.VERSION_NAME}" not in network_tools:
    raise SystemExit("NetworkTools must use BuildConfig.VERSION_NAME for User-Agent")
if "FranProbe/2.0.2" in network_tools or "FranProbe/2.0.3" in network_tools:
    raise SystemExit("Stale hard-coded User-Agent remains in NetworkTools")
if '@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")' not in network_tools:
    raise SystemExit("Intentional diagnostic trust manager must carry explicit lint suppression")
if "validateCertificate = true" not in network_tools:
    raise SystemExit("DoT must use Android certificate validation")
if 'parameters.endpointIdentificationAlgorithm = if (validateCertificate) "HTTPS" else null' not in network_tools:
    raise SystemExit("Validated TLS probes must enable hostname verification")
if "context.init(null, null, SecureRandom())" not in network_tools:
    raise SystemExit("Validated TLS probes must use the Android trust store")
if ".proxy(Proxy.NO_PROXY)" not in network_tools:
    raise SystemExit("Direct DoH probes must bypass an unrelated system proxy")
if "runInterruptible(Dispatchers.IO)" not in network_tools:
    raise SystemExit("System DNS lookup must be interruptible and bounded by the coroutine timeout")
if "LocalCapabilityException" not in network_tools:
    raise SystemExit("Unsupported local TLS versions must be distinguished from network failures")
if "httpErrorType" not in network_tools or "portUnreachable" not in network_tools:
    raise SystemExit("TLS/HTTP and UDP outcomes must be recorded separately")

manifest_text = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
backup_rules = (root / "app/src/main/res/xml/backup_rules.xml").read_text(encoding="utf-8")
data_rules = (root / "app/src/main/res/xml/data_extraction_rules.xml").read_text(encoding="utf-8")
if 'android:allowBackup="false"' not in manifest_text:
    raise SystemExit("Diagnostic reports must not be included in Android cloud backup")
if 'domain="file" path="reports/"' not in backup_rules or 'domain="file" path="reports/"' not in data_rules:
    raise SystemExit("Report history must be excluded from backup and device transfer")

diagnostic_engine = (root / "app/src/main/java/ru/franprobe/app/engine/DiagnosticEngine.kt").read_text(encoding="utf-8")
if "config.mode == DiagnosticMode.QUICK && collected.isNotEmpty()" not in diagnostic_engine:
    raise SystemExit("Full mode must compare direct DNS resolvers instead of stopping after the first answer")
if "dnsStatus(rcode: Int, addresses: List<String>, truncated: Boolean)" not in diagnostic_engine:
    raise SystemExit("Truncated DNS packets must not be reported as complete/available answers")
if 'data.selectedAlpn == "h2"' not in diagnostic_engine:
    raise SystemExit("HTTP/2 ALPN test must distinguish h2 from fallback/no ALPN")
if "is LocalCapabilityException -> ProbeStatus.NOT_TESTED" not in diagnostic_engine:
    raise SystemExit("Unsupported local TLS versions must be NOT_TESTED, not network ERROR")

dns_codec = (root / "app/src/main/java/ru/franprobe/app/net/DnsCodec.kt").read_text(encoding="utf-8")
if "questionClass == 1" not in dns_codec or "reader.requireAvailable(dataLength)" not in dns_codec:
    raise SystemExit("DNS parser must validate question class and RDATA packet bounds")

main_activity = (root / "app/src/main/java/ru/franprobe/app/ui/MainActivity.kt").read_text(encoding="utf-8")
if "deleteTemporaryExport(source)" not in main_activity:
    raise SystemExit("Temporary export ZIP files must be removed after save/cancel")

# Validate shell syntax now, before Gradle.
subprocess.run(
    ["bash", "-n", str(root / "scripts/run_ci_checks.sh")],
    check=True,
)

print(
    f"FranProbe source tree OK: version={version_name}, "
    f"SDK={compile_sdk}, files={sum(1 for item in root.rglob('*') if item.is_file())}"
)
