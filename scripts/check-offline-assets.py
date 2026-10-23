"""Check bundled OCR weights and native ABI metadata in a built APK; no network."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('apk',type=Path); args=parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        for notice in ('LICENSE.txt','NOTICE.txt','LEPTONICA-LICENSE.txt','LIBJPEG-README.txt','LIBPNG-LICENSE.txt'):
            if not apk.read('assets/ocr/'+notice).strip(): raise RuntimeError('Missing OCR licence notice')
        manifest=json.loads(apk.read('assets/ocr/manifest.json').decode('utf-8-sig'))
        if {a['name'] for a in manifest['files']} != {'eng.traineddata','ara.traineddata'}: raise RuntimeError('Missing language')
        for asset in manifest['files']:
            data=apk.read('assets/ocr/'+asset['name'])
            if len(data)!=asset['bytes'] or hashlib.sha256(data).hexdigest()!=asset['sha256']: raise RuntimeError('OCR weight mismatch')
        native=[name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
        abis=sorted({name.split('/')[1] for name in native})
        alignments={}
        for name in native:
            data=apk.read(name)
            if data[:4]!=b'\x7fELF': raise RuntimeError('Bad native binary')
            if data[4]==2:
                offset=struct.unpack_from('<Q',data,32)[0]; size,count=struct.unpack_from('<HH',data,54)
                values=[struct.unpack_from('<Q',data,offset+i*size+48)[0] for i in range(count) if struct.unpack_from('<I',data,offset+i*size)[0]==1]
                alignments[name]=min(values)
        print(json.dumps({'apk':str(args.apk),'bytes':args.apk.stat().st_size,'bundled_languages':len(manifest['files']),'ocr_weight_bytes':sum(a['bytes'] for a in manifest['files']),'abis':abis,'elf64_min_alignment':min(alignments.values()),'ocr_sha256_verified':True},ensure_ascii=False))

if __name__=='__main__': main()
