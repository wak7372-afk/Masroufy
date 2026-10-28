"""Verify ABI compatibility and hardening of the rebuilt PNG in the actual APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parent.parent
ABIS=('armeabi-v7a','arm64-v8a','x86','x86_64')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--original-aar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if hashlib.sha256(args.original_aar.read_bytes()).hexdigest()!='bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a':
        raise SystemExit('Unexpected upstream OCR binary')
    tool=ROOT/'.tools/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    def readelf(path,flag):
        return subprocess.run([str(tool),'-W',flag,str(path)],check=True,capture_output=True,text=True).stdout
    def symbols(path,undefined=False):
        found=set()
        for line in readelf(path,'--dyn-syms').splitlines():
            fields=line.split()
            if len(fields)>=8 and fields[4] in ('GLOBAL','WEAK') and (fields[6]=='UND')==undefined:
                name=fields[7].split('@')[0]
                if name.startswith('png_'):found.add(name)
        return found
    results=[]
    with tempfile.TemporaryDirectory(prefix='masroofi-native-audit-') as temp, zipfile.ZipFile(args.apk) as apk, zipfile.ZipFile(args.original_aar) as original:
        base=Path(temp)
        for abi in ABIS:
            before=base/(abi+'-old.so');before.write_bytes(original.read(f'jni/{abi}/libpngx.so'))
            current=base/(abi+'-new.so');data=apk.read(f'lib/{abi}/libpngx.so');current.write_bytes(data)
            exports=symbols(current)
            missing_old=sorted(symbols(before)-exports)
            imports=set()
            for name in apk.namelist():
                if name.startswith(f'lib/{abi}/') and name.endswith('.so') and not name.endswith('/libpngx.so'):
                    consumer=base/'consumer.so';consumer.write_bytes(apk.read(name))
                    imports|=symbols(consumer,True)
            dynamic=readelf(current,'--dynamic')
            headers=readelf(current,'--program-headers')
            loads=[line.split() for line in headers.splitlines() if line.strip().startswith('LOAD ')]
            aligned=bool(loads) and all(int(fields[-1],16)>=16384 for fields in loads)
            stack=[line for line in headers.splitlines() if 'GNU_STACK' in line]
            non_executable_stack=bool(stack) and not any(re.search(r'\bRWE\b',line) for line in stack)
            soname='Library soname: [libpngx.so]' in dynamic
            version=b'1.6.59' in data and b'1.6.48' not in data
            relro='GNU_RELRO' in headers
            passed=not missing_old and imports<=exports and aligned and non_executable_stack and soname and version and 'BIND_NOW' in dynamic and relro
            results.append({'abi':abi,'sha256':hashlib.sha256(data).hexdigest(),'version':'1.6.59' if version else 'unexpected',
                            'exported_png_symbols':len(exports),'required_png_symbols':len(imports),
                            'missing_previous_exports':missing_old,'unresolved_png_imports':sorted(imports-exports),
                            'soname_compatible':soname,'load_alignment_16k':aligned,'non_executable_stack':non_executable_stack,
                            'bind_now':'BIND_NOW' in dynamic,'relro':relro,'passed':passed})
    report={'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'passed':all(r['passed'] for r in results),'abis':results,
            'limits':'Static ELF/ABI verification; actual device tests are recorded separately.'}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['passed'] else 1)

if __name__=='__main__':main()
