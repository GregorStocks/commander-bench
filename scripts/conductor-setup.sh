#!/bin/bash
# Conductor workspace setup script

set -e

# Ensure shared Maven build cache directory exists
mkdir -p ~/.m2/build-cache

echo "XMage workspace ready. Build cache at ~/.m2/build-cache"
