"""Fail closed on package hardening, secret patterns, dependency findings or stale native PNG."""
import argparse
from datetime import datetime, timezone
import json
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent.parent
PNG_ABIS={'arm64-v8a','armeabi-v7a','x86','x86_64'}
BCPKIX_SHA256='cf70d32101e53c3f92d55ba5c5f1329af51a9a44057fd21e81e20cfd6cd64953'

def reviewed_lint_issue(issue, offline, est_absent):
    """One reviewed upstream helper is allowed only when absent from shipped code.

    Do not suppress lint or allow unrelated occurrences of the same warning.
    The exact upstream JAR is checked, and no EST references may remain in DEX.
    """
    if issue.get('id')!='TrustAllX509TrustManager' or not offline or not est_absent:
        return False
    locations=issue.findall('location')
    if len(locations)!=1:return False
    path=Path(locations[0].get('file',''))
    return (path.name=='bcpkix-jdk15to18-1.86.jar' and path.is_file() and
            hashlib.sha256(path.read_bytes()).hexdigest()==BCPKIX_SHA256)

def verify_png_sources():
    base=ROOT/'third_party/libpng'
    manifest=json.loads((base/'manifest.json').read_text(encoding='utf-8'))
    files=manifest.get('files_sha256',{})
    return (manifest.get('version')=='1.6.59' and len(files)==26 and
            all((base/name).resolve().is_relative_to(base.resolve()) and
                hashlib.sha256((base/name).read_bytes()).hexdigest()==digest
                for name,digest in files.items()))

