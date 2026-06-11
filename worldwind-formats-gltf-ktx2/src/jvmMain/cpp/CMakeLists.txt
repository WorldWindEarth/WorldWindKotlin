cmake_minimum_required(VERSION 3.22)
project(ktx_bridge LANGUAGES C CXX)

find_package(JNI REQUIRED)

include(FetchContent)
FetchContent_Declare(
    ktx
    GIT_REPOSITORY https://github.com/KhronosGroup/KTX-Software.git
    GIT_TAG       v4.3.2
    GIT_SHALLOW   TRUE
)

set(KTX_FEATURE_TESTS         OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_TOOLS         OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_DOC           OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_GL_UPLOAD     OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_VULKAN        OFF CACHE BOOL "" FORCE)
# KTX1 must stay ON: KTX-Software's exports.def references ktxTexture1_* symbols on every
# platform regardless of the toggle, and disabling it breaks the MSVC link with LNK2001.
set(KTX_FEATURE_KTX1          ON  CACHE BOOL "" FORCE)
set(KTX_FEATURE_KTX2          ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)

# Linking a STATIC libktx into our SHARED libktx_bridge requires every libktx object to be
# position-independent on ELF platforms — Android NDK defaults PIC on, but host JVM
# toolchains (Linux GCC, macOS clang) don't. Set before FetchContent_MakeAvailable.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# astc-encoder ships -ffp-model=precise + -ffp-contract=off; recent clang flags those as
# conflicting and the subproject's -Werror promotes the warning. Downgrade only this class.
# Gate on clang specifically: GCC doesn't recognize `-W[no-]error=overriding-option` and
# errors out, which breaks CMake's FindThreads pthread detection during ktx configure.
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -Wno-error=overriding-option")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=overriding-option")
endif()

FetchContent_MakeAvailable(ktx)

# Shared bridge source — lives in src/jvmCommonMain/cpp/, same JNI symbols as Android.
add_library(ktx_bridge SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../jvmCommonMain/cpp/ktx_bridge.cpp)

target_include_directories(ktx_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${ktx_SOURCE_DIR}/include
)
target_link_libraries(ktx_bridge PRIVATE ktx)

set_target_properties(ktx_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    # Pin Release output to the build root on multi-config generators (MSBuild, Xcode) — see
    # the matching note in worldwind-formats-gltf-draco/src/jvmMain/cpp/CMakeLists.txt.
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
)

# Strip in release on GNU-ld platforms; skip on Apple and MSVC.
if (CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT APPLE AND NOT MSVC)
    target_link_options(ktx_bridge PRIVATE -Wl,--strip-all)
endif()
