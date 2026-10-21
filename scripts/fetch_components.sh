#!/usr/bin/env bash
# fetch_components.sh — local helper for developers
#
# Downloads the 5 emulation component artifacts from the latest GitHub Actions run
# so you can build the APK locally without running the full CI matrix.
#
# Usage:
#   ./scripts/fetch_components.sh
#   ./scripts/fetch_components.sh v0.1.0-alpha   # use a specific release tag

set -euo pipefail

TAG="${1:-latest}"
DEST="${2:-app/src/main/assets}"
mkdir -p "$DEST"

if [ "$TAG" = "latest" ]; then
    echo "Fetching artifacts from latest CI run on main..."
    # Use gh CLI to download all artifacts from latest run
    if ! command -v gh >/dev/null 2>&1; then
        echo "::error::gh CLI not installed. Install: https://cli.github.com/"
        exit 1
    fi
    gh run download \
        --repo "$(git remote get-url origin | sed 's|.*github.com[:/]||;s|.git$||')" \
        --dir "$DEST" \
        $(gh run list --workflow=build.yml --limit 1 --json databaseId -q '.[0].databaseId')
else
    echo "Fetching release $TAG..."
    if ! command -v gh >/dev/null 2>&1; then
        echo "::error::gh CLI not installed"
        exit 1
    fi
    gh release download "$TAG" \
        --repo "$(git remote get-url origin | sed 's|.*github.com[:/]||;s|.git$||')" \
        --dir "$DEST" \
        --pattern '*.wcp' \
        --pattern '*.tar.zst' \
        --pattern '*.so'
fi

echo "✓ Downloaded artifacts to $DEST/"
ls -lh "$DEST/"
