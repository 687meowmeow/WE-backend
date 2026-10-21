# WepWawet builder image
#
# This image contains EVERYTHING needed to build all 5 emulation components AND the APK:
#   - Android NDK 26, SDK 35 (with build-tools, platforms)
#   - Mesa build deps (libdrm, libx11, libxcb, wayland, etc.)
#   - Wine build deps (flex, bison, libfreetype, libvulkan, libxcomposite, libxinerama, ...)
#   - DXVK build deps (Meson, MinGW cross for x86_64-w64-mingw32)
#   - VKD3D build deps (similar)
#   - Box64 cross-build env (aarch64 GCC cross-compiler + the right libc)
#   - ccache
#   - zstd, xz-utils, tar, patchelf
#
# Build time: ~90 minutes cold (large image)
# Image size: ~10 GB
# Layer cache strategy: each RUN is a separate layer so changes don't invalidate unrelated layers.

FROM ubuntu:24.04 AS base

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=UTC

# Enable universe + multiverse repositories (needed for glslang-tools, mingw-w64-tools)
# Ubuntu 24.04 uses deb822 format in /etc/apt/sources.list.d/ubuntu.sources
RUN sed -i 's/^Components: main restricted$/Components: main restricted universe multiverse/' \
    /etc/apt/sources.list.d/ubuntu.sources || true

# Install the foundational build tools (apt packages first; they change rarely)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    ca-certificates \
    curl \
    wget \
    git \
    pkg-config \
    python3 python3-pip python3-venv \
    ccache \
    zstd xz-utils tar gzip \
    patchelf \
    flex bison \
    ninja-build \
    cmake \
    meson \
    nasm yasm \
    autoconf automake libtool \
    mingw-w64 mingw-w64-tools \
    gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
    gcc-x86-64-linux-gnu g++-x86-64-linux-gnu \
    glslang-tools \
    libfreetype-dev \
    libx11-dev libxext-dev libxcb1-dev libxrandr-dev \
    libxcomposite-dev libxinerama-dev libxrender-dev libxcursor-dev \
    libxi-dev libxtst-dev libxxf86vm-dev libxv-dev \
    libgl1-mesa-dev libgles2-mesa-dev libegl1-mesa-dev \
    libvulkan-dev \
    libwayland-dev wayland-protocols \
    libdrm-dev \
    libpulse-dev libasound2-dev \
    libdbus-1-dev \
    libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
    libmpv-dev \
    liblcms2-dev \
    libpng-dev libjpeg-dev libwebp-dev \
    libssl-dev \
    zlib1g-dev \
    libxml2-dev libxslt1-dev \
    libcups2-dev \
    libdbus-1-dev \
    libfontconfig1-dev \
    libgnutls28-dev \
    libgsm1-dev \
    libkrb5-dev \
    libldap2-dev \
    libnettle-dev \
    libopencl-clang-dev \
    libpcsclite-dev \
    libpng-dev \
    libv4l-dev \
    libva-dev \
    libusb-1.0-0-dev \
    libgphoto2-dev \
    libudev-dev \
    libsane-dev \
    libsnappy-dev \
    libvulkan-dev \
    && rm -rf /var/lib/apt/lists/*

# Install JDK 17 (needed by Android Gradle plugin)
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# ============================================================================
# Android SDK + NDK
# ============================================================================
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.1.10911917
ENV ANDROID_SDK_ROOT=$ANDROID_HOME
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    cd $ANDROID_HOME/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools latest && \
    rm cmdline-tools.zip && \
    yes | sdkmanager --licenses >/dev/null 2>&1 && \
    sdkmanager \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        "ndk;26.1.10911917" \
        "cmake;3.22.1" 2>&1 | tail -5

# ============================================================================
# Mesa Turnip build deps (we need libdrm + the right ARM cross-compiler)
# ============================================================================
ENV MESA_REPO=/opt/mesa-src
RUN git clone --depth 1 https://gitlab.freedesktop.org/mesa/mesa.git $MESA_REPO

# ============================================================================
# Wine build deps (already in apt above; here we clone Wine for later builds)
# ============================================================================
ENV WINE_REPO=/opt/wine-src
RUN git clone --depth 1 -b wine-10.10 https://gitlab.winehq.org/wine/wine.git $WINE_REPO

# ============================================================================
# Box64 cross-build env
# ============================================================================
ENV BOX64_REPO=/opt/box64-src
RUN git clone --depth 1 https://github.com/ptitSeb/box64.git $BOX64_REPO

# ============================================================================
# DXVK / VKD3D
# ============================================================================
ENV DXVK_REPO=/opt/dxvk-src
ENV VKD3D_REPO=/opt/vkd3d-src
RUN git clone --depth 1 -b v1.10.3 https://github.com/doitsujin/dxvk.git $DXVK_REPO && \
    git clone --depth 1 -b v2.12 https://gitlab.winehq.org/wine/vkd3d.git $VKD3D_REPO

# ============================================================================
# Winlator rootfs (Ubuntu Focal ARM64 base, used as the Wine prefix's C: drive)
# ============================================================================
ENV ROOTFS_BASE=/opt/rootfs-base
RUN mkdir -p $ROOTFS_BASE && \
    cd /tmp && \
    curl -fsSL http://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.6-base-arm64.tar.gz -o rootfs.tar.gz && \
    tar xzf rootfs.tar.gz -C $ROOTFS_BASE && \
    rm rootfs.tar.gz

# ============================================================================
# Gradle (specific version pinned for reproducibility)
# ============================================================================
ENV GRADLE_VERSION=8.10.2
RUN curl -fsSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-${GRADLE_VERSION} /opt/gradle && \
    rm /tmp/gradle.zip && \
    ln -sf /opt/gradle/bin/gradle /usr/local/bin/gradle

# Final touch — switch to non-root for safety
RUN useradd -m -s /bin/bash builder
USER builder
WORKDIR /home/builder

# Default command — `build.yml` overrides this with the build script
CMD ["bash"]
