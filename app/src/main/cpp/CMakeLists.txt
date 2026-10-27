# CMakeLists.txt for WepWawet native library
# Builds: ETC2 encoder + DXT decoder + shader transcoder (glslang + SPIRV-Cross)
# for Android arm64-v8a as a single shared library: libwepwawet_etc2.so

cmake_minimum_required(VERSION 3.18.1)

project(wepwawet_etc2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================================
# ETC2 encoder (Google's etc2comp)
# ============================================================================
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec
)

file(GLOB ETC_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec/*.cpp
)

# ============================================================================
# glslang + SPIRV-Cross (for HLSL → GLSL ES shader compilation)
# ============================================================================
# These are fetched from GitHub via CMake FetchContent during the build.
# glslang compiles GLSL/HLSL → SPIR-V, SPIRV-Cross cross-compiles SPIR-V → GLSL ES.
#
# This is used for full shader parity with WE PC's transcoder, which has its
# own HLSL→GLSL converter. Our existing fixShadersForMobile handles most cases
# with regex-based patching; glslang is the fallback for complex shaders.
#
# Build flags to minimize size:
#   ENABLE_OPT=OFF          — don't require SPIRV-Tools optimizer
#   ENABLE_HLSL=ON          — enable HLSL front-end
#   BUILD_SHARED_LIBS=OFF   — build as static libs (linked into our .so)
#   SKIP_SPIRV_TOOLS=ON     — skip SPIRV-Tools dependency
include(FetchContent)

FetchContent_Declare(
    glslang_src
    GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git
    GIT_TAG 14.3.0
    GIT_SHALLOW TRUE
)

# Set glslang build options BEFORE MakeAvailable
set(ENABLE_OPT OFF CACHE BOOL "Disable SPIRV-Tools optimizer" FORCE)
set(ENABLE_HLSL ON CACHE BOOL "Enable HLSL front-end" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs" FORCE)
set(SKIP_SPIRV_TOOLS ON CACHE BOOL "Skip SPIRV-Tools" FORCE)
set(ALLOW_EXTERNAL_SPIRV_TOOLS ON CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(glslang_src)

FetchContent_Declare(
    spirv_cross_src
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Cross.git
    GIT_TAG vulkan-sdk-1.3.296
    GIT_SHALLOW TRUE
)
set(SPIRV_CROSS_SKIP_TESTS ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_SKIP_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(spirv_cross_src)

# ============================================================================
# JNI wrappers
# ============================================================================
set(JNI_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_decoder.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/shader_transcoder_jni.cpp
)

# ============================================================================
# Build the shared library
# ============================================================================
add_library(wepwawet_etc2 SHARED
    ${JNI_SOURCES}
    ${ETC_SOURCES}
)

# Link with ETC2, DXT, glslang, SPIRV-Cross, and Android log
target_link_libraries(wepwawet_etc2
    log
    glslang                   # glslang core (GLSL/HLSL front-end + SPIR-V codegen)
    SPIRV-Tools-opt-cpp       # SPIRV-Cross's GLSL output
    spirv-cross-glsl         # SPIRV-Cross GLSL target
)

# Include paths for shader transcoder
target_include_directories(wepwawet_etc2 PRIVATE
    ${glslang_src_SOURCE_DIR}
    ${spirv_cross_src_SOURCE_DIR}/include
)

# Compiler flags
target_compile_options(wepwawet_etc2 PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-unused-variable -Wno-unused-but-set-variable
    -O2
    -fvisibility=hidden -fvisibility-inlines-hidden
    -fexceptions -frtti
)

target_compile_definitions(wepwawet_etc2 PRIVATE
    ETC2_COMP_STATIC=1
)
