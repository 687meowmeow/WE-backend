package com.wepwawet.pairing

/**
 * GLSL ES 3.00 safety pass — replicates, at source level, the semantics the
 * REAL Wallpaper Engine PC pipeline gets for free from the D3D shader
 * compiler (fxc), but which the official Android engine's strict GLSL ES
 * compiler rejects.
 *
 * Why this exists (verified against the shipping binaries):
 *  - wallpaperui.exe's mobile transcoder (fixShader @0x140015f20 +
 *    deprModFix @0x140017dd0 + zcompat substitution) does NOT fix the
 *    HLSL-tolerant constructs below — the PC ships them verbatim in the
 *    .mpkg and relies on fxc to accept them.
 *  - The official Android engine (libscenejni.so) only prepends a macro
 *    header (#define texSample2D texture, ...) and compiles with strict
 *    GLSL ES 3.00 (proven by the engine's own "Failed compiling shader"
 *    log for workshop 2798696916's sharpen_filter.frag: implicit vec4→float
 *    truncation and an int-ternary both rejected).
 *  - Therefore the backend must make the PC's tolerant semantics explicit
 *    so the same shader compiles identically on both compilers.
 *
 * Rewrites performed (all semantics-preserving, fxc-faithful):
 *  1. Ternary with a non-bool scalar condition   c ? a : b  →  bool(c) ? a : b
 *     (fxc converts int/float conditions via != 0)
 *  2. if / while / for / do-while with a non-bool scalar condition → bool(c)
 *  3. Logical && / || / ! on non-bool scalars → bool(x)
 *  4. Implicit truncation on initialization / assignment / return:
 *       float x = <vecN>;  →  float x = (<expr>).x;      (N > 1)
 *       vec2  x = <vecN>;  →  vec2  x = (<expr>).xy;     (N > 2)
 *     (fxc X3206 "implicit truncation of vector type" takes the leading
 *      components)
 *  5. Scalar constructor from a vector argument:
 *       float(v4) / CASTF(v4)  →  float((v4).x) / CASTF((v4).x)
 *     (fxc scalar cast = .x; GLSL ES forbids scalar-from-vector ctors)
 *  6. #define'd macro bodies get the same treatment (they are expanded by
 *     the engine's preprocessor, so they must be ESSL-safe too).
 *
 * Design rule: CONSERVATIVE. Types come from declarations in the file, from
 * [COMBO] metadata (the engine injects `#define NAME <int>` for every combo
 * — verified from the engine's runtime shader assembly), from a built-in
 * function table, and from simple #define bodies. Anything not confidently
 * typed is left untouched. If anything about the token structure looks
 * inconsistent, the pass makes NO edits at all.
 *
 * The pass is pure Kotlin (no Android imports) and unit-testable on the JVM.
 */
object ShaderEsslSafety {

    // ═══════════════════════════════ lexer ═══════════════════════════════

    private enum class TKind { WS, COMMENT, IDENT, NUM, PUNCT, PREPROC }

    private class Tok(val kind: TKind, val s: String, val start: Int, val end: Int) {
        fun punct(p: String) = kind == TKind.PUNCT && s == p
        fun ident(id: String) = kind == TKind.IDENT && s == id
    }

    private val PUNCT3 = listOf("<<=", ">>=", "...")
    private val PUNCT2 = listOf("==", "!=", "<=", ">=", "&&", "||", "++", "--",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "->")
    private val PUNCT1 = "{}()[];,.?:=+-*/%<>!~&|^#".map { it.toString() }

