# CMakeLists.txt for WepWawet native library
# Builds: ETC2 encoder + DXT decoder + shader transcoder (glslang + SPIRV-Cross)
# for Android arm64-v8a as a single shared library: libwepwawet_etc2.so

cmake_minimum_required(VERSION 3.18.1)

project(wepwawet_etc2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)  # glslang 14.x requires C++17
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
include(FetchContent)

# glslang — GLSL/HLSL → SPIR-V compiler
FetchContent_Declare(
    glslang_src
    GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git
    GIT_TAG 14.3.0
    GIT_SHALLOW TRUE
)
# glslang build options (set BEFORE MakeAvailable)
set(ENABLE_OPT OFF CACHE BOOL "Disable SPIRV-Tools optimizer" FORCE)
set(ENABLE_HLSL ON CACHE BOOL "Enable HLSL front-end" FORCE)
set(ENABLE_GLSL ON CACHE BOOL "Enable GLSL front-end" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs" FORCE)
set(SKIP_SPIRV_TOOLS ON CACHE BOOL "Skip SPIRV-Tools" FORCE)
set(ALLOW_EXTERNAL_SPIRV_TOOLS ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(glslang_src)

# SPIRV-Cross — SPIR-V → GLSL/Metal/HLSL cross-compiler
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

# Include paths for shader transcoder
# glslang headers are at ${glslang_src_SOURCE_DIR}/glslang/Public/Shader.h
# SPIRV-Cross headers are at ${spirv_cross_src_SOURCE_DIR}/include/spirv_cross_c.h
target_include_directories(wepwawet_etc2 PRIVATE
    ${glslang_src_SOURCE_DIR}
    ${spirv_cross_src_SOURCE_DIR}/include
)

# Link with ETC2, glslang, SPIRV-Cross, and Android log
target_link_libraries(wepwawet_etc2
    log
    glslang              # glslang core (GLSL/HLSL front-end)
    SPIRV                # glslang's SPIR-V codegen (has GlslangToSpv)
    HLSL                 # glslang's HLSL front-end
    spirv-cross-core     # SPIRV-Cross core
    spirv-cross-glsl     # SPIRV-Cross GLSL backend
    spirv-cross-c        # SPIRV-Cross C API
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
