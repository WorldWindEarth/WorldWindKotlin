cmake_minimum_required(VERSION 3.22)
project(draco_bridge LANGUAGES C CXX)

# JVM target: builds for the host (Linux x86_64, macOS arm64/x86_64, Windows x86_64). The
# JNI bridge source is shared with the Android target — single source of truth at
# ../../jvmCommonMain/cpp/draco_bridge.cpp; an #ifdef __ANDROID__ swaps the logging backend.
find_package(JNI REQUIRED)

include(FetchContent)
FetchContent_Declare(
    draco
    GIT_REPOSITORY https://github.com/google/draco.git
    GIT_TAG       1.5.7
    GIT_SHALLOW   TRUE
)

set(DRACO_DECODER_ONLY     ON  CACHE BOOL "Build decoder only" FORCE)
set(DRACO_BACKWARDS_COMPATIBILITY OFF CACHE BOOL "" FORCE)
set(DRACO_TESTS            OFF CACHE BOOL "" FORCE)
set(DRACO_MAYA_PLUGIN      OFF CACHE BOOL "" FORCE)
set(DRACO_UNITY_PLUGIN     OFF CACHE BOOL "" FORCE)
set(DRACO_TRANSCODER_SUPPORTED OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# Linking a STATIC lib into our SHARED libdraco_bridge requires every Draco object to be
# position-independent on ELF platforms; otherwise the linker rejects R_X86_64_PC32
# relocations against vtable symbols. Android's NDK defaults PIC on, but the host JVM
# toolchains (Linux GCC, macOS clang) don't — set it explicitly before the FetchContent
# subdirectory so it propagates to every Draco OBJECT/STATIC target.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Draco 1.5.7's CMakeLists still uses FindPythonInterp (removed in CMake 3.27); silence the
# CMP0148 deprecation warning by forcing the policy default OLD across the subdirectory.
set(CMAKE_POLICY_DEFAULT_CMP0148 OLD)

FetchContent_MakeAvailable(draco)

# Shared source — bridge .cpp lives in src/jvmCommonMain/cpp/ and compiles for both targets
# via the __ANDROID__ ifdef (Android uses android/log, JVM uses stderr).
add_library(draco_bridge SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../jvmCommonMain/cpp/draco_bridge.cpp)

target_include_directories(draco_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${draco_SOURCE_DIR}/src
    ${draco_BINARY_DIR}
    ${CMAKE_BINARY_DIR}
)
# Draco 1.5.7 names its static-lib target differently per toolchain: `draco` on MSVC,
# `draco_static` on every other compiler (both with OUTPUT_NAME=draco, so the file is
# `draco.lib` / `libdraco.a` respectively). Linking the target — not the file path — lets
# CMake resolve the right per-generator output location on Make, Ninja, MSBuild, and Xcode.
if (MSVC)
    target_link_libraries(draco_bridge PRIVATE draco)
else()
    target_link_libraries(draco_bridge PRIVATE draco_static)
endif()

set_target_properties(draco_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    # Pin Release output to the build root on multi-config generators (MSBuild, Xcode) —
    # they default to a per-config subdir (Release/, Debug/...), but the Gradle Copy task in
    # buildDracoBridgeJvm expects the predictable `<build_dir>/<libname>` path that Unix
    # Makefiles/Ninja use natively.
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
)

# Strip in release on GNU-ld platforms; skip on Apple (uses `strip` post-link) and MSVC.
if (CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT APPLE AND NOT MSVC)
    target_link_options(draco_bridge PRIVATE -Wl,--strip-all)
endif()
