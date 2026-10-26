/*
 * dxt_jni.cpp — JNI wrapper for the DXT/BCn decoder.
 *
 * Exposes:
 *   nativeDecodeDxt(srcBytes, width, height, formatId) -> rgbaBytes
 *
 * where formatId is one of:
 *   1 = DXT1 (BC1)
 *   3 = DXT3 (BC2)
 *   5 = DXT5 (BC3)
 *   4 = BC4  (RGTC1)
 *   6 = BC5  (RGTC2, this avoids clashing with DXT5=5)
 *
 * The format IDs match the repkg TexFormat enum values where applicable:
 *   DXT1 = format 7, DXT3 = format 6, DXT5 = format 4
 * BUT for the JNI call we use a clean enum (1, 3, 5, 4, 6) to avoid
 * confusion. The Kotlin caller maps the .tex format field to these IDs.
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
 * Signature: ([BIIIB)[B
 *
 * Decodes DXT/BCn block-compressed texture data to RGBA8888.
 *
 * Args:
 *   srcBytes  - byte[] of compressed block data
 *   width     - image width in pixels
 *   height    - image height in pixels
 *   formatId  - format ID (1=DXT1, 3=DXT3, 5=DXT5, 4=BC4, 6=BC5)
 *
 * Returns: byte[] of RGBA8888 (length = width * height * 4)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_DxtDecoder_nativeDecodeDxt(
    JNIEnv *env, jclass /* cls */,
    jbyteArray srcBytes,
    jint width, jint height,
    jint formatId) {

    // Validate format ID
    DxtFormat fmt;
    switch (formatId) {
        case 1: fmt = DxtFormat::DXT1; break;
        case 3: fmt = DxtFormat::DXT3; break;
        case 5: fmt = DxtFormat::DXT5; break;
        case 4: fmt = DxtFormat::BC4;  break;
        case 6: fmt = DxtFormat::BC5;  break;
        default:
            jclass exc = env->FindClass("java/lang/IllegalArgumentException");
            if (exc != nullptr) {
                env->ThrowNew(exc, "Unknown DXT format ID (expected 1=DXT1, 3=DXT3, 5=DXT5, 4=BC4, 6=BC5)");
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
                         out.data(), outLen);

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
