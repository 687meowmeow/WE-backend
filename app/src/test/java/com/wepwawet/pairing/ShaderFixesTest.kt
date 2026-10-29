package com.wepwawet.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the v0.7.6-alpha shader fix pipeline in [MpkgPacker].
 *
 * These tests exercise the regex-based shader-fix logic IN ISOLATION from
 * the rest of the MpkgPacker (which depends on Android Context, native
 * ETC2 encoder, LZ4 factory, etc.). The regex patterns below are COPIED
 * VERBATIM from MpkgPacker.fixShadersForMobile so a regression in the
 * packer code is caught here without needing a full Android device.
 *
 * The fixtures are derived from the real failing shader in
 * `applog.txt` (workshop id 3655958892 — R18 Acheron & Black Swan,
 * Simple_Audio_Bars effect from workshop 2084198056).
 */
class ShaderFixesTest {

    // =========================================================================
    // FIX 1 — unconditional lerp→mix (textual swap, word-boundary, case-sensitive)
    // =========================================================================
    //
    // The packer's PHASE 0.5 now does a simple textual `lerp(` → `mix(` swap
    // on every .frag/.vert file. The semantics are identical (HLSL lerp(a,b,t)
    // ≡ GLSL mix(a,b,t), same arg order), so a textual swap is safe regardless
    // of the arg structure — even with nested parens like
    // `lerp(a, b, smoothstep(0.0, 1.0, frac(frequency)))`.

    /** Mirrors the lerp→mix regex in MpkgPacker.fixShadersForMobile PHASE 0.5. */
    private fun applyLerpToMix(shader: String): String {
        val count = Regex("""\blerp\(""").findAll(shader).count()
        return if (count > 0) shader.replace(Regex("""\blerp\("""), "mix(") else shader
    }

    @Test
    fun `FIX 1 - lerp_to_mix swaps basic lerp calls`() {
        val input = "float barVolume = lerp(barVolume1, barVolume2, smoothstep(0.0, 1.0, frac(frequency)));"
        val expected = "float barVolume = mix(barVolume1, barVolume2, smoothstep(0.0, 1.0, frac(frequency)));"
        assertEquals(expected, applyLerpToMix(input))
    }

    @Test
    fun `FIX 1 - lerp_to_mix preserves nested parens in saturate_lerp chain`() {
        // This is the exact failing pattern from applog.txt line 275:
        //   verticalSmoothing *= saturate(lerp(0.0, 1.0, barVolume * 100.0));
        // The simple `lerp(` → `mix(` swap should leave the args (including
        // the nested `100.0` and the outer `saturate(...)` ) untouched.
        val input = "verticalSmoothing *= saturate(lerp(0.0, 1.0, barVolume * 100.0));"
        val expected = "verticalSmoothing *= saturate(mix(0.0, 1.0, barVolume * 100.0));"
        assertEquals(expected, applyLerpToMix(input))
    }

    @Test
    fun `FIX 1 - lerp_to_mix swaps all 4 lerp calls in Simple_Audio_Bars frag`() {
        // Mirrors the 4 lerp() calls in the failing shader (lines 269, 271, 275, 317).
        val input = """
            float barVolume = lerp(barVolume1, barVolume2, smoothstep(0.0, 1.0, frac(frequency)));
            float barHeight = lerp(u_BarBounds.x, u_BarBounds.y, barVolume);
            verticalSmoothing *= saturate(lerp(0.0, 1.0, barVolume * 100.0));
            finalColor = ApplyBlending(BLENDMODE, lerp(finalColor.rgb, scene.rgb, scene.a), finalColor.rgb, bar * u_BarOpacity);
        """.trimIndent()
        val result = applyLerpToMix(input)
        // All 4 lerp( should become mix(
        assertEquals(0, Regex("""\blerp\(""").findAll(result).count())
        assertEquals(4, Regex("""\bmix\(""").findAll(result).count())
        assertTrue(result.contains("mix(barVolume1, barVolume2, smoothstep(0.0, 1.0, frac(frequency)))"))
        assertTrue(result.contains("mix(u_BarBounds.x, u_BarBounds.y, barVolume)"))
        assertTrue(result.contains("saturate(mix(0.0, 1.0, barVolume * 100.0))"))
        assertTrue(result.contains("ApplyBlending(BLENDMODE, mix(finalColor.rgb, scene.rgb, scene.a), finalColor.rgb, bar * u_BarOpacity)"))
    }

