cmake_minimum_required(VERSION 3.22.1)

project("media_player")

# libjpeg-turbo 2.1.x as static library (manual build)
set(TJSRC ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo)
add_library(turbojpeg STATIC
    ${TJSRC}/jcapimin.c
    ${TJSRC}/jcapistd.c
    ${TJSRC}/jccoefct.c
    ${TJSRC}/jccolor.c
    ${TJSRC}/jcdctmgr.c
    ${TJSRC}/jchuff.c
    ${TJSRC}/jcicc.c
    ${TJSRC}/jcinit.c
    ${TJSRC}/jcmainct.c
    ${TJSRC}/jcmarker.c
    ${TJSRC}/jcmaster.c
    ${TJSRC}/jcomapi.c
    ${TJSRC}/jcparam.c
    ${TJSRC}/jcphuff.c
    ${TJSRC}/jcprepct.c
    ${TJSRC}/jcsample.c
    ${TJSRC}/jctrans.c
    ${TJSRC}/jdapimin.c
    ${TJSRC}/jdapistd.c
    ${TJSRC}/jdatadst.c
    ${TJSRC}/jdatadst-tj.c
    ${TJSRC}/jdatasrc.c
    ${TJSRC}/jdatasrc-tj.c
    ${TJSRC}/jdcoefct.c
    ${TJSRC}/jdcolor.c
    ${TJSRC}/jddctmgr.c
    ${TJSRC}/jdhuff.c
    ${TJSRC}/jdicc.c
    ${TJSRC}/jdinput.c
    ${TJSRC}/jdmainct.c
    ${TJSRC}/jdmarker.c
    ${TJSRC}/jdmaster.c
    ${TJSRC}/jdmerge.c
    ${TJSRC}/jdphuff.c
    ${TJSRC}/jdpostct.c
    ${TJSRC}/jdsample.c
    ${TJSRC}/jdtrans.c
    ${TJSRC}/jerror.c
    ${TJSRC}/jfdctflt.c
    ${TJSRC}/jfdctfst.c
    ${TJSRC}/jfdctint.c
    ${TJSRC}/jidctflt.c
    ${TJSRC}/jidctfst.c
    ${TJSRC}/jidctint.c
    ${TJSRC}/jidctred.c
    ${TJSRC}/jmemmgr.c
    ${TJSRC}/jmemnobs.c
    ${TJSRC}/jquant1.c
    ${TJSRC}/jquant2.c
    ${TJSRC}/jutils.c
    ${TJSRC}/jaricom.c
    ${TJSRC}/jdarith.c
    ${TJSRC}/jcarith.c
    ${TJSRC}/jsimd_none.c
    ${TJSRC}/transupp.c
    ${TJSRC}/rdbmp.c
    ${TJSRC}/rdppm.c
    ${TJSRC}/wrbmp.c
    ${TJSRC}/wrppm.c
    ${TJSRC}/cdjpeg.c
    ${TJSRC}/turbojpeg.c)

# Config headers come first so they override the .in templates
target_include_directories(turbojpeg PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo-config
    ${TJSRC})

target_compile_definitions(turbojpeg PRIVATE
    BMP_SUPPORTED
    PPM_SUPPORTED)

target_compile_options(turbojpeg PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-shift-negative-value)

# Main shared library -- image decoding only (audio moved to :audioengine)
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    image_decoder_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo-config
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -funroll-loops)

target_link_libraries(${CMAKE_PROJECT_NAME}
    turbojpeg
    android
    jnigraphics
    log)
