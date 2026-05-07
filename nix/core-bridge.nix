# Build a per-classifier core-bridge JAR (the cdylib wrapped in a JAR with
# native/<classifier>/<libname> layout). Resolves classifier → cross stdenv,
# vendors deps via crane, and produces both the cdylib derivation and the
# Maven-shaped classifier JAR.
{
  pkgs,
  crane,
  sdk-core,
}:

let
  inherit (pkgs) lib;

  # Classifier → target triple, library filename, and a function returning the
  # crossPkgs (or null when the host can build the target natively).
  #
  # Keep the classifier set in sync with `flake.nix` (`hostClassifiers`) and
  # `buildSrc/src/main/kotlin/CoreBridgeNative.kt` (`allClassifiers`).
  classifierConfigs = {
    "linux-x86_64-gnu" = {
      target = "x86_64-unknown-linux-gnu";
      libname = "libtemporalio_sdk_core_c_bridge.so";
      pickCross = p: if p.stdenv.hostPlatform.system == "x86_64-linux" then null else p.pkgsCross.gnu64;
    };
    "linux-aarch64-gnu" = {
      target = "aarch64-unknown-linux-gnu";
      libname = "libtemporalio_sdk_core_c_bridge.so";
      pickCross =
        p:
        if p.stdenv.hostPlatform.system == "aarch64-linux" then null else p.pkgsCross.aarch64-multiplatform;
    };
    "macos-aarch64" = {
      target = "aarch64-apple-darwin";
      libname = "libtemporalio_sdk_core_c_bridge.dylib";
      pickCross =
        p:
        if p.stdenv.hostPlatform.system == "aarch64-darwin" then
          null
        else
          throw "macos-aarch64 must be built natively on aarch64-darwin (no cross from ${p.stdenv.hostPlatform.system})";
    };
    "windows-x86_64" = {
      target = "x86_64-pc-windows-gnu";
      libname = "temporalio_sdk_core_c_bridge.dll";
      pickCross =
        p:
        # Always cross via mingw-w64 — no native windows support in this flake.
        p.pkgsCross.mingwW64;
    };
  };

  # Pinned toolchain via rust-overlay (consumes core-bridge/rust/rust-toolchain.toml).
  rustToolchain = pkgs.rust-bin.fromRustupToolchainFile ../core-bridge/rust/rust-toolchain.toml;

  craneLib = (crane.mkLib pkgs).overrideToolchain rustToolchain;

  # Source: the parent Rust workspace's tracked files (Cargo.toml, Cargo.lock,
  # rust-toolchain.toml) merged with the sdk-core flake input mounted at
  # `sdk-core/`. The submodule directory in the working tree is empty when the
  # flake is evaluated without `?submodules=1`, so we synthesize a complete
  # workspace here. This makes `nix build .#core-bridge-jar-<classifier>` work
  # without the user needing to know about submodules at all.
  src = pkgs.runCommand "core-bridge-rust" { } ''
    set -euo pipefail
    mkdir -p $out
    cp -r ${../core-bridge/rust}/. $out/
    chmod -R u+w $out
    rm -rf $out/sdk-core
    cp -r ${sdk-core} $out/sdk-core
    chmod -R u+w $out/sdk-core
    # Drop any stale target/ that cargo may have left behind.
    rm -rf $out/target $out/sdk-core/target
  '';

  buildHostSuffix = pkgs.pkgsBuildHost.stdenv.hostPlatform.rust.cargoEnvVarTarget;

  mkCoreBridge =
    classifier:
    let
      cfg = classifierConfigs.${classifier};
      inherit (cfg) target libname;

      crossPkgs = cfg.pickCross pkgs;
      isCross = crossPkgs != null;
      crossStdenv = if isCross then crossPkgs.stdenv else pkgs.stdenv;

      # Cross-tool paths derived from the cross stdenv so we never hand-spell
      # x86_64-w64-mingw32-cc / aarch64-unknown-linux-gnu-cc.
      crossCC = "${crossStdenv.cc}/bin/${crossStdenv.cc.targetPrefix}cc";
      crossCXX = "${crossStdenv.cc}/bin/${crossStdenv.cc.targetPrefix}c++";
      crossAR = "${crossStdenv.cc.bintools}/bin/${crossStdenv.cc.targetPrefix}ar";

      # Cargo's env-var-friendly form of the target triple.
      envTriple = lib.toUpper (lib.replaceStrings [ "-" ] [ "_" ] target);
      envTripleLower = lib.toLower envTriple;

      isWindows = classifier == "windows-x86_64";
      isDarwin = classifier == "macos-aarch64";

      # Per-target env applied for both deps-only and main package builds.
      crossEnv = lib.optionalAttrs isCross {
        # Cross compiler for the target's C/Rust object emission and linking.
        "CARGO_TARGET_${envTriple}_LINKER" = crossCC;
        "CC_${envTripleLower}" = crossCC;
        "CXX_${envTripleLower}" = crossCXX;
        "AR_${envTripleLower}" = crossAR;
        # Native compiler for build.rs scripts (which run on the build host).
        # Without this, cc-rs in a build.rs picks up CC and tries to use the
        # *cross* compiler to build host-side helpers — fails non-trivially.
        "CC_${buildHostSuffix}" = "${pkgs.stdenv.cc}/bin/cc";
        "CXX_${buildHostSuffix}" = "${pkgs.stdenv.cc}/bin/c++";
      };

      # Windows pthreads library path. Rust's target spec for x86_64-pc-windows-gnu
      # already statically links `-l:libpthread.a` by default, so we just need to
      # ensure the search path is on the linker's -L list. We pass it via
      # rustflags --config because the cc-wrapper's auto-injected -L flags are
      # bypassed when rustc invokes the linker with -nodefaultlibs.
      #
      # We deliberately DO NOT add `-Wl,--whole-archive,-lwinpthread,--no-whole-archive`
      # here — the default static link of libpthread.a already prevents the .dll
      # from depending on libwinpthread-1.dll at runtime. Adding --whole-archive
      # appends -lwinpthread at the very end of the link line, after msvcrt/mingwex,
      # which makes its undefined refs (`__imp__errno`, `malloc`, etc.) unresolvable.
      mingwPthreads = "${pkgs.pkgsCross.mingwW64.windows.pthreads}/lib";

      windowsConfigArg = lib.optionalString isWindows ''--config 'target.x86_64-pc-windows-gnu.rustflags=["-L","native=${mingwPthreads}"]' '';

      cargoExtraArgs = lib.concatStringsSep " " (
        [
          "--locked"
          "-p temporalio-sdk-core-c-bridge"
          "--target ${target}"
        ]
        ++ lib.optional isWindows windowsConfigArg
      );

      commonArgs = {
        inherit src cargoExtraArgs;
        pname = "core-bridge";
        version = "0.1.0";
        strictDeps = true;
        doCheck = false;

        # Tools that must run on the build host.
        nativeBuildInputs = [
          pkgs.protobuf
          pkgs.perl
        ]
        ++ lib.optionals isWindows [
          # ring 0.17 from crates.io ships pre-assembled NASM objects, so nasm
          # is defensive only. Cheap to keep.
          pkgs.nasm
          # rustc invokes the cross binutils' dlltool directly (not via the
          # cc wrapper), so it must be on PATH with the mingw triple prefix.
          pkgs.pkgsCross.mingwW64.buildPackages.binutils
        ];

        # Libraries linked into the target binary.
        # Note: aarch64-darwin frameworks (Security, CoreFoundation,
        # SystemConfiguration) are auto-provided by stdenv on modern nixpkgs;
        # the legacy darwin.apple_sdk.frameworks.* path was removed.
        buildInputs = lib.optionals isWindows [
          # Static libpthread.a for the -lwinpthread link-arg above.
          # (windows.pthreads is the modern attribute; mingw_w64_pthreads is deprecated.)
          pkgs.pkgsCross.mingwW64.windows.pthreads
        ];

        PROTOC = "${pkgs.protobuf}/bin/protoc";
        PROTOC_INCLUDE = "${pkgs.protobuf}/include";
        CARGO_BUILD_TARGET = target;
      }
      // crossEnv;

      cargoArtifacts = craneLib.buildDepsOnly commonArgs;

      # On macOS, the linker bakes the build-sandbox absolute path into the dylib's
      # LC_ID_DYLIB and references /nix/store libiconv. Both vanish at runtime on a
      # consumer mac. Rewrite via install_name_tool so:
      #   1. The dylib's own id becomes @rpath/<basename> (FFM/System.load tolerates this).
      #   2. libiconv references redirect to /usr/lib/libiconv.2.dylib (always present on macOS).
      darwinPostFixup = lib.optionalString isDarwin ''
        for dylib in "$out"/lib/*.dylib; do
          install_name_tool -id "@rpath/$(basename "$dylib")" "$dylib"
          # Redirect any nix-store libiconv reference to the system-shipped one.
          for ref in $(${pkgs.darwin.cctools}/bin/otool -L "$dylib" \
                         | awk 'NR>1 {print $1}' \
                         | grep -E '/nix/store/.*libiconv\.[0-9]+\.dylib$' || true); do
            install_name_tool -change "$ref" /usr/lib/libiconv.2.dylib "$dylib"
          done
          # Re-sign after install_name_tool mutations (codesign-required on aarch64-darwin).
          ${pkgs.darwin.sigtool}/bin/codesign -f -s - "$dylib"
        done
      '';

      cdylib = craneLib.buildPackage (
        commonArgs
        // {
          inherit cargoArtifacts;
          postFixup = darwinPostFixup;
        }
      );

      classifierJar = (import ./classifier-jar.nix { inherit pkgs; }) {
        inherit classifier libname cdylib;
      };
    in
    {
      inherit
        classifier
        target
        libname
        cdylib
        classifierJar
        ;
    };
in
{
  inherit mkCoreBridge classifierConfigs rustToolchain;
}