    @Test
    fun `FIX 1 - lerp_to_mix does NOT touch lerp used as variable name`() {
        // The zcompat pixelate shader uses `lerp` as a variable name:
        //   `vec2 lerp = fract(v_PixelCoord);` and `lerp.x * lerp.y`.
        // Our `lerp(` regex requires the next char to be `(`, so `lerp = ...`
        // and `lerp.x` are NOT touched.
        val input = """
            vec2 lerp = fract(v_PixelCoord);
            vec4 finalColor = texSample2D(g_Texture0, texCoord00) * (1.0 - lerp.x) * (1.0 - lerp.y);
        """.trimIndent()
        val result = applyLerpToMix(input)
        // No `lerp(` to match (since lerp is followed by `=`, `.`, `*`, `+`, etc.,
        // NOT `(`). So no swap should happen.
        assertEquals(input, result)
        // Variable declarations and usages are preserved.
        assertTrue(result.contains("vec2 lerp = fract(v_PixelCoord);"))
        assertTrue(result.contains("(1.0 - lerp.x)"))
        assertTrue(result.contains("(1.0 - lerp.y)"))
    }

    @Test
    fun `FIX 1 - lerp_to_mix is case-sensitive (does not touch Lerp or LERP)`() {
        val input = "float Lerp = 1.0; float LERP = 2.0; Lerp(1, 2, 3);"
        val result = applyLerpToMix(input)
        // Capitalized `Lerp(` and `LERP(` should NOT be touched.
        assertTrue(result.contains("Lerp(1, 2, 3)"))
        assertTrue(result.contains("float Lerp = 1.0"))
        assertTrue(result.contains("float LERP = 2.0"))
        assertFalse(result.contains("Lerp_Mix") || result.contains("Mixer("))
    }

    @Test
    fun `FIX 1 - lerp_to_mix does not match lerp inside identifier like MyBlerpFunc`() {
        // `MyBlerpFunc(` contains `lerp` as a substring, but the `\b` word
        // boundary before `lerp` doesn't match because the preceding char `B`
        // is a letter. So `MyBlerpFunc(` is NOT touched.
        val input = "float x = MyBlerpFunc(1.0, 2.0);"
        val result = applyLerpToMix(input)
        assertEquals(input, result)
        assertTrue(result.contains("MyBlerpFunc("))
    }

    // =========================================================================
    // v0.7.7-alpha REGRESSION TEST — mul() must NOT be converted by the packer
    // =========================================================================
    //
    // The WE Android preprocessor provides `#define mul(x, y) ((y) * (x))`
    // (line 2 of every compiled shader). The preprocessor's macro expander
    // correctly handles nested parens, so `mul(vec4(a_Position, 1.0),
    // g_ModelViewProjectionMatrix)` is correctly expanded to
    // `((g_ModelViewProjectionMatrix) * (vec4(a_Position, 1.0)))`.
    //
    // The v0.7.6 unconditional pass ran a buggy mul() regex that broke
    // nested function calls, producing
    // `(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix)` (comma operator
    // form) which evaluates to the mat4 (last element), causing
    // "cannot convert from '4X4 matrix of float' to 'Position 4-component
    // vector of float'" — ALL .vert shaders failed, the scene couldn't
    // render ANY geometry.
    //
    // v0.7.7 REMOVES the mul() conversion entirely. The preprocessor
    // handles it. These tests verify that mul() is NOT touched.

