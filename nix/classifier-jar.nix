# Wrap a cdylib derivation into a classifier JAR consumable by Gradle/Maven.
#
# The resulting JAR has a single entry: native/<classifier>/<libname>
# matching the resource path computed by NativeLoader.kt.
{ pkgs }:

{
  classifier,
  libname,
  cdylib,
}:

pkgs.runCommand "core-bridge-${classifier}.jar"
  {
    nativeBuildInputs = [ pkgs.zip ];
  }
  ''
    set -euo pipefail
    staging=$(mktemp -d)
    mkdir -p "$staging/native/${classifier}"

    # The cdylib derivation places the artifact in $out/lib/. Its filename varies by
    # target (libfoo.so / libfoo.dylib / foo.dll), so glob-and-copy under the canonical name.
    shopt -s nullglob
    candidates=( ${cdylib}/lib/${libname} ${cdylib}/lib/*.so ${cdylib}/lib/*.dylib ${cdylib}/lib/*.dll )
    if [ ''${#candidates[@]} -eq 0 ]; then
      echo "error: no native library found in ${cdylib}/lib/" >&2
      ls -la ${cdylib}/lib/ >&2 || true
      exit 1
    fi
    cp "''${candidates[0]}" "$staging/native/${classifier}/${libname}"

    # Reproducibility: zero out timestamps so the JAR is bit-identical across rebuilds.
    find "$staging" -exec touch -h -d @0 {} +

    mkdir -p "$out"
    ( cd "$staging" && zip -r -X -9 "$out/core-bridge-${classifier}.jar" . )
  ''
