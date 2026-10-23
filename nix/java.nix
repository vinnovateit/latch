{ pkgs }:
pkgs.temurin-bin-21.overrideAttrs (_: {
  version = "21.0.12.1";
  # The unpacked distribution can be seeded from the identical existing SDK.
  # Nix checks this recursive hash on fresh downloads as well.
  src = pkgs.fetchzip {
    url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz";
    hash = "sha256-1Ooedw9dRUz0ufM6n8hryuhT3rGvlQLGzCkcMhHHr+w=";
  };
})
