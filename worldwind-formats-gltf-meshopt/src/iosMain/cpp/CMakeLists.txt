cmake_minimum_required(VERSION 3.22)
project(meshopt_bridge LANGUAGES C CXX)

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

add_library(meshopt_bridge STATIC meshopt_bridge.cpp)

target_include_directories(meshopt_bridge PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${meshoptimizer_SOURCE_DIR}/src
)
target_link_libraries(meshopt_bridge PUBLIC meshoptimizer)

set_target_properties(meshopt_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
