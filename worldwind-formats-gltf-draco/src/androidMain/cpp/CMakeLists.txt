cmake_minimum_required(VERSION 3.22)
project(draco_bridge LANGUAGES C CXX)

# Google's open-source libdraco. Apache 2.0. Pinned to a stable release tag.
include(FetchContent)
FetchContent_Declare(
    draco
    GIT_REPOSITORY https://github.com/google/draco.git
    GIT_TAG       1.5.7
    GIT_SHALLOW   TRUE
)

# Decoder-only build — skips Maya plugin, transcoder, JS / Unity / WASM glue. About a 3×
# reduction in static-lib size vs. the default Draco build.
set(DRACO_DECODER_ONLY     ON  CACHE BOOL "Build decoder only" FORCE)
set(DRACO_BACKWARDS_COMPATIBILITY OFF CACHE BOOL "Disable v1.4.x backcompat" FORCE)
set(DRACO_TESTS            OFF CACHE BOOL "No upstream tests" FORCE)
set(DRACO_MAYA_PLUGIN      OFF CACHE BOOL "" FORCE)
set(DRACO_UNITY_PLUGIN     OFF CACHE BOOL "" FORCE)
set(DRACO_TRANSCODER_SUPPORTED OFF CACHE BOOL "" FORCE)
# Static-link Draco into our bridge so the AAR ships exactly one .so per ABI.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# Draco 1.5.7's CMakeLists still uses FindPythonInterp (removed in CMake 3.27); silence the
# CMP0148 deprecation warning by forcing the policy default OLD across the subdirectory.
set(CMAKE_POLICY_DEFAULT_CMP0148 OLD)

FetchContent_MakeAvailable(draco)

# Our thin JNI bridge. Its symbol mangling matches earth.worldwind.formats.gltf.draco.NativeDraco
# in the Kotlin side; renaming the Kotlin class requires renaming the JNI symbols here. The .cpp
# lives under src/jvmCommonMain/cpp/ — shared with the JVM target's CMakeLists.
add_library(draco_bridge SHARED ../../jvmCommonMain/cpp/draco_bridge.cpp)

# Draco's public headers live under draco/ in the source tree; the configure-time generated
# `draco/draco_features.h` lands at ${CMAKE_BINARY_DIR}/draco/ (the OUTER project's root,
# not draco's own ${draco_BINARY_DIR}) — both paths need to be visible to the bridge.
target_include_directories(draco_bridge PRIVATE
    ${draco_SOURCE_DIR}/src
    ${draco_BINARY_DIR}
    ${CMAKE_BINARY_DIR}
)
# Draco 1.5.7's CMakeLists names its static-lib target `draco` on MSVC and `draco_static`
# on every other compiler — NDK clang is non-MSVC, so link the `draco_static` target. CMake
# resolves the actual output file (libdraco.a) per-generator.
target_link_libraries(draco_bridge PRIVATE draco_static android log)

# C++17 — Draco's source requires it.
set_target_properties(draco_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Strip in release for the smallest .so footprint per ABI.
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(draco_bridge PRIVATE -Wl,--strip-all)
endif()
