{
  description = "Android build environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }: {
    devShells.x86_64-linux.default =
      let
        pkgs = import nixpkgs {
          system = "x86_64-linux";
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidSdk = (pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" "34.0.0" "29.0.3" ];
          platformVersions = [ "34" "29" ];
          includeEmulator = true;
          includeNDK = false;
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "x86_64" ];
          includeSources = false;
        }).androidsdk;
      in
      pkgs.mkShell {
        packages = [ pkgs.jdk21 androidSdk pkgs.gradle pkgs.fdroidserver ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk21}";
        GRADLE_OPTS = "-Dorg.gradle.daemon=false";

        shellHook = ''
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
          echo "Android dev env ready  (JDK 21 + SDK 34, API 29 emulator image)"
        '';
      };
  };
}
