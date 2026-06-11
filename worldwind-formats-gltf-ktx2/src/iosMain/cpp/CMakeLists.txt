cmake_minimum_required(VERSION 3.22)
project(ktx_bridge LANGUAGES C CXX)

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
set(KTX_FEATURE_KTX1          OFF CACHE BOOL "" FORCE)
set(KTX_FEATURE_KTX2          ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(ktx)

# KTX-Software defaults to wrapping libktx and libktx_read as `.framework` bundles on Apple
# platforms (the output lands at `_deps/ktx-build/ktx.framework/ktx` with no `.a` extension).
# Kotlin/Native's cinterop `-libraryPath` search only matches `lib<name>.a`, so it can't
# resolve the framework-wrapped lib and fails with "Could not find 'libktx.a' binary". Force
# plain static-lib output by clearing the FRAMEWORK property on each ktx target that exists.
if (APPLE)
    foreach(t ktx ktx_read)
        if (TARGET ${t})
            set_target_properties(${t} PROPERTIES FRAMEWORK FALSE)
        endif()
    endforeach()
endif()

add_library(ktx_bridge STATIC ktx_bridge.cpp)

target_include_directories(ktx_bridge PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ktx_SOURCE_DIR}/include
)
target_link_libraries(ktx_bridge PUBLIC ktx)

set_target_properties(ktx_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