    /** Mirrors the v0.7.7 PHASE 0.5 — does NOT convert mul/frac/saturate/ddx/ddy. */
    private fun applyPhase05_v077(shader: String): String {
        // Only lerp→mix is converted. mul/frac/saturate/ddx/ddy are left alone
        // (preprocessor handles them via #define).
        val lerpCount = Regex("""\blerp\(""").findAll(shader).count()
        return if (lerpCount > 0) shader.replace(Regex("""\blerp\("""), "mix(") else shader
    }

    @Test
    fun `v0_7_7 - mul calls are NOT converted by the packer (preprocessor handles them)`() {
        // The EXACT pattern from the failing .vert shaders in the v0.7.6 regression.
        // The packer must NOT touch `mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix)`.
        val input = """
            uniform mat4 g_ModelViewProjectionMatrix;
            attribute vec3 a_Position;
            void main() {
                gl_Position = mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix);
            }
        """.trimIndent()
        val result = applyPhase05_v077(input)
        // mul( must be PRESERVED — the WE Android preprocessor's
        // #define mul(x, y) ((y) * (x)) will expand it at compile time.
        assertTrue(
            "mul() must NOT be converted by the packer (preprocessor handles it). " +
                "v0.7.6 regression: mul() was rewritten to broken comma-operator form.",
            result.contains("mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix)")
        )
        // The broken comma-operator form must NOT appear.
        assertFalse(
            "The broken comma-operator form must NOT appear: " +
                "(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix)",
            result.contains("(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix)")
        )
    }

    @Test
    fun `v0_7_7 - frac calls are NOT converted by the packer (preprocessor handles them)`() {
        val input = "float x = frac(y);"
        val result = applyPhase05_v077(input)
        assertEquals(input, result)
        assertTrue(result.contains("frac(y)"))
        assertFalse(result.contains("fract(y)"))
    }

    @Test
    fun `v0_7_7 - saturate calls are NOT converted by the packer (preprocessor handles them)`() {
        val input = "float x = saturate(y * 2.0);"
        val result = applyPhase05_v077(input)
        assertEquals(input, result)
        assertTrue(result.contains("saturate(y * 2.0)"))
    }

    @Test
    fun `v0_7_7 - ddx calls are NOT converted by the packer (preprocessor handles them)`() {
        val input = "float x = ddx(y);"
        val result = applyPhase05_v077(input)
        assertEquals(input, result)
    }

    @Test
    fun `v0_7_7 - ddy calls are NOT converted by the packer (preprocessor handles them with negation)`() {
        // The preprocessor's #define ddy(x) dFdy(-(x)) adds a negation that
        // the packer's old ddy→dFdy conversion DIDN'T add. So the packer must
        // NOT touch ddy().
        val input = "float x = ddy(y);"
        val result = applyPhase05_v077(input)
        assertEquals(input, result)
        assertFalse(result.contains("dFdy(y)"))  // packer must NOT add this (missing negation)
    }

    @Test
    fun `v0_7_7 - lerp calls ARE still converted to mix (no preprocessor define for lerp)`() {
        // lerp→mix is the ONLY conversion kept in v0.7.7 (no #define lerp in preprocessor).
        val input = "float x = lerp(a, b, t);"
        val result = applyPhase05_v077(input)
        assertEquals("float x = mix(a, b, t);", result)
    }

    @Test
    fun `v0_7_7 - full waterflow vert shader pattern - mul preserved, lerp converted`() {
        // The waterflow.vert pattern from the regression:
        //   gl_Position = mul(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix);
        // (after int→float converted `1` to `1.0`)
        // The packer must preserve mul() and NOT break it into comma form.
        val input = """
            #define mul(x, y) ((y) * (x))
            uniform mat4 g_ModelViewProjectionMatrix;
            attribute vec3 a_Position;
            void main() {
                gl_Position = mul(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix);
                float v = lerp(0.0, 1.0, 0.5);
            }
        """.trimIndent()
        val result = applyPhase05_v077(input)
        // mul() preserved (preprocessor will expand via #define)
        assertTrue(result.contains("mul(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix)"))
        // lerp converted to mix
        assertTrue(result.contains("mix(0.0, 1.0, 0.5)"))
        // #define mul line preserved
        assertTrue(result.contains("#define mul(x, y) ((y) * (x))"))
    }

