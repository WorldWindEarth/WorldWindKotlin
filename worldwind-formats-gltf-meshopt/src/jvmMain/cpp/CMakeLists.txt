cmake_minimum_required(VERSION 3.22)
project(meshopt_bridge LANGUAGES C CXX)

find_package(JNI REQUIRED)

include(FetchContent)
FetchContent_Declare(
    meshoptimizer
    GIT_REPOSITORY https://github.com/zeux/meshoptimizer.git
    GIT_TAG       v0.21
    GIT_SHALLOW   TRUE
)

set(MESHOPT_BUILD_DEMO         OFF CACHE BOOL "" FORCE)
set(MESHOPT_BUILD_GLTFPACK     OFF CACHE BOOL "" FORCE)
set(MESHOPT_BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(MESHOPT_INSTALL            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS          OFF CACHE BOOL "" FORCE)

# Linking STATIC meshoptimizer into our SHARED libmeshopt_bridge requires PIC on ELF
# platforms — NDK defaults it on, host JVM toolchains (Linux GCC, macOS clang) don't.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

FetchContent_MakeAvailable(meshoptimizer)

# Shared bridge source — lives in src/jvmCommonMain/cpp/, same JNI symbols as Android.
add_library(meshopt_bridge SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../jvmCommonMain/cpp/meshopt_bridge.cpp)

target_include_directories(meshopt_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${meshoptimizer_SOURCE_DIR}/src
)
target_link_libraries(meshopt_bridge PRIVATE meshoptimizer)

set_target_properties(meshopt_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    # Pin Release output to the build root on multi-config generators (MSBuild, Xcode) — see
    # the matching note in worldwind-formats-gltf-draco/src/jvmMain/cpp/CMakeLists.txt.
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
)

# Strip in release on GNU-ld platforms; skip on Apple and MSVC.
if (CMAKE_BUILD_TYPE STREQUAL "Release" AND NOT APPLE AND NOT MSVC)
    target_link_options(meshopt_bridge PRIVATE -Wl,--strip-all)
endif()
