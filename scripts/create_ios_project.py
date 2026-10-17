#!/usr/bin/env python3
"""Generate a self-contained Xcode project and app icon using only Python stdlib."""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import plistlib
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "iosApp"
PROJECT = IOS / "Masroofi.xcodeproj"


def uid(name: str) -> str:
    return hashlib.sha256(("masroofi-xcode-v1:" + name).encode()).hexdigest()[:24].upper()


class Ref(str):
    """A raw PBX object reference; other strings are quoted OpenStep values."""


def ref(name: str) -> Ref:
    return Ref(uid(name))


def render(value, depth=0) -> str:
    pad = "\t" * depth
    if isinstance(value, Ref):
        return str(value)
    if isinstance(value, dict):
        lines = ["{"]
        for key, item in value.items():
            lines.append(f"{pad}\t{key} = {render(item, depth + 1)};")
        return "\n".join(lines) + f"\n{pad}}}"
    if isinstance(value, list):
        if not value:
            return "()"
        return "(\n" + "\n".join(f"{pad}\t{render(item, depth + 1)}," for item in value) + f"\n{pad})"
    if isinstance(value, int):
        return str(value)
    return json.dumps(value, ensure_ascii=False)


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def icon():
    """Opaque RGB icon: a wallet and coin with no external image/font dependency."""
    size = 1024

    def rounded(x, y, cx, cy, halfx, halfy, radius):
        dx, dy = abs(x - cx) - halfx + radius, abs(y - cy) - halfy + radius
        distance = math.hypot(max(dx, 0), max(dy, 0)) + min(max(dx, dy), 0) - radius
        return max(0, min(1, 0.5 - distance))

    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            t = y / (size - 1)
            color = [int(19 + 9 * t), int(86 - 21 * t), int(65 - 12 * t)]

            def blend(rgb, alpha):
                for index in range(3):
                    color[index] = round(color[index] * (1 - alpha) + rgb[index] * alpha)

            # Fold, wallet silhouette, inset, clasp, and a baisa coin.
            blend((219, 178, 99), rounded(x, y, 485, 445, 266, 151, 34))
            blend((246, 245, 224), rounded(x, y, 501, 526, 288, 203, 55))
            blend((19, 80, 61), rounded(x, y, 501, 531, 247, 160, 33))
            blend((246, 245, 224), rounded(x, y, 740, 525, 95, 67, 22))
            blend((25, 83, 64), max(0, min(1, 29.5 - math.hypot(x - 724, y - 525))))
            blend((223, 181, 105), max(0, min(1, 116.5 - math.hypot(x - 348, y - 489))))
            blend((253, 240, 202), max(0, min(1, 83.5 - math.hypot(x - 348, y - 489))))
            blend((148, 106, 47), rounded(x, y, 348, 489, 11, 49, 7))
            raw.extend(color)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    asset = IOS / "Assets.xcassets" / "AppIcon.appiconset"
    asset.mkdir(parents=True, exist_ok=True)
    (asset / "AppIcon.png").write_bytes(png)
    write(asset / "Contents.json", json.dumps({"images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    write(IOS / "Assets.xcassets" / "Contents.json", '{"info":{"author":"xcode","version":1}}\n')


def generate():
    sources = sorted((IOS / "Sources").glob("*.swift"))
    tests = sorted((IOS / "Tests").glob("*.swift"))
    if not sources or not tests:
        raise SystemExit("Expected Swift app sources and tests under iosApp.")
    for file in ("Info.plist", "PrivacyInfo.xcprivacy"):
        with (IOS / file).open("rb") as handle:
            plistlib.load(handle)

    objects = {}

    def add(object_name: str, isa: str, **values):
        if uid(object_name) in objects:
            raise ValueError("Duplicate Xcode object")
        objects[uid(object_name)] = {"isa": isa, **values}
        return ref(object_name)

    appfiles, appbuild, testfiles, testbuild = [], [], [], []
    for files, group, references, builds in ((sources, "source", appfiles, appbuild), (tests, "test", testfiles, testbuild)):
        for path in files:
            file = add(f"{group}:file:{path.name}", "PBXFileReference", lastKnownFileType="sourcecode.swift", path=path.name, sourceTree="<group>")
            references.append(file)
            builds.append(add(f"{group}:build:{path.name}", "PBXBuildFile", fileRef=file))
    app = add("app:product", "PBXFileReference", explicitFileType="wrapper.application", includeInIndex=0, path="Masroofi.app", sourceTree="BUILT_PRODUCTS_DIR")
    test = add("test:product", "PBXFileReference", explicitFileType="wrapper.cfbundle", includeInIndex=0, path="MasroofiTests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
    info = add("info:file", "PBXFileReference", lastKnownFileType="text.plist.xml", path="Info.plist", sourceTree="<group>")
    privacy = add("privacy:file", "PBXFileReference", lastKnownFileType="text.xml", path="PrivacyInfo.xcprivacy", sourceTree="<group>")
    asset = add("asset:file", "PBXFileReference", lastKnownFileType="folder.assetcatalog", path="Assets.xcassets", sourceTree="<group>")
    readme = add("readme:file", "PBXFileReference", lastKnownFileType="net.daringfireball.markdown", path="README.md", sourceTree="<group>")
    resources = [add("privacy:build", "PBXBuildFile", fileRef=privacy), add("asset:build", "PBXBuildFile", fileRef=asset)]
    add("source:group", "PBXGroup", children=appfiles, path="Sources", sourceTree="<group>")
    add("test:group", "PBXGroup", children=testfiles, path="Tests", sourceTree="<group>")
    add("products:group", "PBXGroup", children=[app, test], name="Products", sourceTree="<group>")
    add("main:group", "PBXGroup", children=[ref("source:group"), ref("test:group"), asset, info, privacy, readme, ref("products:group")], sourceTree="<group>")
    script = '\n'.join([
        'set -eu',
        'if [ "YES" = "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" ]; then',
        '  exit 0',
        'fi',
        'cd "$SRCROOT/.."',
        'if [ -z "${JAVA_HOME:-}" ]; then',
        '  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"',
        'fi',
        './gradlew -PcoreOnly=true :shared:embedAndSignAppleFrameworkForXcode',
        '',
    ])
    add("kotlin:phase", "PBXShellScriptBuildPhase", alwaysOutOfDate=1, buildActionMask=2147483647, files=[], inputPaths=[], name="Compile Kotlin Framework", outputPaths=[], runOnlyForDeploymentPostprocessing=0, shellPath="/bin/sh", shellScript=script)
    add("source:phase", "PBXSourcesBuildPhase", buildActionMask=2147483647, files=appbuild, runOnlyForDeploymentPostprocessing=0)
    add("framework:phase", "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[], runOnlyForDeploymentPostprocessing=0)
    add("resource:phase", "PBXResourcesBuildPhase", buildActionMask=2147483647, files=resources, runOnlyForDeploymentPostprocessing=0)
    add("test:source:phase", "PBXSourcesBuildPhase", buildActionMask=2147483647, files=testbuild, runOnlyForDeploymentPostprocessing=0)
    add("test:framework:phase", "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[], runOnlyForDeploymentPostprocessing=0)
    add("test:resource:phase", "PBXResourcesBuildPhase", buildActionMask=2147483647, files=[], runOnlyForDeploymentPostprocessing=0)
    add("app:proxy", "PBXContainerItemProxy", containerPortal=ref("project"), proxyType=1, remoteGlobalIDString=ref("app:target"), remoteInfo="Masroofi")
    add("test:dependency", "PBXTargetDependency", target=ref("app:target"), targetProxy=ref("app:proxy"))

    project_common = {
        "ALWAYS_SEARCH_USER_PATHS": "NO", "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
        "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES", "GCC_C_LANGUAGE_STANDARD": "gnu17",
        "IPHONEOS_DEPLOYMENT_TARGET": "17.0", "SDKROOT": "iphoneos", "SWIFT_VERSION": "5.0",
        "ENABLE_USER_SCRIPT_SANDBOXING": "NO", "SWIFT_STRICT_CONCURRENCY": "minimal",
    }
    app_common = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon", "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": "1", "GENERATE_INFOPLIST_FILE": "NO", "INFOPLIST_FILE": "Info.plist",
        "PRODUCT_BUNDLE_IDENTIFIER": "om.masroofi.personal", "PRODUCT_NAME": "Masroofi", "PRODUCT_MODULE_NAME": "Masroofi",
        "MARKETING_VERSION": "0.1.0", "TARGETED_DEVICE_FAMILY": "1", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
        "SUPPORTS_MACCATALYST": "NO", "SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD": "NO",
        "FRAMEWORK_SEARCH_PATHS": ["$(inherited)", "$(BUILT_PRODUCTS_DIR)"],
        "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
        "OTHER_LDFLAGS": ["$(inherited)", "-framework", "ExpenseCore"], "ENABLE_DEBUG_DYLIB": "NO",
    }
    test_common = {
        "CODE_SIGN_STYLE": "Automatic", "GENERATE_INFOPLIST_FILE": "YES",
        "PRODUCT_BUNDLE_IDENTIFIER": "om.masroofi.personal.tests", "PRODUCT_NAME": "MasroofiTests",
        "TARGETED_DEVICE_FAMILY": "1", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
        "FRAMEWORK_SEARCH_PATHS": ["$(inherited)", "$(BUILT_PRODUCTS_DIR)"],
        "BUNDLE_LOADER": "$(TEST_HOST)", "TEST_HOST": "$(BUILT_PRODUCTS_DIR)/Masroofi.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/Masroofi",
        "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks"],
    }
    for config in ("Debug", "Release"):
        debug = config == "Debug"
        settings = {**project_common, "DEBUG_INFORMATION_FORMAT": "dwarf" if debug else "dwarf-with-dsym",
                    "GCC_OPTIMIZATION_LEVEL": "0" if debug else "s", "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if debug else "-O",
                    "ENABLE_TESTABILITY": "YES" if debug else "NO", "ONLY_ACTIVE_ARCH": "YES" if debug else "NO"}
        if debug:
            settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] = "$(inherited) DEBUG"
        else:
            settings["SWIFT_COMPILATION_MODE"] = "wholemodule"
            settings["VALIDATE_PRODUCT"] = "YES"
        add(f"project:{config}", "XCBuildConfiguration", buildSettings=settings, name=config)
        add(f"app:{config}", "XCBuildConfiguration", buildSettings=app_common, name=config)
        add(f"test:{config}", "XCBuildConfiguration", buildSettings=test_common, name=config)
    for name in ("project", "app", "test"):
        add(f"{name}:configs", "XCConfigurationList", buildConfigurations=[ref(f"{name}:Debug"), ref(f"{name}:Release")], defaultConfigurationIsVisible=0, defaultConfigurationName="Release")
    add("app:target", "PBXNativeTarget", buildConfigurationList=ref("app:configs"), buildPhases=[ref("kotlin:phase"), ref("source:phase"), ref("framework:phase"), ref("resource:phase")], buildRules=[], dependencies=[], name="Masroofi", productName="Masroofi", productReference=app, productType="com.apple.product-type.application")
    add("test:target", "PBXNativeTarget", buildConfigurationList=ref("test:configs"), buildPhases=[ref("test:source:phase"), ref("test:framework:phase"), ref("test:resource:phase")], buildRules=[], dependencies=[ref("test:dependency")], name="MasroofiTests", productName="MasroofiTests", productReference=test, productType="com.apple.product-type.bundle.unit-test")
    add("project", "PBXProject", attributes={"BuildIndependentTargetsInParallel": "YES", "LastUpgradeCheck": "1600", "TargetAttributes": {uid("app:target"): {"CreatedOnToolsVersion": "16.0", "ProvisioningStyle": "Automatic"}, uid("test:target"): {"CreatedOnToolsVersion": "16.0", "ProvisioningStyle": "Automatic", "TestTargetID": ref("app:target")}}}, buildConfigurationList=ref("project:configs"), compatibilityVersion="Xcode 14.0", developmentRegion="ar", hasScannedForEncodings=0, knownRegions=["ar", "en", "Base"], mainGroup=ref("main:group"), productRefGroup=ref("products:group"), projectDirPath="", projectRoot="", targets=[ref("app:target"), ref("test:target")])

    # Catch broken generated references on any OS before writing the project.
    def check(value):
        if isinstance(value, Ref):
            if value not in objects:
                raise ValueError("Unresolved Xcode reference: " + value)
        elif isinstance(value, dict):
            for item in value.values():
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)
    check(objects)
    data = {"archiveVersion": 1, "classes": {}, "objectVersion": 56, "objects": objects, "rootObject": ref("project")}
    write(PROJECT / "project.pbxproj", "// !$*UTF8*$!\n" + render(data) + "\n")
    workspace = '<?xml version="1.0" encoding="UTF-8"?>\n<Workspace version="1.0"><FileRef location="self:"></FileRef></Workspace>\n'
    ET.fromstring(workspace)
    write(PROJECT / "project.xcworkspace" / "contents.xcworkspacedata", workspace)
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">
      <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('app:target')}" BuildableName="Masroofi.app" BlueprintName="Masroofi" ReferencedContainer="container:Masroofi.xcodeproj"/>
    </BuildActionEntry>
    <BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">
      <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('test:target')}" BuildableName="MasroofiTests.xctest" BlueprintName="MasroofiTests" ReferencedContainer="container:Masroofi.xcodeproj"/>
    </BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">
    <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('test:target')}" BuildableName="MasroofiTests.xctest" BlueprintName="MasroofiTests" ReferencedContainer="container:Masroofi.xcodeproj"/>
  </TestableReference></Testables></TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">
    <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('app:target')}" BuildableName="Masroofi.app" BlueprintName="Masroofi" ReferencedContainer="container:Masroofi.xcodeproj"/>
  </BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">
    <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid('app:target')}" BuildableName="Masroofi.app" BlueprintName="Masroofi" ReferencedContainer="container:Masroofi.xcodeproj"/>
  </BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
    ET.fromstring(scheme)
    write(PROJECT / "xcshareddata" / "xcschemes" / "Masroofi.xcscheme", scheme)
    icon()
    print(f"Generated {len(sources)} Swift source references, {len(tests)} test source references, {len(objects)} PBX objects, a shared scheme and opaque 1024px icon.")
    print("Validated plist/XML and all project object references. Swift compilation requires Xcode on Mac.")


if __name__ == "__main__":
    generate()