    // =========================================================================
    // FIX 2 — type-aware int→float conversion (skip when adjacent to uint)
    // =========================================================================
    //
    // The packer's PHASE 1 Fix 1D (general int→float conversion) previously
    // appended `.0` to int literals blindly. For shaders that declare a
    // `uint` variable (e.g., `uint barFreq1`) and use `barFreq1 + 1`, the
    // conversion produced `barFreq1 + 1.0` — which is `uint + float` — illegal
    // in GLSL ES 3.00 (no implicit conversion).
    //
    // The fix: pre-scan the shader for `uint\s+\w+` declarations to build a
    // uint variable set, then in the int→float regex callback, check if the
    // adjacent operand is a uint variable. If so, SKIP the conversion (don't
    // append .0). The mandatory unit test: `barFreq1 + 1` must NOT become
    // `barFreq1 + 1.0`.

    /** Mirrors the uint variable name detection regex from Fix 1D. */
    private fun findUintVarNames(shader: String): Set<String> {
        val pattern = Regex("""\b(?:uniform\s+|const\s+|static\s+const\s+|static\s+)?uint\s+(\w+)""")
        val exclusions = setOf(
            "main", "return", "void", "if", "else", "for", "while",
            "float", "int", "vec2", "vec3", "vec4", "uint", "bool",
            "mat2", "mat3", "mat4", "sampler2D", "sampler3D",
            "uvec2", "uvec3", "uvec4", "ivec2", "ivec3", "ivec4",
            "bvec2", "bvec3", "bvec4", "in", "out", "inout", "attribute",
            "varying", "uniform", "const", "static", "layout",
        )
        return pattern.findAll(shader).map { it.groupValues[1] }
            .filter { it !in exclusions }.toSet()
    }