def runtime_tests_passed(directory,apk):
    required={'keystoreCiphertextSurvivesReopenAndRejectsTampering',
              'rebuiltNativePngDecodesActualFileAndReleasesResources',
              'nativeOcrReadsSyntheticStatementWithoutInternetPermission',
              'pdfCropRendersOriginalAmountFromEncryptedAndRotatedPagesInMemory'}
    found=set();total=0
    files=list(directory.rglob('TEST-*.xml')) if directory.is_dir() else []
    for path in files:
        if path.stat().st_mtime<apk.stat().st_mtime:return False
        suite=ET.parse(path).getroot()
        if any(int(suite.get(k,'0')) for k in ('failures','errors','skipped')):return False
        if re.search(r'Test run failed|Process crashed',suite.findtext('system-err',''),re.I):return False
        total+=int(suite.get('tests','0'))
        found.update(case.get('name') for case in suite.findall('testcase'))
    return total>=4 and required<=found

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--dependencies',type=Path,required=True)
    parser.add_argument('--lint-report',type=Path,required=True)
    parser.add_argument('--native-audit',type=Path,required=True)
    parser.add_argument('--runtime-tests',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    failures=[];checks=[]
    def check(name,passed,detail=''):
        checks.append({'name':name,'passed':bool(passed),'detail':detail})
        if not passed:failures.append(name)
    deps=json.loads(args.dependencies.read_text(encoding='utf-8'))
    check('known_maven_vulnerabilities',deps.get('passed') is True and deps.get('package_count',0)>0,str(len(deps.get('findings',[])))+' unresolved findings')
    lint=ET.parse(args.lint_report).getroot().findall('issue')
    check('lint_errors',not any(i.attrib.get('severity') in ('Error','Fatal') for i in lint))
    security_ids={'TrustAllX509TrustManager','AllowAllHostnameVerifier','BadHostnameVerifier','UnsafeDynamicallyLoadedCode','UnsafeNativeCodeLocation','UnspecifiedImmutableFlag'}
    aapt=ROOT/'.tools/android-sdk/build-tools/35.0.0/aapt.exe'
    badging=subprocess.run([str(aapt),'dump','badging',str(args.apk.resolve())],check=True,capture_output=True,text=True,encoding='utf-8').stdout
    xml=subprocess.run([str(aapt),'dump','xmltree',str(args.apk.resolve()),'AndroidManifest.xml'],check=True,capture_output=True,text=True,encoding='utf-8').stdout
    check('not_debuggable','application-debuggable' not in badging)
    offline="uses-permission: name='android.permission.INTERNET'" not in badging
    check('no_internet_permission',offline)
    check('no_shared_storage_permission',all(p not in badging for p in ('android.permission.WRITE_EXTERNAL_STORAGE','android.permission.READ_EXTERNAL_STORAGE','android.permission.MANAGE_EXTERNAL_STORAGE')))
    check('backup_disabled',bool(re.search(r'android:allowBackup[^\n]*\b0x0\b',xml)))
    check('cleartext_disabled',bool(re.search(r'android:usesCleartextTraffic[^\n]*\b0x0\b',xml)))
    native=[]
    with zipfile.ZipFile(args.apk) as archive:
        check('apk_crc',archive.testzip() is None)
        dex=[n for n in archive.namelist() if re.fullmatch(r'classes(?:[0-9]+)?\.dex',n)]
        est_absent=bool(dex) and all(b'org/bouncycastle/est/' not in archive.read(n) for n in dex)
        check('unused_est_tls_code_removed',est_absent,'Release R8 also enforces -checkdiscard org.bouncycastle.est.**; class names are preserved.')
        security_issues=[i for i in lint if i.get('category')=='Security' or i.get('id') in security_ids]
        unreviewed=[i.get('id') for i in security_issues if not reviewed_lint_issue(i,offline,est_absent)]
        check('unreviewed_security_lint',not unreviewed,', '.join(unreviewed))
        for name in archive.namelist():
            if name.endswith('libpngx.so'):
                versions=sorted({v.decode('ascii') for v in re.findall(rb'(?<![0-9.])1\.6\.[0-9]{1,3}(?![0-9.])',archive.read(name))})
                safe=bool(versions) and all(int(v.split('.')[-1])>=59 for v in versions)
                native.append({'path':name,'libpng_versions':versions,'passed':safe})
        expected={f'lib/{abi}/libpngx.so' for abi in PNG_ABIS}
        check('native_png_security_baseline',{n['path'] for n in native}==expected and all(n['passed'] for n in native),'All four ABIs require libpng >=1.6.59; binary version strings plus verified vendored upstream sources.')
    check('native_png_sources_verified',verify_png_sources(),'All 26 source/license/configuration files match the pinned official archive manifest.')
    native_audit=json.loads(args.native_audit.read_text(encoding='utf-8'))
    apk_hash=hashlib.sha256(args.apk.read_bytes()).hexdigest()
    abi_results=native_audit.get('abis',[])
    check('native_png_abi_compatibility',native_audit.get('passed') is True and
          native_audit.get('apk_sha256')==apk_hash and len(abi_results)==4 and
          {r.get('abi') for r in abi_results}==PNG_ABIS and all(r.get('passed') is True for r in abi_results),
          'Same APK: PNG symbols/SONAME, all consumers, 16 KB LOAD alignment, non-executable stack and immediate binding.')
    check('android_release_runtime_tests',runtime_tests_passed(args.runtime_tests,args.apk),
          'All required Release OCR/PNG/encrypted-PDF/Keystore cases must pass without skips; reports older than the APK are rejected.')
    files=subprocess.run(['git','-C',str(ROOT),'ls-files'],check=True,capture_output=True,text=True,encoding='utf-8').stdout.splitlines()
    # Include new source files too, without printing or transmitting their contents.
    files+=subprocess.run(['git','-C',str(ROOT),'ls-files','--others','--exclude-standard'],check=True,capture_output=True,text=True,encoding='utf-8').stdout.splitlines()
    patterns=[re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),re.compile(r'\bgh[pousr]_[A-Za-z0-9]{30,}\b'),re.compile(r'\bAKIA[A-Z0-9]{16}\b')]
    leaks=[]
    for name in set(files):
        path=(ROOT/name).resolve()
        if not path.is_relative_to(ROOT) or not path.is_file() or path.stat().st_size>2_000_000:continue
        try:text=path.read_text(encoding='utf-8')
        except UnicodeError:continue
        if any(p.search(text) for p in patterns):leaks.append(name)
    check('secret_patterns',not leaks,', '.join(leaks))
    report={'checked_at_utc':datetime.now(timezone.utc).isoformat(),'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'passed':not failures,'checks':checks,'failures':failures,'native_libraries':native,
        'limits':['Known-advisory and automated checks are not proof against all attacks.','Android runtime execution is covered by the required test reports; emulator results do not prove physical-device/ARM performance or hardware-backed Keystore. iOS runtime was not tested here.','Native dependency findings block release even where exploitability in this app has not been demonstrated.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'failures':failures,'checks':len(checks)}))
    raise SystemExit(0 if report['passed'] else 1)

if __name__=='__main__':main()
