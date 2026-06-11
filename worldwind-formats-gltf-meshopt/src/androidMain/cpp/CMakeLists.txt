cmake_minimum_required(VERSION 3.22)
project(meshopt_bridge LANGUAGES C CXX)

# Arseny Kapoulkine's meshoptimizer — fast-decode mesh compression. MIT licensed.
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

FetchContent_MakeAvailable(meshoptimizer)

# Bridge .cpp lives under src/jvmCommonMain/cpp/ — shared with the JVM target's CMakeLists.
add_library(meshopt_bridge SHARED ../../jvmCommonMain/cpp/meshopt_bridge.cpp)

target_include_directories(meshopt_bridge PRIVATE
    ${meshoptimizer_SOURCE_DIR}/src
)
target_link_libraries(meshopt_bridge PRIVATE meshoptimizer android log)

set_target_properties(meshopt_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

if (CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(meshopt_bridge PRIVATE -Wl,--strip-all)
endif()
