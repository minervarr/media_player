cmake_minimum_required(VERSION 3.22.1)

project("media_player")

set(ENABLE_SHARED OFF CACHE BOOL "Disable shared jpeg lib" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "Enable static jpeg lib" FORCE)
set(REQUIRE_SIMD OFF CACHE BOOL "Don't require SIMD" FORCE)
set(WITH_SIMD OFF CACHE BOOL "Disable SIMD for simple Android build" FORCE)
set(WITH_TURBOJPEG ON CACHE BOOL "Enable turbojpeg API" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Disable programs" FORCE)
add_subdirectory(libjpeg-turbo)

# Main shared library -- image decoding only (audio moved to :audioengine)
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    image_decoder_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo/src
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -funroll-loops)

target_link_libraries(${CMAKE_PROJECT_NAME}
    turbojpeg-static
    android
    jnigraphics
    log)
