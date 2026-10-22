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
#   dxvk          -> artifacts/dxvk-1.10.3.tar.zst
#   vkd3d         -> artifacts/vkd3d-2.12.tar.zst

set -euo pipefail

COMPONENT="${1:?component name required}"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-artifacts}"
mkdir -p "$ARTIFACTS_DIR"

echo "::group::Building $COMPONENT"
case "$COMPONENT" in
    box64)
        # Build Box64 with WINLATOR_GLIBC=ON (required for Steam under Winlator)
        cd /opt/box64-src
        git pull --rebase 2>/dev/null || true
        mkdir -p build && cd build
        cmake .. \
            -DARM_DYNAREC=ON \
            -DWINLATOR_GLIBC=ON \
            -DBOX32=ON \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
            -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
            -DCMAKE_C_FLAGS="-O3 -march=armv8.2-a+simd+crypto -fPIC" \
            -DCMAKE_CXX_FLAGS="-O3 -march=armv8.2-a+simd+crypto -fPIC"
        make -j"$(nproc)"
        # Patchelf to use the Winlator rootfs ld-linux-aarch64.so.1
        patchelf --set-interpreter /data/data/com.wepwawet/files/imagefs/usr/lib/ld-linux-aarch64.so.1 \
            --set-rpath /data/data/com.wepwawet/files/imagefs/usr/lib \
            ./box64
        # Package as a Winlator installable component (.wcp)
        cat > profile.json <<'EOF'
{ "name": "Box64 (WepWawet)", "version": "1.0", "args": ["-loadbin", "/data/data/com.wepwawet/files/imagefs/usr/bin/box64"] }
EOF
        tar --zstd -cf "$ARTIFACTS_DIR/box64-arm64-winlator.wcp" box64 profile.json
        ;;

    wine)
        # Build Wine 10.10 for arm64 + create a pre-configured prefix
        cd /opt/wine-src
        git fetch --tags --depth 1 origin wine-10.10
        git checkout wine-10.10
        mkdir -p build-arm64 && cd build-arm64
        ../configure \
            --host=aarch64-linux-gnu \
            --enable-win64 \
            --without-curses \
            --without-pcap \
            --without-usb \
            --without-coreaudio \
            --without-alsa \
            --without-oss \
            --without-opencl
        make -j"$(nproc)"
        # Create a minimal prefix that contains just the registry hives + drive_c structure
        # (Steam + WE will be downloaded on first run by the WineProcessService)
        ./server/wineserver -p 0
        WINEPREFIX=/tmp/wepwawet-prefix ./wineboot --init 2>/dev/null || true
        # Pre-create the Steam install directory
        mkdir -p /tmp/wepwawet-prefix/drive_c/Program\ Files\ \(x86\)/Steam
        tar --zstd -cf "$ARTIFACTS_DIR/wine-10.10-arm64-prefix.tar.zst" \
            -C /tmp wepwawet-prefix
        ;;

    mesa-turnip)
        # Build Mesa with the Turnip driver (Adreno 7xx Vulkan 1.3)
        cd /opt/mesa-src
        git pull --rebase 2>/dev/null || true
        # Use the whitebelyash/mesa Turnip fork branch
        git remote add whitebelyash https://gitlab.freedesktop.org/whitebelyash/mesa.git 2>/dev/null || true
        git fetch whitebelyash
        git checkout whitebelyash/tu8 2>/dev/null || git checkout whitebelyash/main
        # Mesa's Meson build
        meson setup build-arm64 \
            --cross-file=/opt/mesa-cross-file.txt \
            -Dvulkan-drivers=freedreno \
            -Dgallium-drivers= \
            -Dgles1=disabled \
            -Dgles2=disabled \
            -Dgbm=disabled \
            -Degl=disabled \
            -Dglx=disabled \
            -Dplatforms= \
            -Dshared-glapi=disabled
        meson compile -C build-arm64
        # Extract just the Turnip vulkan driver
        cp build-arm64/src/freedreno/vulkan/libvulkan_freedreno.so "$ARTIFACTS_DIR/libvulkan_turnip.so"
        ;;

    dxvk)
        # Build DXVK 1.10.3 for x86_64-w64-mingw32 (Windows DLL, runs under Wine)
        cd /opt/dxvk-src
        git checkout v1.10.3
        # DXVK ships pre-built release artifacts — we can fetch them instead of building
        # but for reproducibility we build from source
        ./package-release.sh master /tmp/dxvk-build --no-package
        tar --zstd -cf "$ARTIFACTS_DIR/dxvk-1.10.3.tar.zst" -C /tmp/dxvk-build dxvk-1.10.3
        ;;

    vkd3d)
        # Build VKD3D 2.12 for x86_64-w64-mingw32
        cd /opt/vkd3d-src
        git checkout v2.12
        ./autogen.sh
        mkdir -p build-mingw && cd build-mingw
        ../configure \
            --host=x86_64-w64-mingw32 \
            --target=x86_64-w64-mingw32 \
            CFLAGS="-O3 -fPIC"
        make -j"$(nproc)"
        # Package the DLLs
        mkdir -p /tmp/vkd3d-dist
        cp vulkan-1/vkd3d-proton/{d3d12.dll,vkd3d.dll,vkd3d-shaders} /tmp/vkd3d-dist/ 2>/dev/null || true
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
