#!/usr/bin/env bash
set -euo pipefail

OPENSSL_VER="openssl-3.1.6"
ZLIB_VER="zlib-1.3.1"
LIBPNG_VER="libpng-1.6.49"

OPENSSL_URL="https://www.openssl.org/source/${OPENSSL_VER}.tar.gz"
ZLIB_URL="https://zlib.net/${ZLIB_VER}.tar.gz"
LIBPNG_URL="https://download.sourceforge.net/libpng/${LIBPNG_VER}.tar.gz"

WORKDIR="$PWD/deps_build"
SRCDIR="$WORKDIR/src"
BUILDDIR="$WORKDIR/build"
OUTDIR="$PWD/prebuilt"

ANDROID_API=${ANDROID_API:-21}
NDK=${ANDROID_NDK_HOME:-${NDK:-}}


: "${ABIS:=arm64-v8a armeabi-v7a}"

if [ -z "$NDK" ]; then
  echo "ERROR: ANDROID_NDK_HOME or NDK must be set"
  exit 1
fi

HOST_TAG=linux-x86_64
if [ "$(uname -s)" = "Darwin" ]; then
  HOST_TAG=darwin-x86_64
fi
TOOLCHAIN_ROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"


read -ra ABIS_ARR <<< "$ABIS"

declare -A ABIMAP_TRIPLE
declare -A ABIMAP_OPENSSL
ABIMAP_TRIPLE["arm64-v8a"]="aarch64-linux-android"
ABIMAP_OPENSSL["arm64-v8a"]="android-arm64"
ABIMAP_TRIPLE["armeabi-v7a"]="armv7a-linux-androideabi"
ABIMAP_OPENSSL["armeabi-v7a"]="android-arm"


rm -rf "$WORKDIR"
mkdir -p "$SRCDIR" "$BUILDDIR" "$OUTDIR"

cd "$SRCDIR"


echo "Downloading sources..."
curl -L --retry 3 -O "$OPENSSL_URL"
curl -L --retry 3 -O "$ZLIB_URL"
curl -L --retry 3 -O "$LIBPNG_URL"

echo "Extracting..."
tar xf "${OPENSSL_VER}.tar.gz"
tar xf "${ZLIB_VER}.tar.gz"
tar xf "${LIBPNG_VER}.tar.gz"


for ABI in "${ABIS_ARR[@]}"; do
  echo "=== Building for $ABI ==="
  TARGET_TRIPLE=${ABIMAP_TRIPLE[$ABI]}
  OPENSSL_ARCH=${ABIMAP_OPENSSL[$ABI]}
  API=$ANDROID_API

  ABI_OUT="$OUTDIR/$ABI"
  mkdir -p "$ABI_OUT/lib" "$ABI_OUT/include" "$BUILDDIR/$ABI"

  export PATH="$TOOLCHAIN_ROOT/bin:$PATH"
  CC="${TARGET_TRIPLE}${API}-clang"
  CXX="${TARGET_TRIPLE}${API}-clang++"
  STRIP="${TOOLCHAIN_ROOT}/bin/llvm-strip"

  echo "Using CC=$CC CXX=$CXX"


  pushd "$SRCDIR/${ZLIB_VER}" > /dev/null
  make distclean || true
  ./configure --static --prefix="$BUILDDIR/$ABI/zlib" CC="$CC"
  make -j$(nproc)
  make install
  popd > /dev/null


  pushd "$SRCDIR/${LIBPNG_VER}" > /dev/null
  ./configure --host="$TARGET_TRIPLE" --prefix="$BUILDDIR/$ABI/libpng" \
    CPPFLAGS="-I$BUILDDIR/$ABI/zlib/include" LDFLAGS="-L$BUILDDIR/$ABI/zlib/lib"
  make -j$(nproc)
  make install
  popd > /dev/null

  
  pushd "$SRCDIR/${OPENSSL_VER}" > /dev/null
  make clean || true
  
  ./Configure "$OPENSSL_ARCH" no-shared -D__ANDROID_API__="$API"
  make -j$(nproc)
  mkdir -p "$BUILDDIR/$ABI/openssl/lib" "$BUILDDIR/$ABI/openssl/include"
  cp libcrypto.a libssl.a "$BUILDDIR/$ABI/openssl/lib/" || true
  cp -R include/openssl "$BUILDDIR/$ABI/openssl/include/" || true
  popd > /dev/null

  
  mkdir -p "$ABI_OUT/lib"
  pushd "$ABI_OUT/lib" > /dev/null
  
  $CXX -shared -fPIC -Wl,-soname,libz.so \
    -Wl,--whole-archive "$BUILDDIR/$ABI/zlib/lib/libz.a" -Wl,--no-whole-archive \
    -o libz.so || true
  $CXX -shared -fPIC -Wl,-soname,libpng.so \
    -Wl,--whole-archive "$BUILDDIR/$ABI/libpng/lib/libpng.a" "$BUILDDIR/$ABI/zlib/lib/libz.a" -Wl,--no-whole-archive \
    -o libpng.so || true


  $CXX -shared -fPIC -Wl,-soname,libcrypto.so \
    -Wl,--whole-archive "$BUILDDIR/$ABI/openssl/lib/libcrypto.a" -Wl,--no-whole-archive \
    -o libcrypto.so -lz -ldl -llog || true

  
  cp -r "$BUILDDIR/$ABI/openssl/include/openssl" "$ABI_OUT/include/" 2>/dev/null || true
  cp -r "$BUILDDIR/$ABI/libpng/include"/* "$ABI_OUT/include/" 2>/dev/null || true
  cp -r "$BUILDDIR/$ABI/zlib/include"/* "$ABI_OUT/include/" 2>/dev/null || true

  
  $STRIP --strip-unneeded libcrypto.so 2>/dev/null || true
  $STRIP --strip-unneeded libpng.so 2>/dev/null || true
  $STRIP --strip-unneeded libz.so 2>/dev/null || true
  popd > /dev/null

  echo "Artifacts placed in: $ABI_OUT"
done


for ABI in "${ABIS_ARR[@]}"; do
  mkdir -p libs/$ABI
  cp -v prebuilt/$ABI/lib/*.so libs/$ABI/ 2>/dev/null || true
done

if [ -f Android.mk ] || [ -f jni/Android.mk ]; then
  if command -v ndk-build >/dev/null 2>&1; then
    ndk-build NDK_PROJECT_PATH=. APP_ABI="${ABIS_ARR[*]}"
  else
    "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_ABI="${ABIS_ARR[*]}"
  fi
fi

echo "All done."
