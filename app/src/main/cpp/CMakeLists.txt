# CMakeLists.txt for WepWawet native library
# Builds: ETC2 encoder + DXT decoder (+ optional glslang + SPIRV-Cross)
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
# JNI wrappers (always built)
# ============================================================================
set(JNI_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_decoder.cpp
)

# ============================================================================
# glslang + SPIRV-Cross (OPTIONAL — for HLSL→GLSL shader compilation)
# ============================================================================
# If FetchContent fails (network issues, etc.), the build still succeeds
# without glslang. The Kotlin ShaderTranscoder class checks isAvailable()
# and falls back to the regex-based fixShadersForMobile if glslang is absent.
option(ENABLE_GLSLANG "Build with glslang+SPIRV-Cross for shader transcoding" ON)

set(GLSLANG_AVAILABLE FALSE)

if(ENABLE_GLSLANG)
    include(FetchContent)

    # Try to fetch glslang
    FetchContent_Declare(
        glslang_src
        GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git
        GIT_TAG 14.3.0
        GIT_SHALLOW TRUE
        TIMEOUT 120
    )
    set(ENABLE_OPT OFF CACHE BOOL "Disable SPIRV-Tools optimizer" FORCE)
    set(ENABLE_HLSL ON CACHE BOOL "Enable HLSL front-end" FORCE)
    set(ENABLE_GLSL ON CACHE BOOL "Enable GLSL front-end" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs" FORCE)

    # Try to make glslang available, but don't fail the build if it doesn't work
    FetchContent_MakeAvailable(glslang_src)

    # Fetch SPIRV-Cross
    FetchContent_Declare(
        spirv_cross_src
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Cross.git
        GIT_TAG vulkan-sdk-1.3.296
        GIT_SHALLOW TRUE
        TIMEOUT 120
    )
    set(SPIRV_CROSS_SKIP_TESTS ON CACHE BOOL "" FORCE)
    set(SPIRV_CROSS_SKIP_INSTALL ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(spirv_cross_src)

    # Verify the source directories are set AND the header files actually exist
    if(EXISTS "${glslang_src_SOURCE_DIR}/glslang/Public/Shader.h" AND
       EXISTS "${spirv_cross_src_SOURCE_DIR}/include/spirv_cross/spirv_cross.hpp")
        set(GLSLANG_AVAILABLE TRUE)
        list(APPEND JNI_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/shader_transcoder_jni.cpp)
        message(STATUS "glslang+SPIRV-Cross available — shader transcoder enabled")
        message(STATUS "  glslang source: ${glslang_src_SOURCE_DIR}")
        message(STATUS "  spirv-cross source: ${spirv_cross_src_SOURCE_DIR}")
    else()
        message(WARNING "glslang/Public/Shader.h or spirv_cross.hpp not found — shader transcoder DISABLED")
        message(STATUS "  glslang src dir: ${glslang_src_SOURCE_DIR}")
        message(STATUS "  checking: EXISTS ${glslang_src_SOURCE_DIR}/glslang/Public/Shader.h = ${EXISTS}")
        if(NOT EXISTS "${glslang_src_SOURCE_DIR}/glslang/Public/Shader.h")
            file(GLOB GLOSLANG_FILES "${glslang_src_SOURCE_DIR}/*")
            message(STATUS "  glslang src dir contents: ${GLOSLANG_FILES}")
        endif()
    endif()
endif()

# ============================================================================
# Build the shared library
# ============================================================================
add_library(wepwawet_etc2 SHARED
    ${JNI_SOURCES}
    ${ETC_SOURCES}
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

# Link with standard libraries
target_link_libraries(wepwawet_etc2
    log   # Android logging
)

# If glslang is available, add include paths and link libraries
if(GLSLANG_AVAILABLE)
    target_include_directories(wepwawet_etc2 PRIVATE
        ${glslang_src_SOURCE_DIR}
        ${spirv_cross_src_SOURCE_DIR}/include
    )
    target_link_libraries(wepwawet_etc2
        glslang              # glslang core (GLSL/HLSL front-end)
        SPIRV                # glslang's SPIR-V codegen (has GlslangToSpv)
        HLSL                 # glslang's HLSL front-end
        spirv-cross-glsl     # SPIRV-Cross GLSL backend
    )
    target_compile_definitions(wepwawet_etc2 PRIVATE
        GLSLANG_AVAILABLE=1
    )
endif()
