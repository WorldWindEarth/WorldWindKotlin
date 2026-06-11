cmake_minimum_required(VERSION 3.22)
project(ktx_bridge LANGUAGES C CXX)

# KTX-Software (libktx) — KTX2 container + Basis Universal transcoder. Apache 2.0.
include(FetchContent)
FetchContent_Declare(
    ktx
    GIT_REPOSITORY https://github.com/KhronosGroup/KTX-Software.git
    GIT_TAG       v4.3.2
    GIT_SHALLOW   TRUE
)

# Disable everything we don't need. KTX1 must stay ON because KTX-Software's exports.def
# still references ktxTexture1_* symbols on every platform regardless of the toggle —
# turning it off breaks the MSVC link with LNK2001.
set(KTX_FEATURE_TESTS         OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_TOOLS         OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_DOC           OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_GL_UPLOAD     OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_VULKAN        OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_KTX1          ON  CACHE BOOL "" FORCE)
set(KTX_FEATURE_KTX2          ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)

# astc-encoder (a transitive dep) ships -ffp-model=precise + -ffp-contract=off; recent
# clang (Android NDK 30 onward) flags those as a conflict, and astc-encoder builds with
# -Werror so it'd promote to a hard error. Downgrade only this specific class so the
# rest of -Werror stays intact. Must be set before FetchContent materializes ktx.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -Wno-error=overriding-option")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=overriding-option")

FetchContent_MakeAvailable(ktx)

# Bridge .cpp lives under src/jvmCommonMain/cpp/ — shared with the JVM target's CMakeLists.
add_library(ktx_bridge SHARED ../../jvmCommonMain/cpp/ktx_bridge.cpp)

target_include_directories(ktx_bridge PRIVATE
    ${ktx_SOURCE_DIR}/include
)
target_link_libraries(ktx_bridge PRIVATE ktx android log)

set_target_properties(ktx_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

if (CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(ktx_bridge PRIVATE -Wl,--strip-all)
endif()
