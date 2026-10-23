{ pkgs }:
let
  libraries = with pkgs; [ stdenv.cc.cc.lib glibc zlib libusb1 libxml2_13 ncurses5 ];
in pkgs.writeShellApplication {
  name = "android-sdk-prepare";
  runtimeInputs = with pkgs; [ auto-patchelf patchelf findutils ];
  text = ''
    sdk="''${ANDROID_HOME:-$HOME/Android/Sdk}"
    if [ ! -d "$sdk" ]; then
      echo "Install the reviewed Android SDK in $sdk first." >&2
      exit 1
    fi
    paths=()
    for tools in "$sdk"/build-tools/*; do
      [ -d "$tools" ] || continue
      while IFS= read -r -d $'\0' file; do paths+=("$file"); done \
        < <(find "$tools" -maxdepth 1 -type f -executable -print0)
      [ ! -d "$tools/lib64" ] || paths+=("$tools/lib64")
    done
    for tools in "$sdk"/platform-tools "$sdk"/cmake/*/bin \
      "$sdk"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin \
      "$sdk"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/lib \
      "$sdk"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/python3/bin \
      "$sdk"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/python3/lib; do
      [ ! -d "$tools" ] || paths+=("$tools")
    done
    # Do not patch Android target libraries in NDK sysroots.
    auto-patchelf --no-recurse --paths "''${paths[@]}" \
      --libs ${pkgs.lib.concatMapStringsSep " " (p: "${pkgs.lib.getLib p}/lib") libraries}
  '';
}