    private fun lex(src: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val start = i
            when {
                c == '\n' -> { i++; out.add(Tok(TKind.WS, "\n", start, i)) }
                c == ' ' || c == '\t' || c == '\r' -> {
                    while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r')) i++
                    out.add(Tok(TKind.WS, src.substring(start, i), start, i))
                }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                    out.add(Tok(TKind.COMMENT, src.substring(start, i), start, i))
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    if (i + 1 >= n) return null            // unterminated comment
                    i += 2
                    out.add(Tok(TKind.COMMENT, src.substring(start, i), start, i))
                }
                c == '#' && atLineStart(src, i) -> {
                    while (i < n && src[i] != '\n') i++
                    out.add(Tok(TKind.PREPROC, src.substring(start, i), start, i))
                }
                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i >= n || src[i] != '"') return null
                    i++
                    out.add(Tok(TKind.PREPROC, src.substring(start, i), start, i))
                }
                c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
                    if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                        i += 2
                        while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F')) i++
                    } else {
                        var seenDot = false
                        var seenE = false
                        while (i < n) {
                            val d = src[i]
                            if (d.isDigit()) i++
                            else if (d == '.' && !seenDot && !seenE) { seenDot = true; i++ }
                            else if ((d == 'e' || d == 'E') && !seenE && i + 1 < n &&
                                (src[i + 1].isDigit() || src[i + 1] == '+' || src[i + 1] == '-')) {
                                seenE = true; i++
                                if (src[i] == '+' || src[i] == '-') i++
                            } else if (d == 'f' || d == 'F' || d == 'u' || d == 'U' ||
                                d == 'l' || d == 'L') i++
                            else break
                        }
                    }
                    out.add(Tok(TKind.NUM, src.substring(start, i), start, i))
                }
                c == '_' || c.isLetter() -> {
                    while (i < n && (src[i] == '_' || src[i].isLetterOrDigit())) i++
                    out.add(Tok(TKind.IDENT, src.substring(start, i), start, i))
                }
                else -> {
                    var matched = false
                    for (p in PUNCT3) if (src.startsWith(p, i)) {
                        i += 3; out.add(Tok(TKind.PUNCT, p, start, i)); matched = true; break
                    }
                    if (!matched) for (p in PUNCT2) if (src.startsWith(p, i)) {
                        i += 2; out.add(Tok(TKind.PUNCT, p, start, i)); matched = true; break
                    }
                    if (!matched) {
                        if (c.toString() !in PUNCT1) return null   // stray char → bail
                        i++
                        out.add(Tok(TKind.PUNCT, c.toString(), start, i))
                    }
                }
            }
        }
        return out
    }

    private fun atLineStart(src: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (src[j] == ' ' || src[j] == '\t')) j--
        return j < 0 || src[j] == '\n'
    }

    // ═══════════════════════════════ types ═══════════════════════════════

    private enum class B { F, I, U, BO, S, M, VO, UNK }

    private class Ty(val b: B, val n: Int = 1, val arr: Boolean = false) {
        val isNumericScalar get() = n == 1 && (b == B.F || b == B.I || b == B.U)
        companion object {
            val F1 = Ty(B.F); val F2 = Ty(B.F, 2); val F3 = Ty(B.F, 3); val F4 = Ty(B.F, 4)
            val I1 = Ty(B.I)
            val BO1 = Ty(B.BO)
            val UNK = Ty(B.UNK)
            val VOID = Ty(B.VO)
        }
    }

    private fun typeKeyword(s: String): Ty? = when (s) {
        "float" -> Ty.F1; "vec2" -> Ty.F2; "vec3" -> Ty.F3; "vec4" -> Ty.F4
        "int" -> Ty.I1; "ivec2" -> Ty(B.I, 2); "ivec3" -> Ty(B.I, 3); "ivec4" -> Ty(B.I, 4)
        "uint" -> Ty(B.U); "uvec2" -> Ty(B.U, 2); "uvec3" -> Ty(B.U, 3); "uvec4" -> Ty(B.U, 4)
        "bool" -> Ty.BO1; "bvec2" -> Ty(B.BO, 2); "bvec3" -> Ty(B.BO, 3); "bvec4" -> Ty(B.BO, 4)
        "mat2" -> Ty(B.M, 2); "mat3" -> Ty(B.M, 3); "mat4" -> Ty(B.M, 4)
        "void" -> Ty.VOID
        "sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "samplerCubeShadow",
        "sampler2DArray", "isampler2D", "usampler2D", "samplerExternalOES" -> Ty(B.S)
        else -> null
    }

    private fun scalarCtor(s: String): B? = when (s) {
        "float", "CASTF" -> B.F
        "int", "CASTI" -> B.I
        "uint", "CASTU" -> B.U
        "bool", "CASTB" -> B.BO
        else -> null
    }

    private fun vectorCtor(s: String): Ty? = when (s) {
        "vec2", "CAST2" -> Ty.F2
        "vec3", "CAST3" -> Ty.F3
        "vec4", "CAST4" -> Ty.F4
        "ivec2" -> Ty(B.I, 2); "ivec3" -> Ty(B.I, 3); "ivec4" -> Ty(B.I, 4)
        "uvec2" -> Ty(B.U, 2); "uvec3" -> Ty(B.U, 3); "uvec4" -> Ty(B.U, 4)
        "bvec2" -> Ty(B.BO, 2); "bvec3" -> Ty(B.BO, 3); "bvec4" -> Ty(B.BO, 4)
        else -> null
    }

    private class FnSig(val ret: Ty)

    // ═══════════════ built-in function return types ══════════════════════

    private val BUILTIN_RET: Map<String, Ty> = mapOf(
        // WE sampling macros (the engine header maps them to texture/textureLod)
        "texSample2D" to Ty.F4, "texSample2DLod" to Ty.F4,
        "texSample3D" to Ty.F4, "texSample2DBackBuffer" to Ty.F4,
        "texSample2DCompare" to Ty.F1, "texSample2DCompareLevelZero" to Ty.F1,
        // GLSL builtins with fixed returns
        "texture" to Ty.F4, "texture2D" to Ty.F4, "texture3D" to Ty.F4,
        "textureLod" to Ty.F4, "textureProj" to Ty.F4, "texelFetch" to Ty.F4,
        "length" to Ty.F1, "distance" to Ty.F1, "dot" to Ty.F1,
        "cross" to Ty.F3,
        "any" to Ty.BO1, "all" to Ty.BO1,
        // engine CAST macros with fixed returns
        "CASTF" to Ty.F1, "CASTI" to Ty.I1, "CASTU" to Ty(B.U), "CASTB" to Ty.BO1,
        "CAST2" to Ty.F2, "CAST3" to Ty.F3, "CAST4" to Ty.F4
    )

    /** Builtins whose return type follows their first argument. */
    private val FIRST_ARG_RET = setOf(
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
        "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "exp", "log", "exp2", "log2",
        "sqrt", "inversesqrt", "abs", "sign", "floor", "ceil", "fract", "trunc", "round",
        "roundEven", "mod", "min", "max", "clamp", "mix", "step", "smoothstep", "fma",
        "normalize", "faceforward", "reflect", "refract", "pow", "modf",
        "dFdx", "dFdy", "fwidth", "saturate", "frac", "fmod", "lerp", "mod2", "not"
    )

    private fun promote(a: Ty, b: Ty): Ty {
        if (a.b == B.UNK || b.b == B.UNK) return Ty.UNK
        if (a.b == B.S || b.b == B.S || a.b == B.M || b.b == B.M || a.b == B.VO || b.b == B.VO) return Ty.UNK
        val base = if (a.b == B.F || b.b == B.F) B.F else if (a.b == B.U || b.b == B.U) B.U else B.I
        return Ty(base, maxOf(a.n, b.n))
    }

    // ═══════════════════════════ edits ═══════════════════════════════════

    private class Edit(val pos: Int, val text: String, val closing: Boolean = false)

    /** Public entry: apply the pass, returning the new source and a report. */
    fun applyWithReport(content: String): Pair<String, List<String>> {
        val toks = lex(content) ?: return content to emptyList()
        val edits = ArrayList<Edit>()
        val notes = ArrayList<String>()
        val code = toks.filter { it.kind != TKind.WS && it.kind != TKind.COMMENT }

        val p = ProgramParser(content, toks, code, edits, notes)
        try {
            p.parseProgram()
        } catch (e: Exception) {
            return content to emptyList()
        }
        if (!p.balanced) return content to emptyList()
        if (edits.isEmpty()) return content to emptyList()

        // Splice order for edits at the SAME position (nested wraps):
        //   1. closings, in collection order  (inner ')' before outer ')')
        //   2. openings, in REVERSE collection order (outer '(' before inner)
        // e.g. `vec2 w = u_a ? u_v : u_v.zwxy;` needs
        //      (bool(u_a) ? u_v : u_v.zwxy).xy   — never  bool((u_a) ? …).xy
        val seq = HashMap<Edit, Int>()
        edits.forEachIndexed { idx, e -> seq[e] = idx }
        val sorted = edits.sortedWith(
            compareBy({ it.pos }, { if (it.closing) 0 else 1 }, { if (it.closing) seq[it]!! else -seq[it]!! })
        )
        val sb = StringBuilder(content.length + 64)
        var cursor = 0
        for (e in sorted) {
            if (e.pos < cursor) return content to emptyList()   // overlap → no edits
            sb.append(content, cursor, e.pos)
            sb.append(e.text)
            cursor = e.pos
        }
        sb.append(content, cursor, content.length)
        return sb.toString() to notes
    }

    fun apply(content: String): String = applyWithReport(content).first

    // ═══════════════════ expression result handle ════════════════════════

    private class ERes(val ty: Ty, val startIdx: Int, val endIdx: Int)

    // ═══════════════════ base expression parser ══════════════════════════

    private abstract class ExprParser(
        @Suppress("unused") val src: String,
        @Suppress("unused") val toks: List<Tok>,
        protected val edits: MutableList<Edit>,
        protected val notes: MutableList<String>,
    ) {
        abstract val code: List<Tok>
        open var baseOffset = 0
        var i = 0

        abstract fun lookup(name: String): Ty?
        abstract fun lookupCallable(name: String): Ty?

        fun peek(k: Int = 0): Tok? = code.getOrNull(i + k)
        fun atEnd() = i >= code.size
        fun atP(p: String, k: Int = 0): Boolean = peek(k)?.punct(p) == true
        fun atId(id: String, k: Int = 0): Boolean = peek(k)?.ident(id) == true

        fun addEdit(pos: Int, text: String, closing: Boolean = false) {
            edits.add(Edit(pos + baseOffset, text, closing))
        }
        fun note(what: String, at: Tok) { notes.add("$what @char${at.start + baseOffset}") }

        fun tok(idx: Int): Tok? = code.getOrNull(idx)

        // ── entry: full expression with assignment ──
        fun parseAssignExpr(): ERes {
            val lhs = parseExpr(2)
            val t = peek() ?: return lhs
            if (t.kind == TKind.PUNCT && t.s == "=") {
                val lhsTy = lhs.ty
                i++
                val rhs = parseAssignExpr()                // right-assoc
                if (lhsTy.b != B.UNK && lhsTy.b != B.S && lhsTy.b != B.M && !lhsTy.arr) {
                    wrapTruncation(lhsTy, rhs)
                }
                return ERes(lhsTy, lhs.startIdx, rhs.endIdx)
            }
            return lhs
        }

        // ── binary / ternary; stops at '=', ',', ';', ')' … ──
        private fun parseExpr(minPrec: Int): ERes {
            var lhs = parseUnary()
            while (true) {
                val t = peek() ?: break
                if (t.kind != TKind.PUNCT) break
                val op = t.s
                when {
                    op == "?" -> {
                        if (minPrec > 2) break
                        if (lhs.ty.isNumericScalar) {
                            val first = tok(lhs.startIdx)
                            val last = tok(lhs.endIdx)
                            if (first != null && last != null) {
                                addEdit(first.start, "bool(")
                                addEdit(last.end, ")", closing = true)
                                note("ternary→bool", first)
                            }
                        }
                        i++
                        val thenE = parseAssignExpr()
                        if (atP(":")) i++
                        val elseE = parseExpr(2)            // right-assoc chain
                        val rty = if (thenE.ty.b != B.UNK) thenE.ty else elseE.ty
                        lhs = ERes(rty, lhs.startIdx, elseE.endIdx)
                    }
                    op == "||" || op == "&&" -> {
                        val prec = if (op == "||") 3 else 4
                        if (prec < minPrec) break
                        wrapBoolIfNumeric(lhs)
                        i++
                        val rhs = parseExpr(prec + 1)
                        wrapBoolIfNumeric(rhs)
                        lhs = ERes(Ty.BO1, lhs.startIdx, rhs.endIdx)
                    }
                    op == "|" -> { val r = op(5, minPrec, lhs, op) ?: break; lhs = r }
                    op == "^" -> { val r = op(6, minPrec, lhs, op) ?: break; lhs = r }
                    op == "&" -> { val r = op(7, minPrec, lhs, op) ?: break; lhs = r }
                    op == "==" || op == "!=" -> { val r = op(8, minPrec, lhs, op) ?: break; lhs = r }
                    op == "<" || op == ">" || op == "<=" || op == ">=" -> { val r = op(9, minPrec, lhs, op) ?: break; lhs = r }
                    op == "<<" || op == ">>" -> { val r = op(10, minPrec, lhs, op) ?: break; lhs = r }
                    op == "+" || op == "-" -> { val r = op(11, minPrec, lhs, op) ?: break; lhs = r }
                    op == "*" || op == "/" || op == "%" -> { val r = op(12, minPrec, lhs, op) ?: break; lhs = r }
                    else -> break
                }
            }
            return lhs
        }

        private fun op(prec: Int, minPrec: Int, lhs: ERes, opTok: String): ERes? {
            if (prec < minPrec) return null
            i++
            val rhs = parseExpr(prec + 1)
            val ty = when (opTok) {
                "==", "!=", "<", ">", "<=", ">=" -> Ty.BO1
                "<<", ">>" -> Ty(B.I, maxOf(lhs.ty.n, rhs.ty.n))
                "|", "&", "^" -> promoteInt(lhs.ty, rhs.ty)
                else -> promote(lhs.ty, rhs.ty)
            }
            return ERes(ty, lhs.startIdx, rhs.endIdx)
        }

        private fun promoteInt(a: Ty, b: Ty): Ty {
            if (a.b == B.UNK || b.b == B.UNK) return Ty.UNK
            val base = if (a.b == B.U || b.b == B.U) B.U else B.I
            return Ty(base, maxOf(a.n, b.n))
        }

        private fun wrapBoolIfNumeric(e: ERes) {
            if (!e.ty.isNumericScalar) return
            val first = tok(e.startIdx) ?: return
            val last = tok(e.endIdx) ?: return
            addEdit(first.start, "bool(")
            addEdit(last.end, ")", closing = true)
            note("logical→bool", first)
        }

        private fun parseUnary(): ERes {
            val t = peek() ?: return ERes(Ty.UNK, i, i - 1)
            if (t.kind == TKind.PUNCT) {
                when (t.s) {
                    "!" -> {
                        i++
                        val operandStart = i
                        val operand = parseUnary()
                        if (operand.ty.isNumericScalar) {
                            val first = tok(operandStart) ?: return operand
                            val last = tok(i - 1) ?: return operand
                            addEdit(first.start, "bool(")
                            addEdit(last.end, ")", closing = true)
                            note("not→bool", first)
                        }
                        return ERes(Ty.BO1, operandStart - 1, i - 1)
                    }
                    "-", "+", "~" -> {
                        i++
                        val operandStart = i
                        val operand = parseUnary()
                        return ERes(operand.ty, operandStart - 1, i - 1)
                    }
                    "++", "--" -> {
                        i++
                        val operand = parseUnary()
                        return ERes(operand.ty, i - 2, i - 1)
                    }
                }
            }
            return parsePostfix()
        }

        private fun parsePostfix(): ERes {
            var r = parsePrimary()
            while (true) {
                val t = peek() ?: break
                if (t.punct(".")) {
                    val nt = peek(1) ?: break
                    if (nt.kind == TKind.IDENT) {
                        if (nt.s == "length" && peek(2)?.punct("(") == true) {
                            i += 3
                            if (atP(")")) i++
                            r = ERes(Ty.I1, r.startIdx, i - 1)
                            continue
                        }
                        if (nt.s.isNotEmpty() && nt.s.all { it in "xyzwrgbastpq" }) {
                            i += 2
                            val cnt = minOf(4, nt.s.length)
                            val base = if (r.ty.b == B.UNK || r.ty.b == B.S || r.ty.b == B.M || r.ty.b == B.VO) B.UNK else r.ty.b
                            r = ERes(Ty(base, cnt), r.startIdx, i - 1)
                            continue
                        }
                        i += 2                                        // struct member / unknown
                        r = ERes(Ty.UNK, r.startIdx, i - 1)
                        continue
                    }
                    break
                } else if (t.punct("[")) {
                    i++
                    parseAssignExpr()
                    if (atP("]")) i++
                    val inner = when {
                        r.ty.arr -> Ty(r.ty.b, r.ty.n, false)
                        r.ty.b == B.M -> Ty(B.F, r.ty.n)
                        else -> Ty(if (r.ty.b == B.UNK) B.UNK else r.ty.b, 1)
                    }
                    r = ERes(inner, r.startIdx, i - 1)
                    continue
                } else if (t.punct("++") || t.punct("--")) {
                    i++
                    continue
                } else break
            }
            return r
        }

        private fun parsePrimary(): ERes {
            val t = peek() ?: return ERes(Ty.UNK, i, i - 1)
            when (t.kind) {
                TKind.NUM -> {
                    i++
                    return ERes(numType(t.s), i - 1, i - 1)
                }
                TKind.IDENT -> {
                    if (peek(1)?.punct("(") == true) return parseCall()
                    i++
                    return ERes(lookup(t.s) ?: Ty.UNK, i - 1, i - 1)
                }
                TKind.PUNCT -> {
                    if (t.s == "(") {
                        val openIdx = i
                        i++
                        val inner = parseAssignExpr()
                        if (atP(")")) i++
                        return ERes(inner.ty, openIdx, i - 1)
                    }
                    i++                                              // stray punct: survive
                    return ERes(Ty.UNK, i - 1, i - 1)
                }
                else -> return ERes(Ty.UNK, i, i - 1)                // PREPROC boundary: stop
            }
        }

        private fun parseCall(): ERes {
            val callStart = i
            val nameTok = peek()!!
            val name = nameTok.s
            i += 2                                                    // NAME (
            val args = ArrayList<ERes>()
            if (!atP(")")) {
                while (true) {
                    args.add(parseAssignExpr())
                    if (atP(",")) { i++; continue }
                    break
                }
            }
            if (atP(")")) i++

            // scalar constructor with a single vector argument → (arg).x
            val sc = scalarCtor(name)
            if (sc != null && args.size == 1) {
                val a = args[0]
                if (a.ty.n in 2..4 && a.ty.b != B.UNK && a.ty.b != B.S && a.ty.b != B.M) {
                    val first = tok(a.startIdx)
                    val last = tok(a.endIdx)
                    if (first != null && last != null) {
                        addEdit(first.start, "(")
                        addEdit(last.end, ").x", closing = true)
                        note("scalar-ctor→.x", first)
                    }
                }
            }
            // NOTE: vector constructors from larger vectors are LEGAL ESSL (vec2(v4)), no rewrite.

            val vc = vectorCtor(name)
            val ret: Ty = when {
                vc != null -> vc
                sc != null -> Ty(sc, 1)
                name == "mat2" -> Ty(B.M, 2)
                name == "mat3" || name == "CAST3X3" -> Ty(B.M, 3)
                name == "mat4" -> Ty(B.M, 4)
                name == "mul" -> {
                    val x = args.getOrNull(0)?.ty ?: Ty.UNK
                    val y = args.getOrNull(1)?.ty ?: Ty.UNK
                    when {
                        x.b == B.M && y.b == B.M -> x
                        x.b == B.M -> y
                        y.b == B.M -> x
                        else -> promote(x, y)
                    }
                }
                else -> lookupCallable(name)
                    ?: BUILTIN_RET[name]
                    ?: if (name in FIRST_ARG_RET) args.firstOrNull()?.ty ?: Ty.UNK else Ty.UNK
            }
            return ERes(ret, callStart, i - 1)
        }

        private fun numType(s: String): Ty = when {
            s.startsWith("0x") || s.startsWith("0X") -> Ty.I1
            s.endsWith("u") || s.endsWith("U") -> Ty(B.U)
            s.endsWith("f") || s.endsWith("F") || s.contains('.') ||
                s.contains('e') || s.contains('E') -> Ty.F1
            else -> Ty.I1
        }

        /**
         * fxc implicit conversions that GLSL ES 3.00 rejects, replicated at
         * source level for initialization / assignment / return:
         *
         *  A. implicit truncation  (fxc X3206): vecN → smaller type keeps the
         *     leading components          →  (expr).xyz
         *  B. base conversion: fxc converts any numeric base implicitly
         *     (float→int, anything→bool, bool→float, …) while ESSL only
         *     allows int→uint, int→float and uint→float. Everything else
         *     gets an explicit constructor. NOTE: the PC's own fixShader
         *     int→float pass (`int d = 3;` → `int d = 3.0;`) lands here too
         *     — fxc compiles that fine, strict ESSL does not.
         */
        fun wrapTruncation(lhs: Ty, rhs: ERes) {
            val rt = rhs.ty
            if (rt.b == B.UNK || rt.b == B.S || rt.b == B.M || rt.b == B.VO || rt.arr) return
            if (lhs.b == B.UNK || lhs.b == B.S || lhs.b == B.M || lhs.b == B.VO || lhs.arr) return
            if (rt.n !in 1..4 || lhs.n !in 1..4) return
            if (rt.b != B.F && rt.b != B.I && rt.b != B.U && rt.b != B.BO) return
            if (lhs.b != B.F && lhs.b != B.I && lhs.b != B.U && lhs.b != B.BO) return
            val truncates = rt.n > lhs.n
            val convertsBad = lhs.b != rt.b && !esslImplicitlyConverts(rt.b, lhs.b)
            if (!truncates && !convertsBad) return
            val first = tok(rhs.startIdx) ?: return
            val last = tok(rhs.endIdx) ?: return

            if (truncates) {
                val swz = "." + "xyzw".take(lhs.n)
                if (lhs.b == rt.b) {
                    addEdit(first.start, "(")
                    addEdit(last.end, ")$swz", closing = true)
                } else {
                    val ctor = scalarCtorName(lhs.b) ?: return
                    addEdit(first.start, "$ctor(")
                    addEdit(last.end, ")$swz)", closing = true)
                }
                note("truncation→$swz", first)
            } else {
                // same size (or scalar broadcast), incompatible base
                if (rt.n == lhs.n) {
                    val ctor = scalarCtorName(lhs.b) ?: return
                    addEdit(first.start, "$ctor(")
                    addEdit(last.end, ")", closing = true)
                    note("base→${ctor}", first)
                } else if (rt.n < lhs.n) {
                    // scalar broadcast into a vector of another base
                    val ctor = vectorCtorName(lhs) ?: return
                    addEdit(first.start, "$ctor(")
                    addEdit(last.end, ")", closing = true)
                    note("base→${ctor}", first)
                }
            }
        }

        /** ESSL 3.00 §4.1.10: only these implicit conversions exist. */
        private fun esslImplicitlyConverts(from: B, to: B): Boolean = when {
            from == to -> true
            from == B.I && to == B.U -> true
            from == B.I && to == B.F -> true
            from == B.U && to == B.F -> true
            else -> false
        }

        private fun scalarCtorName(b: B): String? = when (b) {
            B.F -> "float"; B.I -> "int"; B.U -> "uint"; B.BO -> "bool"; else -> null
        }

        private fun vectorCtorName(t: Ty): String? = when (t.b) {
            B.F -> when (t.n) { 2 -> "vec2"; 3 -> "vec3"; 4 -> "vec4"; else -> null }
            B.I -> when (t.n) { 2 -> "ivec2"; 3 -> "ivec3"; 4 -> "ivec4"; else -> null }
            B.U -> when (t.n) { 2 -> "uvec2"; 3 -> "uvec3"; 4 -> "uvec4"; else -> null }
            B.BO -> when (t.n) { 2 -> "bvec2"; 3 -> "bvec3"; 4 -> "bvec4"; else -> null }
            else -> null
        }

        fun checkConditionBool(cond: ERes) {
            if (!cond.ty.isNumericScalar) return
            val first = tok(cond.startIdx) ?: return
            val last = tok(cond.endIdx) ?: return
            addEdit(first.start, "bool(")
            addEdit(last.end, ")", closing = true)
            note("condition→bool", first)
        }
    }

    // ═══════════════ program parser (statements + symbols) ═══════════════

    private val MODIFIERS = setOf("uniform", "varying", "attribute", "const", "static",
        "in", "out", "inout", "flat", "smooth", "noperspective", "highp", "mediump", "lowp")

    private class ProgramParser(
        src: String,
        toks: List<Tok>,
        override val code: List<Tok>,
        edits: MutableList<Edit>,
        notes: MutableList<String>,
    ) : ExprParser(src, toks, edits, notes) {

        val global = HashMap<String, Ty>()
        val scopes = ArrayList<HashMap<String, Ty>>()
        val functions = HashMap<String, FnSig>()
        val macroObj = HashMap<String, Ty>()
        val macroFn = HashMap<String, Ty>()
        val fnReturns = ArrayDeque<Ty>()
        var balanced = true

        override fun lookup(name: String): Ty? {
            for (s in scopes.asReversed()) if (s.containsKey(name)) return s[name]
            if (global.containsKey(name)) return global[name]
            if (macroObj.containsKey(name)) return macroObj[name]
            return null
        }

        override fun lookupCallable(name: String): Ty? {
            if (functions.containsKey(name)) return functions[name]!!.ret
            if (macroFn.containsKey(name)) return macroFn[name]
            if (macroObj.containsKey(name)) return macroObj[name]
            return null
        }

        fun parseProgram() {
            registerCombosAndSystemDefines()
            while (!atEnd()) {
                val t = peek()!!
                when {
                    t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                    t.punct(";") -> i++
                    t.punct("{") -> { enterScope(); i++ }
                    t.punct("}") -> { leaveScope(); i++ }
                    else -> parseStatement()
                }
            }
            if (scopes.isNotEmpty()) balanced = false
        }

        private fun enterScope() { scopes.add(HashMap()) }
        private fun leaveScope() {
            if (scopes.isEmpty()) { balanced = false; return }
            scopes.removeAt(scopes.size - 1)
        }

        private fun registerCombosAndSystemDefines() {
            // The engine injects `#define NAME <int>` for every [COMBO] at load
            // time (verified: `#define INVERT 0` in its own shader-compile log)
            // and integer system defines (PLATFORM_ANDROID 1, GLSL 1, ...).
            for (n in listOf("PLATFORM_ANDROID", "GLSL", "HLSL", "SCENE_ORTHO",
                "PLATFORM_DIRECTX", "PLATFORM_WINDOWS")) macroObj[n] = Ty.I1
            // Combos are declared three ways in WE shaders, and the engine
            // turns ALL of them into integer defines (verified:
            // `#define INVERT 0` in its own shader-compile log):
            //   // [COMBO]     {"combo":"INVERT",...}     (standalone)
            //   // [COMBO_OFF] {"combo":"SPECULAR",...}   (default-off variant)
            //   uniform ...; // {"combo":"MASK",...}      (uniform-line metadata)
            val rx = Regex(""""combo"\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"""")
            for (t in toks) {
                if (t.kind != TKind.COMMENT) continue
                if (!t.s.contains("\"combo\"")) continue
                for (m in rx.findAll(t.s)) global[m.groupValues[1]] = Ty.I1
            }
        }

        private fun handlePreproc(t: Tok) {
            val body = t.s.trim()
            if (!body.startsWith("#define")) return
            val nameM = Regex("""^#define\s+([A-Za-z_][A-Za-z0-9_]*)""").find(body) ?: return
            val name = nameM.groupValues[1]
            // C preprocessor rule: a macro is FUNCTION-like only when the
            // '(' follows the name with NO whitespace. `#define FLAG (u_a)`
            // is an OBJECT-like macro whose body is `(u_a)`.
            val afterRaw = body.substring(nameM.range.last + 1)
            val afterName = afterRaw.trim()
            if (afterRaw.startsWith("(")) {
                // function-like macro: #define NAME(a,b) BODY
                val open = body.indexOf('(', nameM.range.last + 1)
                if (open < 0) return
                var depth = 0
                var close = -1
                for (j in open until body.length) {
                    if (body[j] == '(') depth++
                    else if (body[j] == ')') { depth--; if (depth == 0) { close = j; break } }
                }
                if (close < 0) return
                val macroBody = body.substring(close + 1).trim()
                if (macroBody.isEmpty() || macroBody.length > 400) return
                val bodyStartInSrc = t.start + t.s.indexOf(macroBody)
                val sub = MacroBodyParser(this, macroBody, edits, notes, bodyStartInSrc)
                try {
                    val ty = sub.parseAssignExpr()
                    if (ty.ty.b != B.UNK) macroFn[name] = ty.ty
                } catch (e: Exception) { /* best effort */ }
            } else if (afterName.isNotEmpty()) {
                val lit = afterName.substringBefore(' ').trim()
                val ty = when {
                    lit.startsWith("0x") || lit.startsWith("0X") -> Ty.I1
                    lit.matches(Regex("""-?\d+[uU]""")) -> Ty(B.U)
                    lit.matches(Regex("""-?\d+\.\d*[fF]?""")) ||
                        lit.matches(Regex("""-?\d+[fF]""")) -> Ty.F1
                    lit.matches(Regex("""-?\d+""")) -> Ty.I1
                    else -> null
                }
                if (ty != null) {
                    macroObj[name] = ty
                } else if (afterName.length <= 400) {
                    // non-literal object-like body: #define FLAG (u_a) —
                    // infer the body's type (and fix its constructs) so uses
                    // like `FLAG ? a : b` expand to ESSL-safe code.
                    val bodyStartInSrc = t.start + t.s.indexOf(afterName)
                    val sub = MacroBodyParser(this, afterName, edits, notes, bodyStartInSrc)
                    try {
                        val r = sub.parseAssignExpr()
                        if (r.ty.b != B.UNK) macroObj[name] = r.ty
                    } catch (e: Exception) { /* best effort */ }
                }
            }
        }

        // ── statements ──
        fun parseStatement() {
            while (peek()?.kind == TKind.IDENT &&
                (peek()!!.s in MODIFIERS || peek()!!.s == "layout" || peek()!!.s == "precision")) {
                when (peek()!!.s) {
                    "layout" -> skipParens()
                    "precision" -> { skipToSemicolon(); return }
                    else -> i++
                }
            }
            val t = peek() ?: return
            when {
                t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                t.punct("{") -> { enterScope(); i++ }
                t.punct("}") -> { leaveScope(); i++ }
                t.punct(";") -> i++
                t.kind != TKind.IDENT -> skipToSemicolon()
                t.ident("struct") -> parseStruct()
                t.ident("if") -> parseIf()
                t.ident("while") -> parseWhile()
                t.ident("do") -> parseDoWhile()
                t.ident("for") -> parseFor()
                t.ident("return") -> parseReturn()
                t.ident("break") || t.ident("continue") || t.ident("discard") -> { i++; if (atP(";")) i++ }
                t.ident("else") -> i++
                else -> {
                    val ty = typeKeyword(t.s)
                    val nameTok = peek(1)
                    val after = peek(2)
                    if (ty != null && nameTok?.kind == TKind.IDENT && after != null &&
                        (after.punct("=") || after.punct(";") || after.punct(",") || after.punct("["))) {
                        parseDeclaration(ty)
                    } else if (ty != null && nameTok?.kind == TKind.IDENT && after?.punct("(") == true) {
                        parseFunction(ty, nameTok)
                    } else {
                        parseExpressionStatement()
                    }
                }
            }
        }

        private fun parseStruct() {
            i++
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("{")) depth++
                else if (t.punct("}")) { depth--; if (depth == 0) break }
            }
            skipToSemicolon()
        }

        private fun parseFunction(ret: Ty, nameTok: Tok) {
            i += 2
            val bodyScope = HashMap<String, Ty>()
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            while (!atEnd() && !atP(")")) {
                while (peek()?.kind == TKind.IDENT && peek()!!.s in MODIFIERS) i++
                val pt = peek() ?: break
                val pty = typeKeyword(pt.s)
                if (pty != null) {
                    i++
                    if (peek()?.kind == TKind.IDENT) {
                        val pname = next()
                        var arr = false
                        while (!atEnd() && !atP(")") && !atP(",")) {
                            if (atP("[")) arr = true
                            i++
                        }
                        bodyScope[pname!!.s] = Ty(pty.b, pty.n, arr)
                    }
                } else if (!atP(",") && !atP(")")) {
                    i++
                }
                if (atP(",")) i++
            }
            if (atP(")")) i++
            functions[nameTok.s] = FnSig(ret)
            if (atP(";")) { i++; return }                              // prototype
            fnReturns.addLast(ret)
            scopes.add(bodyScope)
            if (atP("{")) {
                i++
                parseBlockInner()
                if (atP("}")) i++
            } else {
                skipToSemicolon()
            }
            if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
            if (fnReturns.isNotEmpty()) fnReturns.removeLast()
        }

        private fun next(): Tok? = peek()?.also { i++ }

        private fun parseBlockInner() {
            while (!atEnd() && !atP("}")) {
                val t = peek()!!
                when {
                    t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                    t.punct(";") -> i++
                    else -> parseStatement()
                }
            }
        }

        private fun parseDeclaration(ty: Ty) {
            i++
            while (true) {
                val nameTok = peek()
                if (nameTok?.kind != TKind.IDENT) { skipToSemicolon(); return }
                i++
                var arr = false
                while (atP("[")) { arr = true; skipBracketGroup() }
                val varTy = Ty(ty.b, ty.n, arr)
                scopePutRedeclareSafe(nameTok.s, varTy)
                if (atP("=")) {
                    i++
                    val rhs = parseAssignExpr()
                    // LHS type for the truncation check: what THIS branch sees
                    val effTy = currentScopeType(nameTok.s) ?: varTy
                    wrapTruncation(effTy, rhs)
                }
                if (atP(",")) { i++; continue }
                if (atP(";")) { i++; return }
                skipToSemicolon()
                return
            }
        }

        /**
         * A redeclaration with a DIFFERENT type in the same scope means the
         * GLSL is branch-legal only via the preprocessor (#if/#else variants
         * of the same variable — e.g. generic.vert's vec4/vec2 v_TexCoord).
         * The engine's preprocessor sees one branch; we see both. Such names
         * are typed UNKNOWN so their assignments are never rewritten.
         */
        private fun scopePutRedeclareSafe(name: String, t: Ty) {
            val scope = if (scopes.isEmpty()) global else scopes.last()
            val prev = scope[name]
            if (prev != null && (prev.b != t.b || prev.n != t.n || prev.arr != t.arr)) {
                scope[name] = Ty.UNK
            } else {
                scope[name] = t
            }
        }

        private fun currentScopeType(name: String): Ty? {
            for (s in scopes.asReversed()) if (s.containsKey(name)) return s[name]
            return global[name]
        }

        private fun parseExpressionStatement() {
            parseAssignExpr()
            if (atP(";")) i++
            else if (!atEnd() && !atP("}") && !atP(")")) skipToSemicolon()
        }

        private fun parseIf() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val cond = parseAssignExpr()
            checkConditionBool(cond)
            if (atP(")")) i++
            parseBody()
            if (atId("else")) {
                i++
                if (atId("if")) parseIf() else parseBody()
            }
        }

        private fun parseBody() {
            if (atP("{")) {
                enterScope(); i++
                parseBlockInner()
                if (atP("}")) { i++; leaveScope() }
            } else parseStatement()
        }

        private fun parseWhile() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val cond = parseAssignExpr()
            checkConditionBool(cond)
            if (atP(")")) i++
            parseBody()
        }

        private fun parseDoWhile() {
            i++
            parseBody()
            if (atId("while") && atP("(", 1)) {
                i += 2
                val cond = parseAssignExpr()
                checkConditionBool(cond)
                if (atP(")")) i++
                if (atP(";")) i++
            }
        }

        private fun parseFor() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val depth0 = scopes.size
            scopes.add(HashMap())
            if (!atP(";")) parseStatement()                             // init
            if (atP(";")) i++
            if (!atP(";")) {
                val cond = parseAssignExpr()
                checkConditionBool(cond)
            }
            if (atP(";")) i++
            if (!atP(")")) parseAssignExpr()                           // increment
            if (atP(")")) i++
            parseBody()
            while (scopes.size > depth0) leaveScope()
        }

        private fun parseReturn() {
            i++
            val ret = fnReturns.lastOrNull() ?: Ty.VOID
            if (atP(";")) { i++; return }
            val rhs = parseAssignExpr()
            wrapTruncation(ret, rhs)
            if (atP(";")) i++
        }

        private fun skipToSemicolon() {
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.kind == TKind.PREPROC) continue
                if (t.punct("(") || t.punct("[") || t.punct("{")) depth++
                else if (t.punct(")") || t.punct("]") || t.punct("}")) {
                    if (depth == 0) { i--; return }
                    depth--
                } else if (t.punct(";") && depth == 0) return
            }
        }

        private fun skipParens() {
            if (!atP("(")) return
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("(")) depth++
                else if (t.punct(")")) { depth--; if (depth == 0) return }
            }
        }

        private fun skipBracketGroup() {
            if (!atP("[")) return
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("[")) depth++
                else if (t.punct("]")) { depth--; if (depth == 0) return }
            }
        }
    }

    // ═════════════════ macro-body sub-parser (#define bodies) ═════════════

    private class MacroBodyParser(
        val host: ProgramParser,
        body: String,
        edits: MutableList<Edit>,
        notes: MutableList<String>,
        bodyStartInSrc: Int,
    ) : ExprParser("", emptyList(), edits, notes) {
        override val code: List<Tok> =
            lex(body)?.filter { it.kind != TKind.WS && it.kind != TKind.COMMENT } ?: emptyList()
        override var baseOffset = bodyStartInSrc

        override fun lookup(name: String): Ty? = host.lookup(name)
        override fun lookupCallable(name: String): Ty? = host.lookupCallable(name)
    }
}
