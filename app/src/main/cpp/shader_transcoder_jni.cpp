/*
 * shader_transcoder_jni.cpp — JNI wrapper for glslang + SPIRV-Cross.
 *
 * Provides HLSL → SPIR-V → GLSL ES 3.00 shader transcoding, matching what
 * WE PC's transcoder does internally (wallpaperui.exe has its own HLSL→GLSL
 * converter; we use glslang + SPIRV-Cross as the open-source equivalent).
 *
 * Functions:
 *   nativeTranscodeHlslToGlslEs(srcBytes, shaderStage) -> glslEsBytes
 *   nativeValidateGlslEs(srcBytes, shaderStage) -> boolean
 *
 * shaderStage: 0=vertex, 1=fragment
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>

// glslang includes
#include "glslang/Public/Shader.h"
#include "glslang/Public/ResourceLimits.h"
#include "glslang/Include/Types.h"
#include "SPIRV/GlslangToSpv.h"

// SPIRV-Cross includes
#include "spirv_cross_c.h"

// Default GLSL ES resources (matches Android GLES 3.0 capabilities)
static const TBuiltInResource DefaultResources = {
    /* .maxLights = */ 32,
    /* .maxClipPlanes = */ 6,
    /* .maxTextureUnits = */ 32,
    /* .maxTextureCoords = */ 32,
    /* .maxVertexAttribs = */ 64,
    /* .maxVertexUniformVectors = */ 256,
    /* .maxVaryingFloats = */ 64,
    /* .maxVertexTextureUnits = */ 32,
    /* .maxCombinedTextureUnits = */ 80,
    /* .maxTextureImageUnits = */ 32,
    /* .maxFragmentUniformVectors = */ 256,
    /* .maxDrawBuffers = */ 4,
    /* .maxDualSourceDrawBuffers = */ 0,
    /* .isGLCoreProfile = */ false,
    /* .isESProfile = */ true,  // ← ES profile
    /* .optSourceExtensions = */ nullptr,
    /* .maxClipDistances = */ 8,
    /* .maxGeometryTextureUnits = */ 16,
    /* .maxGeometryOutputVertices = */ 256,
    /* .maxGeometryTotalOutputComponents = */ 1024,
    /* .maxGeometryUniformComponents = */ 1024,
    /* .maxGeometryVaryingComponents = */ 64,
    /* .maxTessControlTextureUnits = */ 16,
    /* .maxTessControlUniformComponents = */ 1024,
    /* .maxTessControlTotalOutputComponents = */ 4096,
    /* .maxTessEvaluationTextureUnits = */ 16,
    /* .maxTessEvaluationUniformComponents = */ 1024,
    /* .maxTessEvaluationTotalOutputComponents = */ 1024,
    /* .maxPatchVertices = */ 32,
    /* .maxTessGenLevel = */ 64,
    /* .maxViewports = */ 16,
    /* .maxComputeWorkGroupCountX = */ 65535,
    /* .maxComputeWorkGroupCountY = */ 65535,
    /* .maxComputeWorkGroupCountZ = */ 65535,
    /* .maxComputeWorkGroupSizeX = */ 1024,
    /* .maxComputeWorkGroupSizeY = */ 1024,
    /* .maxComputeWorkGroupSizeZ = */ 64,
    /* .maxComputeUniformComponents = */ 1024,
    /* .maxComputeTextureUnits = */ 16,
    /* .maxComputeImageUniforms = */ 8,
    /* .maxFragmentImageUniforms = */ 8,
    /* .maxTessControlImageUniforms = */ 8,
    /* .maxTessEvaluationImageUniforms = */ 8,
    /* .maxGeometryImageUniforms = */ 8,
    /* .maxVertexImageUniforms = */ 8,
    /* .combinedImageUniforms = */ true,
    /* .maxImageSamples = */ 8,
    /* .maxMeshOutputVertices = */ 256,
    /* .maxMeshOutputPrimitives = */ 256,
    /* .maxMeshWorkGroupSizeX = */ 128,
    /* .maxMeshWorkGroupSizeY = */ 128,
    /* .maxMeshWorkGroupSizeZ = */ 128,
    /* .maxTaskWorkGroupSizeX = */ 128,
    /* .maxTaskWorkGroupSizeY = */ 128,
    /* .maxTaskWorkGroupSizeZ = */ 128,
    /* .maxMeshOutputMemory = */ 16384,
    /* .maxTaskOutputMemory = */ 16384,
    /* .maxMeshUniformComponents = */ 1024,
    /* .maxTaskUniformComponents = */ 1024,
    /* .limits = */ {
        /* .nonInductiveForLoops = */ 1,
        /* .whileLoops = */ 1,
        /* .doWhileLoops = */ 1,
        /* .generalUniformIndexing = */ 1,
        /* .generalVertexMatrix = */ 1,
        /* .generalSamplerMatrix = */ 1,
        /* .generalUniformMatrix = */ 1,
        /* .generalConstantMatrix = */ 1,
    },
};

