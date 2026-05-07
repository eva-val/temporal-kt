{
  description = "temporal-kt — Nix-built core-bridge classifier JARs";

  # Public binary cache populated by CI. Nix prompts to accept on first build;
  # accepting (or being a trusted-user) means subsequent `nix build` calls pull
  # pre-built derivations instead of rebuilding the Rust sdk-core from source.
  nixConfig = {
    extra-substituters = [ "https://temporal-kt.cachix.org" ];
    extra-trusted-public-keys = [
      "temporal-kt.cachix.org-1:64tIbeewjR884KfUgURvpvwSz0vPUUqLdrFPWXzpOFw="
    ];
  };

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    crane.url = "github:ipetkov/crane";
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # sdk-core is also tracked as a git submodule (`core-bridge/rust/sdk-core`)
    # so cargo / IDEs / non-Nix workflows still work. It's mirrored here as a
    # flake input so `nix build .#...` works without needing `?submodules=1`.
    # Keep this rev in sync with `git submodule status` for that path; bump via
    # `nix flake update sdk-core` after a `git submodule update --remote`.
    sdk-core = {
      url = "github:temporalio/sdk-core/e49359b27095b81d2dc8e07d260d535695b3be02";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      rust-overlay,
      crane,
      treefmt-nix,
      sdk-core,
    }:
    let
      # Map host system → list of classifiers it can produce.
      # x86_64-linux / aarch64-linux can do all three Linux-reachable targets
      # (the third being Windows via mingw cross). Darwin hosts only build
      # their own macos-aarch64 — no cross-from-darwin.
      #
      # The full classifier list is also enumerated in
      # `buildSrc/src/main/kotlin/CoreBridgeNative.kt` (`allClassifiers`) and
      # in `nix/core-bridge.nix` (`classifierConfigs`). Keep all three in sync.
      hostClassifiers = {
        "x86_64-linux" = [
          "linux-x86_64-gnu"
          "linux-aarch64-gnu"
          "windows-x86_64"
        ];
        "aarch64-linux" = [
          "linux-x86_64-gnu"
          "linux-aarch64-gnu"
          "windows-x86_64"
        ];
        "aarch64-darwin" = [ "macos-aarch64" ];
      };

      supportedSystems = builtins.attrNames hostClassifiers;
    in
    flake-utils.lib.eachSystem supportedSystems (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ (import rust-overlay) ];
        };

        coreBridge = import ./nix/core-bridge.nix {
          inherit pkgs crane sdk-core;
        };

        # treefmt config: nixfmt for *.nix, ktlint for *.kt/*.kts.
        # ktlint version drift vs the Gradle plugin's pin (1.5.0) can produce
        # disagreements between `nix fmt` and `gradle ktlintCheck`; if that
        # bites, pin via `programs.ktlint.package = ...` to a matching build.
        treefmtEval = treefmt-nix.lib.evalModule pkgs {
          projectRootFile = "flake.nix";
          programs.nixfmt.enable = true;
          programs.ktlint.enable = true;
          settings.global.excludes = [
            "**/build/**"
            "**/generated/**"
            "**/generated-sources/**"
            "**/.gradle/**"
            "**/sdk-core/**"
            "*.lock"
            "*.jar"
            "*.png"
          ];
        };

        classifiers = hostClassifiers.${system};

        builtBridges = builtins.listToAttrs (
          map (classifier: {
            name = classifier;
            value = coreBridge.mkCoreBridge classifier;
          }) classifiers
        );

        # packages.<system>.core-bridge-jar-<classifier> → classifier JAR
        # packages.<system>.core-bridge-lib-<classifier>  → raw cdylib derivation (for inspection)
        jarPackages = builtins.listToAttrs (
          map (classifier: {
            name = "core-bridge-jar-${classifier}";
            value = builtBridges.${classifier}.classifierJar;
          }) classifiers
        );

        libPackages = builtins.listToAttrs (
          map (classifier: {
            name = "core-bridge-lib-${classifier}";
            value = builtBridges.${classifier}.cdylib;
          }) classifiers
        );
      in
      {
        packages = jarPackages // libPackages;

        formatter = treefmtEval.config.build.wrapper;

        devShells.default = pkgs.mkShell {
          packages = [
            coreBridge.rustToolchain
            pkgs.protobuf
            pkgs.cargo-edit
            pkgs.zip
          ];

          shellHook = ''
            echo "temporal-kt dev shell — pinned Rust toolchain available."
            echo "Build classifier JARs with: nix build .#core-bridge-jar-<classifier>"
          '';
        };
      }
    );
}
