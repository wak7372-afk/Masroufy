"""Package source and the verified personal Android build without local caches."""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
import argparse


ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
APK = ROOT / "androidApp/build/outputs/apk/release/androidApp-release.apk"


def inside_workspace(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(ROOT):
        raise RuntimeError("Output path is outside the project.")
    return resolved


def results_directory(build_root: Path, project: str, task: str) -> Path:
    candidates = (
        build_root / project / 'build/test-results' / task,
        build_root / project / 'test-results' / task,
        build_root / project / 'statement-test-results' / task / 'xml',
    )
    return next((path for path in candidates if path.is_dir()), candidates[0])


def main():
    global APK
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-root', type=Path, default=ROOT)
    parser.add_argument('--security-report', type=Path, required=True)
    args = parser.parse_args()
    build_root = args.build_root.resolve()
    android_build = build_root / 'androidApp/build'
    if not (android_build / 'outputs').is_dir():
        android_build = build_root / 'androidApp'
    APK = android_build / 'outputs/apk/release/androidApp-release.apk'
    security = json.loads(args.security_report.read_text(encoding='utf-8'))
    if security.get('passed') is not True or security.get('apk_sha256') != hashlib.sha256(APK.read_bytes()).hexdigest():
        raise RuntimeError('Release blocked: security checks failed or belong to a different APK.')
    metadata = json.loads((APK.parent / "output-metadata.json").read_text(encoding="utf-8"))
    version = metadata["elements"][0]["versionName"]
    suites = []
    jvm_results = results_directory(build_root, 'shared', 'jvmTest')
    for path in sorted(jvm_results.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        result = {"name": suite.attrib["name"]}
        result.update({key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")})
        if result["failures"] or result["errors"]:
            raise RuntimeError("Refusing to package failed tests.")
        suites.append(result)
    if not suites or not APK.is_file():
        raise RuntimeError("Build and run the tests before packaging.")
    android_suites = []
    android_results = results_directory(build_root, 'androidApp', 'testDebugUnitTest')
    for path in sorted(android_results.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        result = {"name": suite.attrib["name"]}
        result.update({key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")})
        if result["failures"] or result["errors"]:
            raise RuntimeError("Android PDF reader tests failed.")
        android_suites.append(result)
    if not android_suites:
        raise RuntimeError("Run Android PDF reader tests before packaging.")

    lint_path = android_build / "reports/lint-results-release.xml"
    lint_issues = ET.parse(lint_path).getroot().findall("issue")
    lint_errors = sum(issue.attrib.get("severity") in ("Error", "Fatal") for issue in lint_issues)
    if lint_errors:
        raise RuntimeError("Refusing to package an APK with lint errors.")

    inside_workspace(DIST).mkdir(exist_ok=True)
    destination = inside_workspace(DIST / f"Masroofi-{version}.apk")
    shutil.copy2(APK, destination)
    verification = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "version": version,
        "android": {
            "application_id": "om.masroofi.app",
            "minimum_api": 26,
            "target_api": 35,
            "build": "release (personal trial; existing signing identity)",
            "apk": destination.name,
            "bytes": destination.stat().st_size,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "package_signature_and_no_internet_permission_checked_by": "scripts/verify-package.ps1",
            "lint_errors": lint_errors,
            "lint_warnings": sum(issue.attrib.get("severity") == "Warning" for issue in lint_issues),
            "physical_device_or_emulator_tested": False,
        },
        "jvm_tests": {"total": sum(s["tests"] for s in suites), "suites": suites},
        "android_jvm_tests": {"total": sum(s["tests"] for s in android_suites), "runner": "Robolectric", "suites": android_suites},
        "ios": {"xcode_project_available": True, "swift_compiled": False, "xctest_executed": False, "ipa_available": False},
    }
    inside_workspace(DIST / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    root_files = (
        ".gitignore", ".gitattributes", "README.md", "settings.gradle.kts",
        "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    )
    files = [ROOT / name for name in root_files]
    excluded_parts = {"build", ".gradle", ".kotlin", "__pycache__", "xcuserdata"}
    for directory in ("androidApp", "shared", "iosApp", "scripts", "gradle", "tools/local_extractor"):
        for path in (ROOT / directory).rglob("*"):
            relative = path.relative_to(ROOT)
            if path.is_file() and not any(part in excluded_parts for part in relative.parts) and path.suffix not in (".pyc", ".xcuserstate", ".jks", ".keystore"):
                files.append(path)
    files.append(DIST / "verification.json")
    archive = inside_workspace(DIST / f"Masroofi-{version}-source.zip")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in sorted(set(files)):
            if not path.is_file():
                raise RuntimeError(f"Missing source file: {path.name}")
            output.write(path, path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(archive) as source:
        if source.testzip() is not None:
            raise RuntimeError("Source archive failed CRC verification.")
        count = len(source.namelist())
    print(json.dumps({"apk": str(destination), "source_archive": str(archive), "source_files": count, "tests": verification["jvm_tests"]["total"], "lint_errors": lint_errors}, ensure_ascii=False))


if __name__ == "__main__":
    main()
