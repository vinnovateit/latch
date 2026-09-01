#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <version> <linux-tar.gz> <windows.zip> <output-dir>" >&2
    exit 2
fi

version=$1
linux_archive=$2
windows_archive=$3
output_dir=$4

if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid version: $version" >&2
    exit 2
fi

expected_linux="latch-cli-${version}-linux-x64.tar.gz"
expected_windows="latch-cli-${version}-windows-x64.zip"

if [[ $(basename "$linux_archive") != "$expected_linux" ]]; then
    echo "Expected Linux artifact named $expected_linux" >&2
    exit 2
fi
if [[ $(basename "$windows_archive") != "$expected_windows" ]]; then
    echo "Expected Windows artifact named $expected_windows" >&2
    exit 2
fi
if [[ ! -f $linux_archive || ! -f $windows_archive ]]; then
    echo "Both release artifacts must exist" >&2
    exit 2
fi

linux_sha=$(sha256sum "$linux_archive" | awk '{print $1}')
windows_sha=$(sha256sum "$windows_archive" | awk '{print toupper($1)}')
aur_dir="$output_dir/aur"
winget_dir="$output_dir/winget/VinnovateIT.LatchCLI/$version"
mkdir -p "$aur_dir" "$winget_dir"

cat > "$aur_dir/PKGBUILD" <<EOF
# Maintainer: VinnovateIT
pkgname=latch-cli-bin
pkgver=$version
pkgrel=1
pkgdesc='Automatic VIT hostel Wi-Fi login from the terminal'
arch=('x86_64')
url='https://github.com/vinnovateit/latch'
license=('MIT')
depends=('networkmanager')
provides=('latch-cli')
conflicts=('latch-cli')
options=('!strip')
source=("latch-cli-\${pkgver}-linux-x64.tar.gz::https://github.com/vinnovateit/latch/releases/download/v\${pkgver}/latch-cli-\${pkgver}-linux-x64.tar.gz")
sha256sums=('$linux_sha')

package() {
    install -d "\$pkgdir/opt/latch-cli" "\$pkgdir/usr/bin"
    cp -a "\$srcdir/latch-cli-\${pkgver}-linux-x64/." "\$pkgdir/opt/latch-cli/"
    ln -s /opt/latch-cli/bin/latch-cli "\$pkgdir/usr/bin/latch-cli"
}
EOF

cat > "$aur_dir/.SRCINFO" <<EOF
pkgbase = latch-cli-bin
	pkgdesc = Automatic VIT hostel Wi-Fi login from the terminal
	pkgver = $version
	pkgrel = 1
	url = https://github.com/vinnovateit/latch
	arch = x86_64
	license = MIT
	depends = networkmanager
	provides = latch-cli
	conflicts = latch-cli
	options = !strip
	source = latch-cli-$version-linux-x64.tar.gz::https://github.com/vinnovateit/latch/releases/download/v$version/latch-cli-$version-linux-x64.tar.gz
	sha256sums = $linux_sha

pkgname = latch-cli-bin
EOF

cat > "$winget_dir/VinnovateIT.LatchCLI.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.version.1.10.0.schema.json
PackageIdentifier: VinnovateIT.LatchCLI
PackageVersion: $version
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.10.0
EOF

cat > "$winget_dir/VinnovateIT.LatchCLI.installer.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.installer.1.10.0.schema.json
PackageIdentifier: VinnovateIT.LatchCLI
PackageVersion: $version
InstallerType: zip
NestedInstallerType: portable
Commands:
  - latch-cli
UpgradeBehavior: uninstallPrevious
Installers:
  - Architecture: x64
    NestedInstallerFiles:
      - RelativeFilePath: latch-cli-$version-windows-x64\\latch-cli.exe
        PortableCommandAlias: latch-cli
    InstallerUrl: https://github.com/vinnovateit/latch/releases/download/v$version/latch-cli-$version-windows-x64.zip
    InstallerSha256: $windows_sha
ManifestType: installer
ManifestVersion: 1.10.0
EOF

cat > "$winget_dir/VinnovateIT.LatchCLI.locale.en-US.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.defaultLocale.1.10.0.schema.json
PackageIdentifier: VinnovateIT.LatchCLI
PackageVersion: $version
PackageLocale: en-US
Publisher: VinnovateIT
PublisherUrl: https://vinnovateit.com
PublisherSupportUrl: https://github.com/vinnovateit/latch/issues
PackageName: Latch CLI
PackageUrl: https://github.com/vinnovateit/latch
License: MIT
ShortDescription: Automatic VIT hostel Wi-Fi login from the terminal
Description: Latch CLI detects VIT hostel Wi-Fi networks, stores credentials securely, logs in automatically, and can share one active engine with the Latch Desktop app.
Tags:
  - cli
  - network
  - vit
  - wifi
ManifestType: defaultLocale
ManifestVersion: 1.10.0
EOF

echo "Generated AUR and winget metadata for v$version in $output_dir"
