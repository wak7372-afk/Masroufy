"""Import only build sources from the pinned official libpng archive (no extraction)."""
import hashlib
import json
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parent.parent
SHA256 = '2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5'
SOURCES = ['png.c', 'pngerror.c', 'pngget.c', 'pngmem.c', 'pngpread.c', 'pngread.c',
           'pngrio.c', 'pngrtran.c', 'pngrutil.c', 'pngset.c', 'pngtrans.c', 'pngwio.c',
           'pngwrite.c', 'pngwtran.c', 'pngwutil.c', 'png.h', 'pngconf.h', 'pngpriv.h',
           'pngdebug.h', 'pnginfo.h', 'pngstruct.h', 'LICENSE',
           'arm/arm_init.c', 'arm/filter_neon_intrinsics.c', 'arm/palette_neon_intrinsics.c']

def main():
    archive = Path(sys.argv[1])
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise SystemExit('Pinned libpng source checksum mismatch')
    destination = ROOT / 'third_party/libpng'
    files = {}
    with tarfile.open(archive) as source:
        for name in SOURCES + ['scripts/pnglibconf.h.prebuilt']:
            target_name = 'pnglibconf.h' if name == 'scripts/pnglibconf.h.prebuilt' else name
            member = source.getmember('libpng-1.6.59/' + name)
            if not member.isfile() or member.size > 1_000_000:
                raise SystemExit('Unexpected source archive member')
            data = source.extractfile(member).read()
            target = destination / target_name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            files[target_name] = hashlib.sha256(data).hexdigest()
    manifest = {'version': '1.6.59',
                'source': 'https://codeload.github.com/pnggroup/libpng/tar.gz/refs/tags/v1.6.59',
                'archive_sha256': SHA256, 'files_sha256': files,
                'configuration': 'Unmodified upstream sources and scripts/pnglibconf.h.prebuilt renamed to pnglibconf.h.'}
    (destination / 'manifest.json').write_bytes((json.dumps(manifest, indent=2) + '\n').encode('utf-8'))
    print(f'Imported {len(files)} verified upstream source files')

if __name__ == '__main__':
    main()
