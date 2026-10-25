/*
 * etc2_jni.cpp — JNI wrapper for ETC2 RGBA8 encoding using Google's etc2comp library.
 *
 * Exposes a single function:
 *   nativeEncodeEtc2Rgba8(rgba_bytes, width, height) -> etc2_bytes
 *
 * The output is raw ETC2 RGBA8 block data (no KTX container, no header).
 * For a width×height image:
 *   blocks_x = (width + 3) / 4
 *   blocks_y = (height + 3) / 4
 *   total_blocks = blocks_x * blocks_y
 *   output_size = total_blocks * 16  (each block is 16 bytes)
 *
 * The output is then LZ4-compressed by the Kotlin side before being written
 * to the .tex file.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <memory>

// ETC2 library headers
#include "Etc.h"
#include "EtcImage.h"
#include "EtcColor.h"
#include "EtcColorFloatRGBA.h"
#include "EtcErrorMetric.h"

using namespace Etc;

extern "C" {

/*
 * Class:     com_wepwawet_pairing_Etc2Encoder
 * Method:    nativeEncodeEtc2Rgba8
 * Signature: ([BII)[B
 *
 * Encodes RGBA8888 pixel data (R,G,B,A per pixel, row-major, top-down) as
 * ETC2 RGBA8 block-compressed data.
 *
 * Input: byte[] of RGBA8888 pixels (4 bytes per pixel, length = width*height*4)
 * Returns: byte[] of ETC2 RGBA8 data (16 bytes per 4x4 block, no header)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_Etc2Encoder_nativeEncodeEtc2Rgba8(
    JNIEnv *env, jclass /* cls */, jbyteArray rgba_bytes,
    jint width, jint height) {

    // Get RGBA bytes from Java
    jsize input_size = env->GetArrayLength(rgba_bytes);
    jbyte *input_bytes = env->GetByteArrayElements(rgba_bytes, nullptr);
    if (input_bytes == nullptr) {
        return nullptr; // OutOfMemoryError already thrown
    }

    // Validate size
    jsize expected_size = (jsize)width * (jsize)height * 4;
    if (input_size < expected_size) {
        env->ReleaseByteArrayElements(rgba_bytes, input_bytes, JNI_ABORT);
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "Input RGBA bytes too small for given dimensions");
        }
        return nullptr;
    }

    // Convert raw RGBA bytes (0..255 each) to float array (0..1 each)
    // Etc::Image takes a float* a_pafSourceRGBA where each pixel is 4 floats (R,G,B,A)
    // arranged as [R0, G0, B0, A0, R1, G1, B1, A1, ...]
    //
    // IMPORTANT: Do NOT flip vertically. etc2comp expects source pixels in
    // row-major top-down order (row 0 = top of image), and outputs ETC2 blocks
    // in the SAME top-to-bottom, left-to-right order. This matches the WE Android
    // .tex format (block 0 = top-left of image), which is what glCompressedTexImage2D
    // expects for ETC2 (standard OpenGL/KTX raster order).
    //
    // Proof: The real WE .tex file (materials_69721567_p0.tex from 2279314480.mpkg)
    // was decoded with texture2ddecoder.decode_etc2a8() WITHOUT any flip, and
    // Mordred's hair appeared at the top (correct orientation). The etc2comp library
    // also outputs top-to-bottom with no flip option (verified in EtcImage.cpp).
    int pixel_count = width * height;
    std::vector<float> rgba_floats(pixel_count * 4);

    for (int i = 0; i < pixel_count; i++) {
        unsigned char r = (unsigned char)input_bytes[i * 4 + 0];
        unsigned char g = (unsigned char)input_bytes[i * 4 + 1];
        unsigned char b = (unsigned char)input_bytes[i * 4 + 2];
        unsigned char a = (unsigned char)input_bytes[i * 4 + 3];
        rgba_floats[i * 4 + 0] = r / 255.0f;  // R
        rgba_floats[i * 4 + 1] = g / 255.0f;  // G
        rgba_floats[i * 4 + 2] = b / 255.0f;  // B
        rgba_floats[i * 4 + 3] = a / 255.0f;  // A
    }

    // Release the input bytes (we have our own copy now)
    env->ReleaseByteArrayElements(rgba_bytes, input_bytes, JNI_ABORT);

    // Create EtcImage with the float RGBA pixels
    // CRITICAL: Use ErrorMetric::RGBA (not RGBX) so the encoder considers ALL 4 channels
    // (R, G, B, A) when minimizing error. With RGBX (ignore alpha), the encoder might
    // produce blocks where alpha is wrong (e.g., all 0 or all 255). This breaks the
    // X-ray shader which reads mask.a as the blend factor — wrong alpha = no X-ray.
    // For RGBA PNGs (like the X-ray mask "talia no backg.tex"), alpha accuracy is
    // essential for the X-ray effect to work.
    Etc::Image image(rgba_floats.data(),
                     (unsigned int)width, (unsigned int)height,
                     Etc::ErrorMetric::RGBA);

    // Encode as ETC2 RGBA8
    // Parameters: format, errormetric, effort (0..100), num_jobs, max_jobs
    // Use RGBA error metric to preserve alpha channel accuracy
    Etc::Image::EncodingStatus encStatus = image.Encode(
        Etc::Image::Format::RGBA8,  // ETC2 RGBA8 (format=5 in .tex header)
        Etc::ErrorMetric::RGBA,    // consider all 4 channels (R, G, B, A)
        40.0f,         // effort (40 = medium, balanced quality/speed)
        4,             // uiJobs (use 4 threads)
        1024           // uiMaxJobs (per etc2comp's MAX_JOBS)
    );

    // Check encoding result
    if ((unsigned int)encStatus >= (unsigned int)Etc::Image::EncodingStatus::ERROR_THRESHOLD) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "ETC2 encoding failed (error status)");
        }
        return nullptr;
    }

    // Get the encoded ETC2 bytes
    unsigned char *encoded = image.GetEncodingBits();
    unsigned int encoded_bytes = image.GetEncodingBitsBytes();
    if (encoded == nullptr || encoded_bytes == 0) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "ETC2 encoding returned null");
        }
        return nullptr;
    }

    // Create output Java byte array
    jbyteArray output = env->NewByteArray((jsize)encoded_bytes);
    if (output == nullptr) {
        return nullptr; // OutOfMemoryError already thrown
    }
    env->SetByteArrayRegion(output, 0, (jsize)encoded_bytes,
        reinterpret_cast<const jbyte *>(encoded));

    return output;
}

} // extern "C"
