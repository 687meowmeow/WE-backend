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
#  - PEP 668: Python venv at /tmp/meson-venv (not --break-system-packages)
#  - DXVK: write custom cross-file with -I path to source-tree Vulkan-Headers
#    (DXVK's own build-win64.txt doesn't include them, and meson's -Dc_args
#    does NOT apply to cross-compile — only the cross-file's c_args does)
#  - VKD3D: needs x86_64-w64-mingw32-widl, provided by mingw-w64-tools apt pkg
#  - Mesa: needs glslangValidator, provided by glslang-tools apt pkg
#  - Wine: mkdir -p $WINEPREFIX before wineserver (it chdirs first)
#  - Box64: ARTIFACTS_DIR made absolute at start
#  - All apt installs: use apt-get download + dpkg-deb -x (no sudo needed)

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

# === Install newer meson than apt ships (apt's 1.3.2 is too old for latest Mesa >=1.4) ===
# Use Python venv to avoid PEP 668 'externally-managed-environment' error
if [ ! -x /tmp/meson-venv/bin/meson ]; then
    echo "→ Creating Python venv + installing newer meson (apt's 1.3.2 too old for Mesa >=1.4)"
    python3 -m venv /tmp/meson-venv
    /tmp/meson-venv/bin/pip install --quiet --upgrade pip
    /tmp/meson-venv/bin/pip install --quiet 'meson>=1.4.0'
fi
export PATH="/tmp/meson-venv/bin:$PATH"
hash -r 2>/dev/null || true

# === Defensive: set safe.directory in case anything still touches /opt/*-src ===
git config --global --add safe.directory '*' 2>/dev/null || true

