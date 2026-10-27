/*
 * dxt_jni.cpp — JNI wrapper for the DXT/BCn decoder.
 *
 * Exposes:
 *   nativeDecodeDxt(srcBytes, width, height, formatId, use1BitAlpha) -> rgbaBytes
 *
 * where formatId is one of:
 *   1 = DXT1 (BC1)
 *   3 = DXT3 (BC2)
 *   5 = DXT5 (BC3)
 *   4 = BC4  (RGTC1)
 *   6 = BC5  (RGTC2, this avoids clashing with DXT5=5)
 *   7 = BC6H (BPTC float HDR RGB)        — v0.6.0
 *   8 = BC7  (BPTC high-quality)           — v0.6.0
 *   9 = RG88 (2-channel, 2 bytes/pixel)    — v0.6.0
 *   10 = R8  (1-channel, 1 byte/pixel)      — v0.6.0
 *
 * The format IDs match the repkg TexFormat enum values where applicable:
 *   DXT1 = format 7, DXT3 = format 6, DXT5 = format 4
 * BUT for the JNI call we use a clean enum to avoid confusion.
 *
 * v0.6.0 (parity §6 6-4): Added use1BitAlpha parameter for DXT1 vs DXT1a
 * distinction. The .tex flags field (bit 3 = HasAlpha) determines whether
 * DXT1 uses 1-bit alpha (DXT1a) or 4-color opaque (DXT1).
 *
 * Input: byte[] of DXT block data (no header, just the block bytes).
 * Returns: byte[] of RGBA8888 pixels (4 bytes per pixel, length = width*height*4).
 *         Returns null on error (after throwing a Java exception).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "dxt_decoder.h"

using namespace wepwawet;

extern "C" {

/*
 * Class:     com_wepwawet_pairing_DxtDecoder
 * Method:    nativeDecodeDxt
 * Signature: ([BIIIZ)[B
 *
 * Decodes DXT/BCn block-compressed texture data to RGBA8888.
 *
 * Args:
 *   srcBytes      - byte[] of compressed block data
 *   width         - image width in pixels
 *   height        - image height in pixels
 *   formatId      - format ID (1=DXT1, 3=DXT3, 5=DXT5, 4=BC4, 6=BC5, 7=BC6H, 8=BC7, 9=RG88, 10=R8)
 *   use1BitAlpha  - true for DXT1a (1-bit alpha), false for DXT1 (4-color opaque)
 *
 * Returns: byte[] of RGBA8888 (length = width * height * 4)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_DxtDecoder_nativeDecodeDxt(
    JNIEnv *env, jclass /* cls */,
    jbyteArray srcBytes,
    jint width, jint height,
    jint formatId,
    jboolean use1BitAlpha) {

    // Validate format ID
    DxtFormat fmt;
    switch (formatId) {
        case 1: fmt = DxtFormat::DXT1; break;
        case 3: fmt = DxtFormat::DXT3; break;
        case 5: fmt = DxtFormat::DXT5; break;
        case 4: fmt = DxtFormat::BC4;  break;
        case 6: fmt = DxtFormat::BC5;  break;
        case 7: fmt = DxtFormat::BC6H; break;  // v0.6.0
        case 8: fmt = DxtFormat::BC7;  break;  // v0.6.0
        default:
            jclass exc = env->FindClass("java/lang/IllegalArgumentException");
            if (exc != nullptr) {
                env->ThrowNew(exc, "Unknown DXT format ID (expected 1=DXT1, 3=DXT3, 5=DXT5, 4=BC4, 6=BC5, 7=BC6H, 8=BC7)");
            }
            return nullptr;
    }

    // Get input bytes
    jsize srcLen = env->GetArrayLength(srcBytes);
    if (srcLen <= 0) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "Empty input data");
        }
        return nullptr;
    }
    jbyte* src = env->GetByteArrayElements(srcBytes, nullptr);
    if (src == nullptr) {
        return nullptr; // OutOfMemoryError
    }

    // Compute required input size and verify
    int blocksX = (width + 3) / 4;
    int blocksY = (height + 3) / 4;
    int blockSize = dxtBlockSize(fmt);
    size_t requiredSrc = (size_t)blocksX * blocksY * blockSize;
    if ((size_t)srcLen < requiredSrc) {
        env->ReleaseByteArrayElements(srcBytes, src, JNI_ABORT);
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "Input data too small for given dimensions and format");
        }
        return nullptr;
    }

    // Allocate output
    size_t outLen = (size_t)width * height * 4;
    std::vector<uint8_t> out(outLen);

    // Decode
    bool ok = decodeDxt((const uint8_t*)src, srcLen, width, height, fmt,
                         out.data(), outLen,
                         use1BitAlpha != JNI_TRUE);

    // Release input (we have our own copy)
    env->ReleaseByteArrayElements(srcBytes, src, JNI_ABORT);

    if (!ok) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "DXT decoding failed (see logs)");
        }
        return nullptr;
    }

    // Wrap the output in a Java byte array
    jbyteArray result = env->NewByteArray((jsize)outLen);
    if (result == nullptr) {
        return nullptr; // OutOfMemoryError
    }
    env->SetByteArrayRegion(result, 0, (jsize)outLen,
        reinterpret_cast<const jbyte*>(out.data()));

    return result;
}

} // extern "C"
