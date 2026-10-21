#!/usr/bin/env bash
# build_component.sh <component_name>
#
# Builds ONE of the 5 emulation components and writes the artifact to artifacts/<file>.
# Used by .github/workflows/build.yml as part of the matrix build.
#
# Components:
#   box64         -> artifacts/box64-arm64-winlator.wcp
#   wine          -> artifacts/wine-10.10-arm64-prefix.tar.zst
#   mesa-turnip   -> artifacts/libvulkan_turnip.so
#   dxvk          -> artifacts/dxvk-2.7.1.tar.zst
#   vkd3d         -> artifacts/vkd3d-2.12.tar.zst
#
# All fixes are research-backed:
#  - PEP 668 (pip3 --user fails): use a Python venv at /tmp/meson-venv
#  - DXVK expects Vulkan-Headers at <src>/include/vulkan/ (per linuxfromscratch.org)
#  - VKD3D-Proton declares `khronos/Vulkan-Headers` submodule in .gitmodules
#  - Wine: must mkdir -p $WINEPREFIX before invoking wineserver (it chdirs first)
#  - ARTIFACTS_DIR made absolute at start so we can cd into build dirs freely

set -euo pipefail

COMPONENT="${1:?component name required}"

# === ARTIFACTS_DIR: make it absolute (we'll cd into build dirs later) ===
ARTIFACTS_DIR="${ARTIFACTS_DIR:-artifacts}"
if [[ "$ARTIFACTS_DIR" != /* ]]; then
    ARTIFACTS_DIR="$(pwd)/$ARTIFACTS_DIR"
fi
mkdir -p "$ARTIFACTS_DIR"

# === Writable source + build roots ===
SRC_ROOT=/tmp/wepwawet-src
BUILD_ROOT=/tmp/wepwawet-builds
mkdir -p "$SRC_ROOT" "$BUILD_ROOT"

# === Install newer meson than apt ships (apt's 1.3.2 is too old for latest Mesa which needs >=1.4) ===
# Use a Python venv to avoid PEP 668 'externally-managed-environment' error
# (Ubuntu 24.04 blocks `pip3 install --user` without --break-system-packages)
if [ ! -x /tmp/meson-venv/bin/meson ]; then
    echo "→ Creating Python venv + installing newer meson (apt's 1.3.2 too old for Mesa >=1.4)"
    python3 -m venv /tmp/meson-venv
    /tmp/meson-venv/bin/pip install --quiet --upgrade pip
    /tmp/meson-venv/bin/pip install --quiet 'meson>=1.4.0'
fi
export PATH="/tmp/meson-venv/bin:$PATH"
hash -r 2>/dev/null || true
echo "→ meson version: $(meson --version 2>/dev/null || echo 'meson not found')"
echo "→ ninja version: $(ninja --version 2>/dev/null || echo 'ninja not found')"

# === Defensive: set safe.directory in case anything still touches /opt/*-src ===
git config --global --add safe.directory '*' 2>/dev/null || true

# Copy /opt/<name>-src to /tmp/wepwawet-src/<name> if not already done.
# Uses `cp -r` (no -a) so files become builder-owned (writable).
copy_src() {
    local name="$1"
    local src="/opt/${name}-src"
    local dst="$SRC_ROOT/$name"
    if [ ! -d "$dst" ]; then
        echo "→ Copying $src -> $dst (cp -r gives us builder-owned files)"
        cp -r "$src" "$dst"
        chown -R "$(id -u):$(id -g)" "$dst" 2>/dev/null || true
    else
        echo "→ $dst already exists, reusing"
    fi
}

echo "::group::Building $COMPONENT"
case "$COMPONENT" in
    box64)
        # Build Box64 with WINLATOR_GLIBC=ON (required for Steam under Winlator)
        copy_src box64
        cd "$SRC_ROOT/box64"
        git pull --rebase 2>/dev/null || true
        BUILD_DIR="$BUILD_ROOT/box64"
        mkdir -p "$BUILD_DIR" && cd "$BUILD_DIR"
        cmake "$SRC_ROOT/box64" \
            -DARM_DYNAREC=ON \
            -DWINLATOR_GLIBC=ON \
            -DBOX32=ON \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
            -DCMAKE_C_FLAGS="-O3 -march=armv8.2-a+simd+crypto -fPIC"
        make -j"$(nproc)"
        # Patchelf to use the Winlator rootfs ld-linux-aarch64.so.1 (best-effort)
        patchelf --set-interpreter /data/data/com.wepwawet/files/imagefs/usr/lib/ld-linux-aarch64.so.1 \
            --set-rpath /data/data/com.wepwawet/files/imagefs/usr/lib \
            "$BUILD_DIR/box64" 2>/dev/null || true
        # Write profile.json next to box64 (both already in $BUILD_DIR — no cp needed)
        cat > "$BUILD_DIR/profile.json" <<'EOF'
{ "name": "Box64 (WepWawet)", "version": "1.0", "args": ["-loadbin", "/data/data/com.wepwawet/files/imagefs/usr/bin/box64"] }
EOF
        # Tar from $BUILD_DIR (ARTIFACTS_DIR is absolute now — works from anywhere)
        tar --zstd -cf "$ARTIFACTS_DIR/box64-arm64-winlator.wcp" -C "$BUILD_DIR" box64 profile.json
        ;;

    wine)
        # Build Wine 10.10 for arm64 + create a pre-configured prefix
        copy_src wine
        cd "$SRC_ROOT/wine"
        git fetch --tags --depth 1 origin wine-10.10 2>/dev/null || true
        git checkout wine-10.10 2>/dev/null || true
        BUILD_DIR="$BUILD_ROOT/wine"
        mkdir -p "$BUILD_DIR" && cd "$BUILD_DIR"
        "$SRC_ROOT/wine/configure" \
            --enable-win64 \
            --without-curses \
            --without-pcap \
            --without-usb \
            --without-coreaudio \
            --without-alsa \
            --without-oss \
            --without-opencl
        make -j"$(nproc)"
        # === Create a minimal Wine prefix ===
        # IMPORTANT: wineserver chdirs to $WINEPREFIX before wineboot creates it,
        # so we MUST mkdir -p the prefix directory first.
        WINEPREFIX_DIR=/tmp/wepwawet-prefix
        mkdir -p "$WINEPREFIX_DIR"
        # Use the just-built wineboot --init to create the prefix structure
        WINEPREFIX="$WINEPREFIX_DIR" WINESERVER="$BUILD_DIR/server/wineserver" \
            "$BUILD_DIR/wineboot" --init 2>&1 | tail -20 || true
        # Pre-create the Steam install directory inside the prefix
        mkdir -p "$WINEPREFIX_DIR/drive_c/Program Files (x86)/Steam"
        # Tar up the prefix
        tar --zstd -cf "$ARTIFACTS_DIR/wine-10.10-arm64-prefix.tar.zst" \
            -C /tmp wepwawet-prefix
        ;;

    mesa-turnip)
        # Build Mesa with the Turnip driver (Adreno 7xx Vulkan 1.3)
        copy_src mesa
        cd "$SRC_ROOT/mesa"
        git remote add whitebelyash https://gitlab.freedesktop.org/whitebelyash/mesa.git 2>/dev/null || true
        git fetch whitebelyash 2>/dev/null || true
        git checkout whitebelyash/tu8 2>/dev/null || git checkout whitebelyash/main 2>/dev/null || true
        # Native-file for native arm64 build (we're on arm64, no cross-compile)
        cat > /tmp/mesa-native.txt <<'EOF'
[binaries]
c = 'cc'
cpp = 'c++'
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
        BUILD_DIR="$BUILD_ROOT/mesa-turnip"
        meson setup "$BUILD_DIR" "$SRC_ROOT/mesa" \
            --native-file=/tmp/mesa-native.txt \
            -Dvulkan-drivers=freedreno \
            -Dgallium-drivers= \
            -Dgles1=disabled \
            -Dgles2=disabled \
            -Dgbm=disabled \
            -Degl=disabled \
            -Dglx=disabled \
            -Dplatforms= \
            -Dshared-glapi=disabled
        meson compile -C "$BUILD_DIR"
        # Extract just the Turnip vulkan driver
        cp "$BUILD_DIR/src/freedreno/vulkan/libvulkan_freedreno.so" "$ARTIFACTS_DIR/libvulkan_turnip.so" 2>/dev/null || {
            find "$BUILD_DIR" -name 'libvulkan_freedreno.so' -exec cp {} "$ARTIFACTS_DIR/libvulkan_turnip.so" \;
        }
        ;;

    dxvk)
        # Build DXVK v2.7.1 for x86_64-w64-mingw32 (Windows DLL, runs under Wine)
        copy_src dxvk
        cd "$SRC_ROOT/dxvk"
        git fetch --tags --depth 1 origin v2.7.1 2>/dev/null || true
        git checkout v2.7.1 2>/dev/null || true
        # DXVK expects Vulkan-Headers at <src>/include/vulkan/ (per linuxfromscratch.org):
        #   mv -T Vulkan-Headers-* dxvk-2.7.1/include/vulkan
        # We clone KhronosGroup/Vulkan-Headers and copy its include/vulkan/ contents.
        if [ ! -d "$SRC_ROOT/dxvk/include/vulkan" ] || [ ! -f "$SRC_ROOT/dxvk/include/vulkan/vulkan.h" ]; then
            echo "→ Cloning KhronosGroup/Vulkan-Headers and copying into include/vulkan/"
            rm -rf "$SRC_ROOT/dxvk/include/vulkan" 2>/dev/null || true
            git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git /tmp/vulkan-headers-tmp 2>/dev/null || true
            cp -r /tmp/vulkan-headers-tmp/include/vulkan "$SRC_ROOT/dxvk/include/"
            rm -rf /tmp/vulkan-headers-tmp
        fi
        # Use DXVK's own cross-file (shipped at build-win64.txt)
        # Build directly via meson instead of package-release.sh for cleaner error reporting
        BUILD_DIR="$BUILD_ROOT/dxvk"
        mkdir -p "$BUILD_DIR" && cd "$BUILD_DIR"
        meson setup "$BUILD_DIR" "$SRC_ROOT/dxvk" \
            --cross-file "$SRC_ROOT/dxvk/build-win64.txt" \
            --buildtype release
        ninja -C "$BUILD_DIR"
        # Collect the DLLs
        mkdir -p /tmp/dxvk-dist/x64
        find "$BUILD_DIR" -name 'd3d8.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d9.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d10core.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d11.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'dxgi.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        tar --zstd -cf "$ARTIFACTS_DIR/dxvk-2.7.1.tar.zst" -C /tmp dxvk-dist
        ;;

    vkd3d)
        # Build VKD3D-Proton 2.12 for x86_64-w64-mingw32
        copy_src vkd3d
        cd "$SRC_ROOT/vkd3d"
        git fetch --tags --depth 1 origin v2.12 2>/dev/null || true
        git checkout v2.12 2>/dev/null || true
        # VKD3D-Proton .gitmodules declares khronos/Vulkan-Headers and other submodules
        # The recommended clone is `git clone --recursive`, but we did --depth 1.
        # Run submodule update to fetch them properly.
        git submodule update --init --recursive 2>&1 | tail -10 || {
            # Fallback: clone Vulkan-Headers directly into the expected path
            echo "→ submodule update failed, falling back to direct clone"
            if [ ! -d "$SRC_ROOT/vkd3d/khronos/Vulkan-Headers" ]; then
                git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git \
                    "$SRC_ROOT/vkd3d/khronos/Vulkan-Headers" 2>/dev/null || true
            fi
        }
        # VKD3D-Proton ships its own build-win64.txt cross-file
        BUILD_DIR="$BUILD_ROOT/vkd3d"
        meson setup "$BUILD_DIR" "$SRC_ROOT/vkd3d" \
            --cross-file "$SRC_ROOT/vkd3d/build-win64.txt" \
            --buildtype release \
            --strip
        ninja -C "$BUILD_DIR"
        # Package the DLLs (proton-style layout: x64/ contains d3d12.dll etc.)
        mkdir -p /tmp/vkd3d-dist/x64
        find "$BUILD_DIR" -name 'd3d12.dll' -exec cp {} /tmp/vkd3d-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'vkd3d.dll' -exec cp {} /tmp/vkd3d-dist/x64/ \; 2>/dev/null || true
        tar --zstd -cf "$ARTIFACTS_DIR/vkd3d-2.12.tar.zst" -C /tmp vkd3d-dist
        ;;

    *)
        echo "::error::Unknown component: $COMPONENT"
        echo "Valid: box64, wine, mesa-turnip, dxvk, vkd3d"
        exit 1
        ;;
esac

echo "::endgroup::"
echo "✓ Built $COMPONENT -> $ARTIFACTS_DIR/"
ls -lh "$ARTIFACTS_DIR/"
