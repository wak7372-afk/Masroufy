cmake_minimum_required(VERSION 3.22.1)
project(MasroofiPng LANGUAGES C)

# Keep the ABI and SONAME expected by Tesseract4Android/Leptonica, while replacing
# its bundled 1.6.48 with unmodified upstream 1.6.59 on every supported ABI.
set(PNG_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/libpng")
set(PNG_FILES png.c pngerror.c pngget.c pngmem.c pngpread.c pngread.c pngrio.c
    pngrtran.c pngrutil.c pngset.c pngtrans.c pngwio.c pngwrite.c pngwtran.c pngwutil.c
    arm/arm_init.c arm/filter_neon_intrinsics.c arm/palette_neon_intrinsics.c)
list(TRANSFORM PNG_FILES PREPEND "${PNG_SOURCE}/")
add_library(pngx SHARED ${PNG_FILES})
target_include_directories(pngx PRIVATE "${PNG_SOURCE}")
target_link_libraries(pngx PRIVATE z m)
target_link_options(pngx PRIVATE "-Wl,-z,relro,-z,now" "-Wl,-z,max-page-size=16384")
