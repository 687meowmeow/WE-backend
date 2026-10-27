/*
 * shader_transcoder_jni.cpp — JNI wrapper for glslang + SPIRV-Cross.
 *
 * Conditionally compiled only when GLSLANG_AVAILABLE=1 is defined (set by
 * CMakeLists.txt when FetchContent successfully fetches glslang+SPIRV-Cross).
 *
 * Compiles GLSL/HLSL shaders to SPIR-V via glslang, then cross-compiles
 * to GLSL ES 3.00 via SPIRV-Cross's C++ API.
 */

#ifdef GLSLANG_AVAILABLE

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>

// glslang includes — the include path is set in CMakeLists.txt to ${glslang_src_SOURCE_DIR}
#include "glslang/Public/Shader.h"
#include "glslang/Public/ResourceLimits.h"
#include "SPIRV/GlslangToSpv.h"

// SPIRV-Cross C++ API — include path set to ${spirv_cross_src_SOURCE_DIR}/include
#include "spirv_cross/spirv_cross.hpp"

extern "C" {

// Initialize glslang (must be called once before any compilation)
static bool glslangInitialized = false;
static void ensureGlslangInit() {
    if (!glslangInitialized) {
        glslang::InitializeProcess();
        glslangInitialized = true;
    }
}

/*
 * Class:     com_wepwawet_pairing_ShaderTranscoder
 * Method:    nativeTranscodeShader
 * Signature: ([BIZ)[B
 *
 * Transcodes shader source (HLSL or GLSL) to GLSL ES 3.00 via:
 *   source → glslang (compile to SPIR-V) → SPIRV-Cross (cross-compile to GLSL ES)
 *
 * Args:
 *   srcBytes    - byte[] of shader source code
 *   shaderStage - 0=vertex, 1=fragment
 *   isHlsl      - true if source is HLSL, false if GLSL
 *
 * Returns: byte[] of GLSL ES 3.00 source, or null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_ShaderTranscoder_nativeTranscodeShader(
    JNIEnv *env, jclass /* cls */,
    jbyteArray srcBytes, jint shaderStage, jboolean isHlsl) {

    ensureGlslangInit();

    // Get source bytes
    jsize srcLen = env->GetArrayLength(srcBytes);
    if (srcLen <= 0) return nullptr;
    jbyte* src = env->GetByteArrayElements(srcBytes, nullptr);
    if (src == nullptr) return nullptr;

    std::string shaderSource((const char*)src, srcLen);
    env->ReleaseByteArrayElements(srcBytes, src, JNI_ABORT);

    // Determine shader stage
    EShLanguage stage = (shaderStage == 0) ? EShLangVertex : EShLangFragment;

    // Create glslang shader object
    glslang::TShader shader(stage);
    const char* sourceCStr = shaderSource.c_str();
    int sourceLen = (int)shaderSource.length();
    shader.setStringsWithLengths(&sourceCStr, &sourceLen, 1);

    // Set source language (HLSL or GLSL) and target (SPIR-V 1.0)
    shader.setEnvInput(isHlsl ? glslang::EShSourceHlsl : glslang::EShSourceGlsl,
                       stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv_1_0);

    // Compile to SPIR-V
    std::string infoLog;
    const TBuiltInResource* resources = GetDefaultResources();
    bool compiled = shader.parse(resources, 100, false,
                                  EShMsgDefault, &infoLog);

    if (!compiled) {
        return nullptr;  // Compilation failed — caller falls back to regex-based fixes
    }

    // Link (for single-shader compilation, this is trivial)
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(EShMsgDefault)) {
        return nullptr;
    }

    // Generate SPIR-V
    std::vector<unsigned int> spirv;
    glslang::GlslangToSpv(*program.getIntermediate(stage), spirv);
    if (spirv.empty()) {
        return nullptr;
    }

    // Cross-compile SPIR-V to GLSL ES 3.00 using SPIRV-Cross C++ API
    spirv_cross::CompilerGLSL compiler(std::move(spirv));

    spirv_cross::CompilerGLSL::Options options;
    options.version = 300;       // GLSL ES 3.00
    options.es = true;           // ES profile
    options.vulkan_semantics = false;  // Use GL semantics (not Vulkan)
    compiler.set_options(options);

    std::string glslResult;
    try {
        glslResult = compiler.compile();
    } catch (...) {
        return nullptr;  // Cross-compilation failed
    }

    if (glslResult.empty()) {
        return nullptr;
    }

    // Copy result to Java byte array
    jsize resultLen = (jsize)glslResult.length();
    jbyteArray result = env->NewByteArray(resultLen);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, resultLen,
            reinterpret_cast<const jbyte*>(glslResult.c_str()));
    }

    return result;
}

} // extern "C"

#endif // GLSLANG_AVAILABLE
