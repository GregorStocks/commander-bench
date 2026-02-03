#!/bin/bash
# Conductor workspace setup script

set -e

# Ensure shared Maven build cache directory exists
mkdir -p ~/.m2/build-cache

# Ensure shared XMage images directory exists
SHARED_IMAGES=~/.xmage/images
mkdir -p "$SHARED_IMAGES"

# Symlink Mage.Client/plugins/images to shared location
PLUGINS_DIR="$(dirname "$0")/../Mage.Client/plugins"
mkdir -p "$PLUGINS_DIR"
IMAGES_LINK="$PLUGINS_DIR/images"

if [ -L "$IMAGES_LINK" ]; then
    # Already a symlink, we're good
    :
elif [ -d "$IMAGES_LINK" ]; then
    # Existing directory - move contents to shared location, then symlink
    echo "Moving existing images to shared location..."
    cp -rn "$IMAGES_LINK"/* "$SHARED_IMAGES"/ 2>/dev/null || true
    rm -rf "$IMAGES_LINK"
    ln -s "$SHARED_IMAGES" "$IMAGES_LINK"
else
    # No existing directory, just create symlink
    ln -s "$SHARED_IMAGES" "$IMAGES_LINK"
fi

echo "XMage workspace ready."
echo "  Build cache: ~/.m2/build-cache"
echo "  Images: ~/.xmage/images (symlinked from Mage.Client/plugins/images)"
