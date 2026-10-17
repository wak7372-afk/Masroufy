"""Package source and the verified personal Android build without local caches."""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
APK = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"


def inside_workspace(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_relative_to(ROOT):
        raise RuntimeError("Output path is outside the project.")
    return resolved


def main():
    suites = []
    for path in sorted((ROOT / "shared/build/test-results/jvmTest").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        result = {"name": suite.attrib["name"]}
        result.update({key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")})
        if result["failures"] or result["errors"]:
            raise RuntimeError("Refusing to package failed tests.")
        suites.append(result)
    if not suites or not APK.is_file():
        raise RuntimeError("Build and run the tests before packaging.")

    lint_path = ROOT / "androidApp/build/reports/lint-results-debug.xml"
    lint_issues = ET.parse(lint_path).getroot().findall("issue")
    lint_errors = sum(issue.attrib.get("severity") in ("Error", "Fatal") for issue in lint_issues)
    if lint_errors:
        raise RuntimeError("Refusing to package an APK with lint errors.")

    inside_workspace(DIST).mkdir(exist_ok=True)
    destination = inside_workspace(DIST / "Masroofi-0.1.0-debug.apk")
    shutil.copy2(APK, destination)
    verification = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "version": "0.1.0",
        "android": {
            "application_id": "om.masroofi.app",
            "minimum_api": 26,
            "target_api": 35,
            "build": "debug (personal trial)",
            "apk": destination.name,
            "bytes": destination.stat().st_size,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "package_signature_and_no_internet_permission_checked_by": "scripts/verify-package.ps1",
            "lint_errors": lint_errors,
            "lint_warnings": sum(issue.attrib.get("severity") == "Warning" for issue in lint_issues),
            "physical_device_or_emulator_tested": False,
        },
        "jvm_tests": {"total": sum(s["tests"] for s in suites), "suites": suites},
        "ios": {"xcode_project_available": True, "swift_compiled": False, "xctest_executed": False, "ipa_available": False},
    }
    inside_workspace(DIST / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    root_files = (
        ".gitignore", ".gitattributes", "README.md", "settings.gradle.kts",
        "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    )
    files = [ROOT / name for name in root_files]
    excluded_parts = {"build", ".gradle", ".kotlin", "__pycache__", "xcuserdata"}
    for directory in ("androidApp", "shared", "iosApp", "scripts", "gradle"):
        for path in (ROOT / directory).rglob("*"):
            relative = path.relative_to(ROOT)
            if path.is_file() and not any(part in excluded_parts for part in relative.parts) and path.suffix not in (".pyc", ".xcuserstate", ".jks", ".keystore"):
                files.append(path)
    files.append(DIST / "verification.json")
    archive = inside_workspace(DIST / "Masroofi-0.1.0-source.zip")
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