// Initialize glslang (must be called once before any compilation)
static bool glslangInitialized = false;
static void ensureGlslangInit() {
    if (!glslangInitialized) {
        glslang::InitializeProcess();
        glslangInitialized = true;
    }
}

extern "C" {

/*
 * Class:     com_wepwawet_pairing_ShaderTranscoder
 * Method:    nativeTranscodeHlslToGlslEs
 * Signature: ([BII)[B
 *
 * Transcodes HLSL shader source to GLSL ES 3.00 via:
 *   HLSL → glslang → SPIR-V → SPIRV-Cross → GLSL ES 3.00
 *
 * Args:
 *   srcBytes    - byte[] of shader source code (HLSL or GLSL)
 *   srcLen       - length of source
 *   shaderStage  - 0=vertex, 1=fragment
 *   isHlsl       - 1 if source is HLSL, 0 if GLSL
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
    const char* stageName = (shaderStage == 0) ? "vertex" : "fragment";

    // Create glslang shader object
    glslang::TShader shader(stage);
    const char* sourceCStr = shaderSource.c_str();
    int sourceLen = (int)shaderSource.length();
    shader.setStringsWithLengths(&sourceCStr, &sourceLen, 1);
    shader.setEnvInput(isHlsl ? glslang::EShSourceHlsl : glslang::EShSourceGlsl,
                       stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv_1_0);

    // Set message options
    shader.setMessages(
        EShMsgDefault |
        EShMsgReadHlsl |
        EShMsgHlslOffsets |
        (isHlsl ? EShMsgReadHlsl : EShMsgDefault)
    );

    // Compile to SPIR-V
    std::string infoLog;
    std::vector<unsigned int> spirv;
    bool compiled = shader.parse(&DefaultResources, 100, false,
                                  EShMsgDefault, &infoLog);

    if (!compiled) {
        // Compilation failed — return null (caller falls back to regex-based fixes)
        env->ReleaseByteArrayElements(srcBytes, src, JNI_ABORT);
        return nullptr;
    }

    // Link (for single-shader compilation, this is trivial)
    glslang::TProgram program;
    program.addShader(&shader);
    bool linked = program.link(EShMsgDefault);
    if (!linked) {
        return nullptr;
    }

    // Generate SPIR-V
    glslang::GlslangToSpv(*program.getIntermediate(stage), spirv);
    if (spirv.empty()) {
        return nullptr;
    }

    // Cross-compile SPIR-V to GLSL ES 3.00 using SPIRV-Cross
    spvc_context context = nullptr;
    spvc_parsed_ir ir = nullptr;
    spvc_compiler compiler = nullptr;
    spvc_compiler_options options = nullptr;
    const char* glslResult = nullptr;

    if (spvc_context_create(&context) != SPVC_SUCCESS) return nullptr;
    if (spvc_context_parse_spirv(context, spirv.data(), spirv.size(), &ir) != SPVC_SUCCESS) {
        spvc_context_destroy(context);
        return nullptr;
    }
    if (spvc_context_create_compiler(context, SPVC_BACKEND_GLSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler) != SPVC_SUCCESS) {
        spvc_context_destroy(context);
        return nullptr;
    }
    if (spvc_compiler_create_compiler_options(compiler, &options) != SPVC_SUCCESS) {
        spvc_context_destroy(context);
        return nullptr;
    }
    spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, 300);
    spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES, SPVC_TRUE);
    spvc_compiler_install_compiler_options(compiler, options);

    if (spvc_compiler_compile(compiler, &glslResult) != SPVC_SUCCESS) {
        spvc_context_destroy(context);
        return nullptr;
    }

    // Copy result to Java byte array
    jsize resultLen = (jsize)strlen(glslResult);
    jbyteArray result = env->NewByteArray(resultLen);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, resultLen, (const jbyte*)glslResult);
    }

    spvc_context_destroy(context);
    return result;
}

} // extern "C"