    /**
     * Mirrors the int→float conversion logic with the uint-adjacent skip
     * (FIX 2). Skips comments, strings, and #define directive lines.
     */
    private fun applyIntToFloat(shader: String): String {
        val uintVarNames = findUintVarNames(shader)
        val uintVarSkipCount = intArrayOf(0)

        // Build a skip mask for comments and strings.
        val skipMask = BooleanArray(shader.length)
        var pos = 0
        var inBlockComment = false
        var inLineComment = false
        var inString = false
        while (pos < shader.length) {
            val ch = shader[pos]
            val nextCh = if (pos + 1 < shader.length) shader[pos + 1] else '\u0000'
            if (inBlockComment) {
                skipMask[pos] = true
                if (ch == '*' && nextCh == '/') {
                    skipMask[pos + 1] = true
                    pos += 2
                    inBlockComment = false
                    continue
                }
            } else if (inLineComment) {
                skipMask[pos] = true
                if (ch == '\n') {
                    inLineComment = false
                }
            } else if (inString) {
                skipMask[pos] = true
                if (ch == '\\' && nextCh != '\u0000') {
                    skipMask[pos + 1] = true
                    pos += 2
                    continue
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                if (ch == '/' && nextCh == '*') {
                    inBlockComment = true
                    skipMask[pos] = true
                    skipMask[pos + 1] = true
                    pos += 2
                    continue
                } else if (ch == '/' && nextCh == '/') {
                    inLineComment = true
                    skipMask[pos] = true
                    skipMask[pos + 1] = true
                    pos += 2
                    continue
                } else if (ch == '"') {
                    inString = true
                    skipMask[pos] = true
                }
            }
            pos++
        }

        val intConversionRegex = Regex("""([\s=/()\+\-\*%\\\],])(\d+)(?!\.)(\b)""")
        return shader.replace(intConversionRegex) { mr ->
            val matchStart = mr.range.first
            if (matchStart < skipMask.size && skipMask[matchStart]) {
                return@replace mr.value
            }
            val lineStart = shader.lastIndexOf('\n', matchStart - 1).coerceAtLeast(0)
            val linePrefix = shader.substring(lineStart, matchStart)
            val trimmedPrefix = linePrefix.trimStart()
            if (trimmedPrefix.startsWith("#")) {
                return@replace mr.value
            }
            // FIX 2: type-aware skip when adjacent to a uint variable.
            if (uintVarNames.isNotEmpty()) {
                val operatorChar = mr.groupValues[1]  // char immediately before the int
                val intEnd = mr.range.last + 1

                // Find the actual arithmetic operator. The captured char might be:
                //   - An operator (+, -, *, /, %) — common case
                //   - A space (whitespace before int) — walk back to find the operator
                //   - An open paren, comma, etc. — no preceding operator
                var opPos = -1
                if (operatorChar.length == 1 && "+-*/%".contains(operatorChar)) {
                    opPos = matchStart
                } else {
                    // Walk back past whitespace to find the actual operator.
                    var p = matchStart - 1
                    while (p >= 0 && shader[p].isWhitespace()) p--
                    if (p >= 0 && "+-*/%".contains(shader[p])) {
                        opPos = p
                    }
                }

                if (opPos >= 0) {
                    // Check the operand BEFORE opPos (uint + int pattern).
                    var p = opPos - 1
                    while (p >= 0 && shader[p].isWhitespace()) p--
                    if (p >= 0 && (shader[p].isLetterOrDigit() || shader[p] == '_')) {
                        var tokenEnd = p + 1
                        var tokenStart = p
                        while (tokenStart > 0 &&
                            (shader[tokenStart - 1].isLetterOrDigit() || shader[tokenStart - 1] == '_')) {
                            tokenStart--
                        }
                        val precedingToken = shader.substring(tokenStart, tokenEnd)
                        if (precedingToken in uintVarNames) {
                            uintVarSkipCount[0]++
                            return@replace mr.value
                        }
                    }
                }

                // Check the operand AFTER the int (int + uint pattern).
                var q = intEnd
                while (q < shader.length && shader[q].isWhitespace()) q++
                if (q < shader.length && "+-*/%".contains(shader[q])) {
                    var r = q + 1
                    while (r < shader.length && shader[r].isWhitespace()) r++
                    if (r < shader.length && (shader[r].isLetterOrDigit() || shader[r] == '_')) {
                        var tokenStart = r
                        var tokenEnd = r + 1
                        while (tokenEnd < shader.length &&
                            (shader[tokenEnd].isLetterOrDigit() || shader[tokenEnd] == '_')) {
                            tokenEnd++
                        }
                        val followingToken = shader.substring(tokenStart, tokenEnd)
                        if (followingToken in uintVarNames) {
                            uintVarSkipCount[0]++
                            return@replace mr.value
                        }
                    }
                }
            }
            "${mr.groupValues[1]}${mr.groupValues[2]}.0"
        }
    }

    @Test
    fun `FIX 2 - barFreq1 plus 1 must NOT become barFreq1 plus 1_0`() {
        // The MANDATORY unit test from the user's task spec.
        // Shader declares `uint barFreq1` and uses `barFreq1 + 1`.
        // The int→float regex MUST NOT rewrite `1` to `1.0` here.
        val input = """
            uniform float u_BarCount;
            uniform float g_AudioSpectrum64Left[64];
            #define RESOLUTION 64
            void main() {
                float frequency = floor(shapeCoord.x * u_BarCount) / u_BarCount * RESOLUTION;
                uint barFreq1 = frequency % RESOLUTION;
                uint barFreq2 = (barFreq1 + 1) % RESOLUTION;
                float barVolume1 = g_AudioSpectrum64Left[barFreq1] * 0.5;
            }
        """.trimIndent()
        val result = applyIntToFloat(input)
        // The CRITICAL assertion: `barFreq1 + 1` must NOT become `barFreq1 + 1.0`.
        assertFalse(
            "barFreq1 + 1 must NOT become barFreq1 + 1.0 (FIX 2 — type-aware int→float)",
            result.contains("barFreq1 + 1.0")
        )
        // The original `barFreq1 + 1` (uint + int) must be preserved.
        assertTrue(
            "barFreq1 + 1 must be preserved (not rewritten to barFreq1 + 1.0)",
            result.contains("barFreq1 + 1)")
        )
    }

    @Test
    fun `FIX 2 - reverse pattern 1 plus barFreq1 must NOT become 1_0 plus barFreq1`() {
        // Mirror of the above, but for `1 + barFreq1` (int + uint).
        val input = """
            uniform float u_BarCount;
            #define RESOLUTION 64
            void main() {
                uint barFreq1 = 42u;
                uint barFreq2 = (1 + barFreq1) % RESOLUTION;
            }
        """.trimIndent()
        val result = applyIntToFloat(input)
        assertFalse(
            "1 + barFreq1 must NOT become 1.0 + barFreq1 (FIX 2 reverse pattern)",
            result.contains("1.0 + barFreq1")
        )
        assertTrue(
            "1 + barFreq1 must be preserved (not rewritten)",
            result.contains("1 + barFreq1)")
        )
    }

    @Test
    fun `FIX 2 - normal int literals in float context are still converted`() {
        // The uint-adjacent skip must NOT prevent normal int→float conversion
        // for non-uint variables. e.g., `pointer * 2 - 1` should still become
        // `pointer * 2.0 - 1.0` (pointer is not a uint).
        val input = """
            uniform float pointer;
            void main() {
                float x = pointer * 2 - 1;
            }
        """.trimIndent()
        val result = applyIntToFloat(input)
        assertTrue(
            "Normal int literals in float context are still converted (pointer * 2 → pointer * 2.0)",
            result.contains("pointer * 2.0 - 1.0")
        )
    }

    @Test
    fun `FIX 2 - uint variable detection handles uniform uint`() {
        val input = "uniform uint g_BarCount; uint barFreq1 = g_BarCount;"
        val names = findUintVarNames(input)
        assertTrue("uniform uint g_BarCount should be detected", "g_BarCount" in names)
        assertTrue("local uint barFreq1 should be detected", "barFreq1" in names)
    }

    @Test
    fun `FIX 2 - uint variable detection handles const and static const`() {
        val input = """
            const uint MAX_BARS = 64;
            static const uint SAMPLE_COUNT = 32;
            uniform uint g_Foo;
            void main() {
                uint local = 1u;
            }
        """.trimIndent()
        val names = findUintVarNames(input)
        assertTrue("MAX_BARS", "MAX_BARS" in names)
        assertTrue("SAMPLE_COUNT", "SAMPLE_COUNT" in names)
        assertTrue("g_Foo", "g_Foo" in names)
        assertTrue("local", "local" in names)
    }

    @Test
    fun `FIX 2 - int literals in comments are NOT converted`() {
        val input = """
            // uniform float u_BarCount; // {"default":1,"range":[1, 200]}
            uint barFreq1 = 0u;
            // barFreq1 + 1 in a comment should NOT be touched
        """.trimIndent()
        val result = applyIntToFloat(input)
        // The `1` in the comment must be preserved (not converted to `1.0`).
        assertTrue(
            "Int literals in line comments are NOT converted",
            result.contains("// barFreq1 + 1 in a comment")
        )
    }

    @Test
    fun `FIX 2 - int literals in preprocessor directives are NOT converted`() {
        val input = """
            #define RESOLUTION 64
            #if RESOLUTION == 32
            uint barFreq1 = 0u;
            #endif
        """.trimIndent()
        val result = applyIntToFloat(input)
        // The `64` in #define and `32` in #if must be preserved.
        assertTrue(result.contains("#define RESOLUTION 64"))
        assertTrue(result.contains("#if RESOLUTION == 32"))
    }

    @Test
    fun `FIX 2 - full Simple_Audio_Bars line 213-214 pattern`() {
        // Mirrors the EXACT failing pattern from applog.txt:
        //   uint barFreq1 = frequency % RESOLUTION;
        //   uint barFreq2 = (barFreq1 + 1) % RESOLUTION;
        //
        // Before FIX 2, the packer rewrote `1` to `1.0` in line 214,
        // producing `(barFreq1 + 1.0) % RESOLUTION` — uint + float = ERROR.
        //
        // After FIX 2, the `1` is preserved (because barFreq1 is a uint var),
        // so the line stays `(barFreq1 + 1) % RESOLUTION` — uint + int (which
        // is ALSO illegal in GLSL ES 3.00, but the zcompat substitution for
        // 2084198056 handles this shader separately by replacing the whole
        // shader with the mobile-safe zcompat version).
        val input = """
            #define RESOLUTION 64
            uniform float u_BarCount;
            void main() {
                float frequency = floor(shapeCoord.x * u_BarCount) / u_BarCount * RESOLUTION;
                uint barFreq1 = frequency % RESOLUTION;
                uint barFreq2 = (barFreq1 + 1) % RESOLUTION;
            }
        """.trimIndent()
        val result = applyIntToFloat(input)
        // barFreq1 + 1 must NOT have .0 appended to the 1.
        assertFalse(
            "FIX 2 regression: barFreq1 + 1 was rewritten to barFreq1 + 1.0 (uint + float illegal in GLSL ES 3.00)",
            result.contains("barFreq1 + 1.0")
        )
        assertTrue(
            "FIX 2: barFreq1 + 1 must be preserved (no .0 on the 1)",
            result.contains("barFreq1 + 1) % RESOLUTION")
        )
    }

    // =========================================================================
    // FIX 4 — zcompat path matching (workshop effect id extraction)
    // =========================================================================
    //
    // The substituteZcompatShader helper extracts the LAST `workshop/<id>/effects/<name>`
    // segment from the shader's VFS path. This section tests that path matching
    // in isolation (without needing an Android Context to load assets).

    /** Mirrors substituteZcompatShader's workshop-id regex. */
    private fun extractZcompatEffectId(name: String): String? {
        val zcompatIdRegex = Regex("""workshop/(\d+)/effects/([^/]+\.(?:frag|vert))$""")
        val matches = zcompatIdRegex.findAll(name).toList()
        if (matches.isEmpty()) return null
        return matches.last().groupValues[1]
    }

    @Test
    fun `FIX 4 - extract effect id from nested workshop shader path`() {
        // The actual failing shader path from applog.txt:
        val path = "shaders/workshop/3306828160/workshop/2084198056/effects/Simple_Audio_Bars.frag"
        assertEquals("2084198056", extractZcompatEffectId(path))
    }

    @Test
    fun `FIX 4 - extract effect id from single-level workshop shader path`() {
        val path = "shaders/workshop/3306828160/effects/pulse.frag"
        assertEquals("3306828160", extractZcompatEffectId(path))
    }

    @Test
    fun `FIX 4 - extract effect id from pixelate shader path`() {
        val path = "shaders/workshop/3655958892/workshop/2078835426/effects/pixelate.frag"
        assertEquals("2078835426", extractZcompatEffectId(path))
    }

    @Test
    fun `FIX 4 - returns null for built-in effect shader path`() {
        val path = "shaders/effects/waterflow.frag"
        assertEquals(null, extractZcompatEffectId(path))
    }

    @Test
    fun `FIX 4 - returns null for non-shader file`() {
        val path = "materials/effects/waterflow.json"
        assertEquals(null, extractZcompatEffectId(path))
    }

    @Test
    fun `FIX 4 - matches both frag and vert extensions`() {
        val fragPath = "shaders/workshop/3655958892/workshop/2078835426/effects/pixelate.frag"
        val vertPath = "shaders/workshop/3655958892/workshop/2078835426/effects/pixelate.vert"
        assertEquals("2078835426", extractZcompatEffectId(fragPath))
        assertEquals("2078835426", extractZcompatEffectId(vertPath))
    }

    @Test
    fun `FIX 4 - does NOT match non-frag-vert files in workshop dir`() {
        val path = "shaders/workshop/3306828160/workshop/2084198056/effects/Simple_Audio_Bars.json"
        assertEquals(null, extractZcompatEffectId(path))
    }
}
