#!/usr/bin/env sh
set -e
mkdir -p bin
cp LatchSetup bin/LatchSetup
chmod +x bin/LatchSetup
echo "Linux bootstrapper built: bin/LatchSetup"