# === User-space .deb installer (no sudo needed) ===
# apt-get download fetches .deb files to the current dir; dpkg-deb -x extracts
# them to a user-writable location. We add /tmp/user-debs/usr/bin to PATH.
USER_DEBS_DIR=/tmp/user-debs
mkdir -p "$USER_DEBS_DIR"
install_deb_to_user_space() {
    local pkg
    for pkg in "$@"; do
        if [ -f "$USER_DEBS_DIR/.installed-$pkg" ]; then
            continue
        fi
        echo "→ apt-get update + download $pkg"
        # apt-get update is required — the GHCR image's package index may be stale
        # and universe repo may not be enabled (we enable it in the Dockerfile, but
        # in case the image is old, we try to enable it here too)
        apt-get update -qq 2>&1 | tail -3 || true
        # Try to enable universe repo if not already (Ubuntu 24.04 deb822 format)
        sed -i 's/^Components: main restricted$/Components: main restricted universe multiverse/' \
            /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
        apt-get update -qq 2>&1 | tail -3 || true
        # Download the .deb
        if ( cd "$USER_DEBS_DIR" && apt-get download "$pkg" ) >/tmp/"$pkg".log 2>&1; then
            touch "$USER_DEBS_DIR/.installed-$pkg"
        else
            echo "❌ Failed to fetch $pkg"
            cat /tmp/"$pkg".log
            exit 1
        fi
    done
    # Extract all .deb files
    local deb
    for deb in "$USER_DEBS_DIR"/*.deb; do
        [ -f "$deb" ] || continue
        dpkg-deb -x "$deb" "$USER_DEBS_DIR" 2>&1 | tail -3 || true
        rm -f "$deb"
    done
    export PATH="$USER_DEBS_DIR/usr/bin:$PATH"
    hash -r 2>/dev/null || true
}

# Copy /opt/<name>-src to /tmp/wepwawet-src/<name> if not already done.
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

echo "→ meson version: $(meson --version 2>/dev/null || echo 'meson not found')"
echo "→ ninja version: $(ninja --version 2>/dev/null || echo 'ninja not found')"

echo "::group::Building $COMPONENT"
case "$COMPONENT" in
    box64)
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
        patchelf --set-interpreter /data/data/com.wepwawet/files/imagefs/usr/lib/ld-linux-aarch64.so.1 \
            --set-rpath /data/data/com.wepwawet/files/imagefs/usr/lib \
            "$BUILD_DIR/box64" 2>/dev/null || true
        cat > "$BUILD_DIR/profile.json" <<'EOF'
{ "name": "Box64 (WepWawet)", "version": "1.0", "args": ["-loadbin", "/data/data/com.wepwawet/files/imagefs/usr/bin/box64"] }
EOF
        tar --zstd -cf "$ARTIFACTS_DIR/box64-arm64-winlator.wcp" -C "$BUILD_DIR" box64 profile.json
        ;;

    wine)
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
        # IMPORTANT: wineserver chdirs to $WINEPREFIX before wineboot creates it
        WINEPREFIX_DIR=/tmp/wepwawet-prefix
        mkdir -p "$WINEPREFIX_DIR"
        WINEPREFIX="$WINEPREFIX_DIR" WINESERVER="$BUILD_DIR/server/wineserver" \
            "$BUILD_DIR/wineboot" --init 2>&1 | tail -20 || true
        mkdir -p "$WINEPREFIX_DIR/drive_c/Program Files (x86)/Steam"
        tar --zstd -cf "$ARTIFACTS_DIR/wine-10.10-arm64-prefix.tar.zst" \
            -C /tmp wepwawet-prefix
        ;;

    mesa-turnip)
        copy_src mesa
        cd "$SRC_ROOT/mesa"
        git remote add whitebelyash https://gitlab.freedesktop.org/whitebelyash/mesa.git 2>/dev/null || true
        git fetch whitebelyash 2>/dev/null || true
        git checkout whitebelyash/tu8 2>/dev/null || git checkout whitebelyash/main 2>/dev/null || true
        # Mesa needs glslangValidator (provides by apt pkg 'glslang-tools')
        install_deb_to_user_space glslang-tools
        echo "→ glslangValidator: $(which glslangValidator 2>/dev/null || echo 'NOT FOUND')"
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
        cp "$BUILD_DIR/src/freedreno/vulkan/libvulkan_freedreno.so" "$ARTIFACTS_DIR/libvulkan_turnip.so" 2>/dev/null || {
            find "$BUILD_DIR" -name 'libvulkan_freedreno.so' -exec cp {} "$ARTIFACTS_DIR/libvulkan_turnip.so" \;
        }
        ;;

    dxvk)
        copy_src dxvk
        cd "$SRC_ROOT/dxvk"
        git fetch --tags --depth 1 origin v2.7.1 2>/dev/null || true
        git checkout v2.7.1 2>/dev/null || true
        # DXVK expects Vulkan-Headers at <src>/include/vulkan/ (per linuxfromscratch.org)
        if [ ! -d "$SRC_ROOT/dxvk/include/vulkan" ] || [ ! -f "$SRC_ROOT/dxvk/include/vulkan/vulkan.h" ]; then
            echo "→ Cloning KhronosGroup/Vulkan-Headers and copying into include/vulkan/"
            rm -rf "$SRC_ROOT/dxvk/include/vulkan" 2>/dev/null || true
            git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git /tmp/vulkan-headers-tmp 2>/dev/null || true
            cp -r /tmp/vulkan-headers-tmp/include/vulkan "$SRC_ROOT/dxvk/include/"
            rm -rf /tmp/vulkan-headers-tmp
        fi
        # Write our OWN cross-file (DXVK's build-win64.txt doesn't add -I for Vulkan-Headers)
        # Per meson docs: -Dc_args does NOT apply to cross-compile; only cross-file c_args do
        cat > /tmp/dxvk-cross.txt <<EOF
[binaries]
c = 'x86_64-w64-mingw32-gcc'
cpp = 'x86_64-w64-mingw32-g++'
ar = 'x86_64-w64-mingw32-ar'
strip = 'x86_64-w64-mingw32-strip'
windres = 'x86_64-w64-mingw32-windres'
dlltool = 'x86_64-w64-mingw32-dlltool'

[built-in options]
c_args = ['-I${SRC_ROOT}/dxvk/include', '-I/usr/include']
cpp_args = ['-I${SRC_ROOT}/dxvk/include', '-I/usr/include']
c_link_args = ['-L/usr/lib']
cpp_link_args = ['-L/usr/lib']

[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
        BUILD_DIR="$BUILD_ROOT/dxvk"
        mkdir -p "$BUILD_DIR" && cd "$BUILD_DIR"
        meson setup "$BUILD_DIR" "$SRC_ROOT/dxvk" \
            --cross-file /tmp/dxvk-cross.txt \
            --buildtype release
        ninja -C "$BUILD_DIR"
        mkdir -p /tmp/dxvk-dist/x64
        find "$BUILD_DIR" -name 'd3d8.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d9.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d10core.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'd3d11.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        find "$BUILD_DIR" -name 'dxgi.dll' -exec cp {} /tmp/dxvk-dist/x64/ \; 2>/dev/null || true
        tar --zstd -cf "$ARTIFACTS_DIR/dxvk-2.7.1.tar.zst" -C /tmp dxvk-dist
        ;;

    vkd3d)
        copy_src vkd3d
        cd "$SRC_ROOT/vkd3d"
        git fetch --tags --depth 1 origin v2.12 2>/dev/null || true
        git checkout v2.12 2>/dev/null || true
        # VKD3D-Proton .gitmodules declares khronos/Vulkan-Headers and other submodules
        git submodule update --init --recursive 2>&1 | tail -10 || {
            echo "→ submodule update failed, falling back to direct clone"
            if [ ! -d "$SRC_ROOT/vkd3d/khronos/Vulkan-Headers" ]; then
                git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git \
                    "$SRC_ROOT/vkd3d/khronos/Vulkan-Headers" 2>/dev/null || true
            fi
        }
        # VKD3D-Proton needs x86_64-w64-mingw32-widl (provided by apt pkg 'mingw-w64-tools')
        install_deb_to_user_space mingw-w64-tools
        echo "→ x86_64-w64-mingw32-widl: $(which x86_64-w64-mingw32-widl 2>/dev/null || echo 'NOT FOUND')"
        # VKD3D also needs glslang for shader compilation
        install_deb_to_user_space glslang-tools
        BUILD_DIR="$BUILD_ROOT/vkd3d"
        meson setup "$BUILD_DIR" "$SRC_ROOT/vkd3d" \
            --cross-file "$SRC_ROOT/vkd3d/build-win64.txt" \
            --buildtype release \
            --strip
        ninja -C "$BUILD_DIR"
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
