cmake_minimum_required(VERSION 3.22)
project(draco_bridge LANGUAGES C CXX)

# iOS native build. Produces libdraco.a (from FetchContent) and libdraco_bridge.a together.
# Driven by cmakeBuildIos_<target> Gradle tasks that pass the right SDK + architecture.

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

FetchContent_MakeAvailable(draco)

# Our C wrapper, statically linked against libdraco. The .h goes through cinterop's
# header parsing; the .cpp implementation links into the final iOS framework.
add_library(draco_bridge STATIC draco_bridge.cpp)

target_include_directories(draco_bridge PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${draco_SOURCE_DIR}/src
    ${draco_BINARY_DIR}
    ${CMAKE_BINARY_DIR}
)
target_link_libraries(draco_bridge PUBLIC draco)

set_target_properties(draco_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
