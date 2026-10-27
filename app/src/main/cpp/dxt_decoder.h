/*
 * dxt_decoder.h — DXT (S3TC) / BCn block-compressed texture decoder.
 *
 * Decodes the following formats to RGBA8888 (R,G,B,A per pixel, row-major
 * top-down, 4 bytes per pixel):
 *
 *   - DXT1  (BC1, 8 bytes per 4x4 block)  — RGB, optional 1-bit alpha
 *   - DXT3  (BC2, 16 bytes per 4x4 block) — RGB + 4-bit explicit alpha
 *   - DXT5  (BC3, 16 bytes per 4x4 block) — RGB + interpolated 8-bit alpha
 *   - BC4   (RGTC1, 8 bytes per 4x4 block) — single-channel (R only)
 *   - BC5   (RGTC2, 16 bytes per 4x4 block) — two-channel (R + G)
 *   - BC6H  (BPTC float, 16 bytes per 4x4 block) — HDR RGB float (UF16 / SF16)
 *   - BC7   (BPTC, 16 bytes per 4x4 block) — high-quality, 8 modes
 *
 * These are the formats used by Wallpaper Engine PC for Scene wallpaper
 * textures stored in .pkg files. When WE PC sends a wallpaper to WE Android,
 * it transcodes DXT -> ETC2_RGBA8 using resourcecompiler64.exe.
 *
 * v0.6.0 (parity §6 6-9 / 6-10): Added BC6H and BC7 decoders. BC6H HDR
 * textures (format=13) and BC7 high-quality textures (format=14) are used by
 * post-2019 Workshop wallpapers.
 *
 * Algorithm references:
 *  - Microsoft DirectX SDK docs: BC1, BC2, BC3 (DXT1/3/5)
 *  - Khronos KHR_texture_compression specs
 *  - Khronos KHR_texture_compression_bptc (BC6H + BC7)
 *  - Wikipedia: S3 Texture Compression
 *  - iOrange/bc7dec reference decoder (BC7)
 *  - nicebyte/bc6henc reference decoder (BC6H)
 *
 * Block layout for DXT1 (8 bytes):
 *   - color0 (u16 RGB565 LE)
 *   - color1 (u16 RGB565 LE)
 *   - 4x4 2-bit lookup table (32 bits LE)
 *
 *   - If color0 > color1: 4-color palette
 *       c0 = unpack(color0)
 *       c1 = unpack(color1)
 *       c2 = (2*c0 + c1) / 3
 *       c3 = (c0 + 2*c1) / 3
 *   - If color0 <= color1: 3-color palette + transparent
 *       c0 = unpack(color0)
 *       c1 = unpack(color1)
 *       c2 = (2*c0 + c1) / 3
 *       c3 = transparent black (r=g=b=a=0)
 *
 * Block layout for DXT3 (16 bytes):
 *   - 4-bit alpha per pixel (64 bits = 8 bytes, packed 2 pixels per byte)
 *   - DXT1 color block (8 bytes)
 *
 * Block layout for DXT5 (16 bytes):
 *   - alpha0 (u8)
 *   - alpha1 (u8)
 *   - 4-bit alpha lookup per pixel (48 bits = 6 bytes, packed)
 *   - DXT1 color block (8 bytes)
 *
 *   - If alpha0 > alpha1: 8-alpha palette (interpolated)
 *       a0 = alpha0, a1 = alpha1
 *       a2 = (6*a0 + a1) / 7, a3 = (5*a0 + 2*a1) / 7, ...
 *       a6 = (a0 + 6*a1) / 7, a7 = (a1)  [actually a7 = 0 by spec]
 *   - If alpha0 <= alpha1: 6-alpha palette + 2 special
 *       a0 = alpha0, a1 = alpha1
 *       a2..a5 = interpolated
 *       a6 = 0, a7 = 255
 *
 * Block layout for BC4 (8 bytes, single channel):
 *   - red0 (u8), red1 (u8)
 *   - 4-bit lookup per pixel (48 bits = 6 bytes)
 *   Same interpolation as DXT5 alpha.
 *
 * Block layout for BC5 (16 bytes, two channels):
 *   - red0, red1 + 4-bit red lookups (8 bytes)  — same as BC4
 *   - green0, green1 + 4-bit green lookups (8 bytes) — same as BC4
 *
 * Output: RGBA8888 pixels, alpha=255 for DXT1 (when not transparent),
 * alpha=255 for BC4/BC5 (with R channel), alpha=255 for BC5 (with R+G channels).
 */

#ifndef WEPPAWAWET_DXT_DECODER_H
#define WEPPAWAWET_DXT_DECODER_H

#include <cstdint>
#include <cstddef>

namespace wepwawet {

enum class DxtFormat {
    DXT1 = 1,  // BC1 — 8 bytes per block, RGB (optional 1-bit alpha)
    DXT3 = 3,  // BC2 — 16 bytes per block, RGB + 4-bit explicit alpha
    DXT5 = 5,  // BC3 — 16 bytes per block, RGB + 8-bit interpolated alpha
    BC4  = 4,  // RGTC1 — 8 bytes per block, single channel (R)
    BC5  = 6,  // RGTC2 — 16 bytes per block, two channels (R + G). NOTE: NOT 5 (collides with DXT5)
    BC6H = 7,  // BPTC float — 16 bytes per block, HDR RGB (UF16 only — SF16 rare in Workshop)
    BC7  = 8,  // BPTC — 16 bytes per block, high-quality 8-mode RGBA
};

/**
 * Get the block size in bytes for a DXT format.
 */
inline int dxtBlockSize(DxtFormat fmt) {
    switch (fmt) {
        case DxtFormat::DXT1: return 8;
        case DxtFormat::DXT3: return 16;
        case DxtFormat::DXT5: return 16;
        case DxtFormat::BC4:  return 8;
        case DxtFormat::BC5:  return 16;
        case DxtFormat::BC6H: return 16;
        case DxtFormat::BC7:  return 16;
    }
    return 0;
}

/**
 * Decode a DXT/BCn block-compressed texture to RGBA8888.
 *
 * @param srcData Pointer to the compressed block data (must contain
 *                blocks_x * blocks_y * dxtBlockSize(fmt) bytes).
 * @param srcLen  Length of srcData in bytes.
 * @param width   Image width in pixels (must match the texture's width).
 * @param height  Image height in pixels.
 * @param fmt     DXT format to decode (DXT1, DXT3, DXT5, BC4, BC5, BC6H, BC7).
 * @param outRgba Pointer to a buffer of at least width*height*4 bytes
 *                where the RGBA8888 output will be written.
 * @param outLen  Length of outRgba in bytes.
 * @param use1BitAlpha For DXT1 only: true = DXT1a (1-bit alpha), false = DXT1 (4-color opaque)
 * @return true on success, false on bad input.
 */
bool decodeDxt(const uint8_t* srcData, size_t srcLen,
                int width, int height,
                DxtFormat fmt,
                uint8_t* outRgba, size_t outLen,
                bool use1BitAlpha = true);

} // namespace wepwawet

#endif // WEPPAWAWET_DXT_DECODER_H
