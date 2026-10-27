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

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--dependencies',type=Path,required=True)
    parser.add_argument('--lint-report',type=Path,required=True)
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
    unreviewed=[i.attrib.get('id') for i in lint if i.attrib.get('id') in security_ids]
    check('unreviewed_security_lint',not unreviewed,', '.join(unreviewed))
    aapt=ROOT/'.tools/android-sdk/build-tools/35.0.0/aapt.exe'
    badging=subprocess.run([str(aapt),'dump','badging',str(args.apk.resolve())],check=True,capture_output=True,text=True,encoding='utf-8').stdout
    xml=subprocess.run([str(aapt),'dump','xmltree',str(args.apk.resolve()),'AndroidManifest.xml'],check=True,capture_output=True,text=True,encoding='utf-8').stdout
    check('not_debuggable','application-debuggable' not in badging)
    check('no_internet_permission',"uses-permission: name='android.permission.INTERNET'" not in badging)
    check('no_shared_storage_permission',all(p not in badging for p in ('android.permission.WRITE_EXTERNAL_STORAGE','android.permission.READ_EXTERNAL_STORAGE','android.permission.MANAGE_EXTERNAL_STORAGE')))
    check('backup_disabled',bool(re.search(r'android:allowBackup[^\n]*\b0x0\b',xml)))
    check('cleartext_disabled',bool(re.search(r'android:usesCleartextTraffic[^\n]*\b0x0\b',xml)))
    native=[]
    with zipfile.ZipFile(args.apk) as archive:
        check('apk_crc',archive.testzip() is None)
        for name in archive.namelist():
            if name.endswith('libpngx.so'):
                versions=sorted({v.decode('ascii') for v in re.findall(rb'1\.6\.[0-9]{1,2}',archive.read(name))})
                safe=bool(versions) and all(int(v.split('.')[-1])>=59 for v in versions)
                native.append({'path':name,'libpng_versions':versions,'passed':safe})
        check('native_png_security_baseline',bool(native) and all(n['passed'] for n in native),'Requires libpng >=1.6.59; confirmed from binary version strings. Upstream: https://www.libpng.org/pub/png/libpng.html')
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
        'limits':['Known-advisory and automated checks are not proof against all attacks.','Android device/Keystore/OCR native execution and iOS runtime were not tested here.','Native dependency findings block release even where exploitability in this app has not been demonstrated.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'failures':failures,'checks':len(checks)}))
    raise SystemExit(0 if report['passed'] else 1)

if __name__=='__main__':main()
