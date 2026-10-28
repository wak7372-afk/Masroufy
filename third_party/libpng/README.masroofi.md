This directory contains unmodified build sources from upstream libpng 1.6.59.
The official archive URL, SHA-256 and each imported file's SHA-256 are recorded
in manifest.json. scripts/vendor_png.py reproduces the import from that archive.
pnglibconf.h is upstream scripts/pnglibconf.h.prebuilt, renamed for the compiler.
LICENSE is retained verbatim. The source bytes are preserved by .gitattributes.

The Android app builds these sources with NDK 27.2.12479018 and CMake 3.22.1 for
armeabi-v7a, arm64-v8a, x86 and x86_64. Its shared library retains the libpngx.so
SONAME expected by Tesseract4Android/Leptonica. The build removes only the four
old PNG libraries from the exact SHA-256-pinned Tesseract4Android 4.9.0 AAR; all
other upstream OCR code is unchanged. Duplicate libraries are never pickFirst'd.

scripts/verify_native_png.py compares old/new exported PNG symbols, resolves PNG
imports of all packaged consumers and checks SONAME, 16 KB alignment, stack and
binding hardening on the actual APK. scripts/security_gate.py additionally checks
source hashes, binary versions and that its native audit belongs to the same APK.

Sources: https://github.com/pnggroup/libpng/tree/v1.6.59
Security: https://www.libpng.org/pub/png/libpng.html
Original OCR: https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0
