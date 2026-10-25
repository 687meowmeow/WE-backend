# CMakeLists.txt for ETC2 encoder native library
# Builds Google's etc2comp as a shared library for Android arm64-v8a
# Exposes a single JNI function: nativeEncodeEtc2Rgba8

cmake_minimum_required(VERSION 3.18.1)

project(wepwawet_etc2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include paths for the ETC2 library
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec
)

# Collect all ETC2 source files (excluding EtcTool which is the CLI binary)
file(GLOB ETC_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec/*.cpp
)

# Add our JNI wrapper
set(JNI_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2_jni.cpp
)

# Build the shared library
add_library(wepwawet_etc2 SHARED
    ${JNI_SOURCES}
    ${ETC_SOURCES}
)

# Link with standard libraries
# Note: Android doesn't have a separate pthread library — pthread is part of libc
target_link_libraries(wepwawet_etc2
    log   # Android logging
)

# Compiler flags
target_compile_options(wepwawet_etc2 PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-unused-variable -Wno-unused-but-set-variable
    -O2  # Optimization for better encoding speed
    -fvisibility=hidden -fvisibility-inlines-hidden
    -fexceptions -frtti
)

# Strip debug symbols in release builds to reduce .so size
target_compile_definitions(wepwawet_etc2 PRIVATE
    ETC2_COMP_STATIC=1
)
