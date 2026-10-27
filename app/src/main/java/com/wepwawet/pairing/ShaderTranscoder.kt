package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger

/**
 * Native shader transcoder using glslang + SPIRV-Cross.
 *
 * Compiles HLSL/GLSL shaders to SPIR-V via glslang, then cross-compiles
 * to GLSL ES 3.00 via SPIRV-Cross. This matches what WE PC's transcoder
 * does internally (wallpaperui.exe has its own HLSL→GLSL converter; we
 * use the open-source Khronos Group tools as the equivalent).
 *
 * Used as a FALLBACK in fixShadersForMobile — the regex-based patching
 * handles most GLSL ES compatibility issues. This is used for complex
 * shaders that can't be fixed with regex (e.g., pure HLSL shaders).
 *
 * Native library: libwepwawet_etc2.so (shared with ETC2 encoder + DXT decoder,
 * built from app/src/main/cpp/ via CMakeLists.txt which fetches glslang +
 * SPIRV-Cross via FetchContent).
 */
object ShaderTranscoder {
    private const val TAG = "ShaderTranscoder"

    /** Shader stage: vertex shader. */
    const val STAGE_VERTEX = 0
    /** Shader stage: fragment shader. */
    const val STAGE_FRAGMENT = 1

    private val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary("wepwawet_etc2")
            WepWawetLogger.proxy("$TAG: native library loaded ✓")
            true
        } catch (e: UnsatisfiedLinkError) {
            WepWawetLogger.proxyWarn("$TAG: native library not loaded — shader transcoding unavailable",
                "exception" to e.javaClass.name)
            false
        }
    }

    /**
     * Check if the shader transcoder is available.
     * Same native library as Etc2Encoder + DxtDecoder.
     */
    fun isAvailable(): Boolean = isLoaded

    /**
     * Transcode an HLSL or GLSL shader to GLSL ES 3.00.
     *
     * Flow: source → glslang (compile to SPIR-V) → SPIRV-Cross (cross-compile to GLSL ES)
     *
     * @param shaderSource The shader source code (HLSL or GLSL)
     * @param shaderStage 0=vertex, 1=fragment
     * @param isHlsl true if source is HLSL, false if GLSL
     * @return GLSL ES 3.00 source, or null on failure
     */
    fun transcodeShader(shaderSource: String, shaderStage: Int, isHlsl: Boolean): String? {
        if (!isLoaded) return null
        val srcBytes = shaderSource.toByteArray(Charsets.UTF_8)
        return try {
            val result = nativeTranscodeShader(srcBytes, shaderStage, isHlsl)
            if (result != null) {
                WepWawetLogger.proxy("$TAG.transcodeShader SUCCESS",
                    "stage" to if (shaderStage == STAGE_VERTEX) "vertex" else "fragment",
                    "is_hlsl" to isHlsl,
                    "input_size" to srcBytes.size,
                    "output_size" to result.size)
                String(result, Charsets.UTF_8)
            } else {
                WepWawetLogger.proxyWarn("$TAG.transcodeShader FAILED — native call returned null",
                    "stage" to if (shaderStage == STAGE_VERTEX) "vertex" else "fragment",
                    "is_hlsl" to isHlsl)
                null
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG.transcodeShader EXCEPTION",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    @JvmStatic
    private external fun nativeTranscodeShader(
        srcBytes: ByteArray,
        shaderStage: Int,
        isHlsl: Boolean,
    ): ByteArray?
}
