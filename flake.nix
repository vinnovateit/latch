{
  description = "Project development tools for NixOS; application dependencies retain their own lockfiles";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/26f4107e47e3ad0ffd0db220469ba83b6f6d96e8";
  outputs = { self, nixpkgs, ... }@inputs:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system;  };
      android = import ./nix/android.nix { inherit pkgs; };
      java = import ./nix/java.nix { inherit pkgs; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [ android java pkg-config libsecret networkmanager jq ];
        shellHook = ''
          export GRADLE_USER_HOME="''${XDG_CACHE_HOME:-$HOME/.cache}/vinnovateit/gradle"
          export ANDROID_HOME="$HOME/Android/Sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export JAVA_TOOL_OPTIONS="''${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true -Djdk.tls.client.protocols=TLSv1.2"
          export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.daemon=false -Dorg.gradle.workers.max=2 -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/37.0.0/aapt2"
          export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
        '';
        JAVA_HOME = "${java}";
        LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (with pkgs; [
          stdenv.cc.cc.lib zlib libsecret glib gtk3 fontconfig
          libGL libx11 libxext libxrender libxtst libxi
        ]);
      };
    };
}
