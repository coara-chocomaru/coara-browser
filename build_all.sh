#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'


ZLIB_VER="1.2.13"
LIBPNG_VER="1.6.37"
OPENSSL_VER="1.1.1u"

ABIS=("armeabi-v7a" "arm64-v8a")
ANDROID_API_DEFAULT=24


die(){ echo "ERROR: $*" >&2; exit 1; }
info(){ echo "==> $*"; }
need_cmd(){ command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"; }

NDK_ARG="${1:-}"
PROJECT_DIR_ARG="${2:-}"

if [[ -n "$NDK_ARG" ]]; then
  ANDROID_NDK_ROOT="$NDK_ARG"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  : 
else
  die "NDK path not provided. Usage: $0 /path/to/android-ndk /path/to/android-project"
fi

if [[ -n "$PROJECT_DIR_ARG" ]]; then
  PROJECT_DIR="$PROJECT_DIR_ARG"
else
  PROJECT_DIR="$(pwd)"
fi

[[ -d "$ANDROID_NDK_ROOT" ]] || die "ANDROID_NDK_ROOT not found: $ANDROID_NDK_ROOT"
[[ -d "$PROJECT_DIR" ]]      || die "PROJECT_DIR not found: $PROJECT_DIR"

export ANDROID_NDK_ROOT
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
export ANDROID_NDK="$ANDROID_NDK_ROOT"

UNAME="$(uname -s)"
case "$UNAME" in
  Linux*)  HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  *)       die "Unsupported host: $UNAME" ;;
esac

TOOLCHAIN_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
[[ -d "$TOOLCHAIN_BIN" ]] || die "Toolchain bin not found: $TOOLCHAIN_BIN"
export PATH="$TOOLCHAIN_BIN:$PATH"


for c in curl tar cmake make perl sed awk xz unzip llvm-ar llvm-nm llvm-ranlib llvm-strip ld.lld; do
  need_cmd "$c"
done
command -v git >/dev/null 2>&1 || info "git not found (optional)."


ROOT="$(pwd)"
SRCDIR="$ROOT/src"
BUILDDIR="$ROOT/build"
DISTDIR="$ROOT/dist"
mkdir -p "$SRCDIR" "$BUILDDIR" "$DISTDIR"

info "NDK: $ANDROID_NDK_ROOT"
info "Toolchain: $TOOLCHAIN_BIN"
info "Project: $PROJECT_DIR"

cd "$SRCDIR"


download_and_extract() {
  local name="$1" ver="$2" dest="$3"
  local urls=()
  case "$name" in
    zlib)
      urls+=("https://zlib.net/zlib-$ver.tar.gz")
      urls+=("https://github.com/madler/zlib/archive/refs/tags/v$ver.tar.gz")
      ;;
    libpng)
      urls+=("https://download.sourceforge.net/libpng/libpng-$ver.tar.gz")
      urls+=("https://github.com/glennrp/libpng/archive/refs/tags/v$ver.tar.gz")
      ;;
    openssl)
      urls+=("https://www.openssl.org/source/openssl-$ver.tar.gz")
      urls+=("https://www.openssl.org/source/old/1.1.1/openssl-$ver.tar.gz")
      urls+=("https://github.com/openssl/openssl/archive/refs/tags/OpenSSL_$ver.tar.gz")
      ;;
    *)
      die "unknown package: $name"
      ;;
  esac

  if [[ -d "$dest" ]]; then
    info "$name already downloaded: $dest"
    return
  fi

  local ok=0
  for u in "${urls[@]}"; do
    info "Downloading $name $ver from: $u"
    f="$(basename "$u")"
    if curl -fSL --retry 3 --retry-delay 2 -o "$f" "$u"; then
      info "Extracting: $f"
      case "$f" in
        *.tar.gz|*.tgz) tar xzf "$f" ;;
        *.tar.xz)       tar xJf "$f" ;;
        *.zip)          unzip -q "$f" ;;
        *)              die "unknown archive: $f" ;;
      esac
      rm -f "$f"
      case "$name" in
        zlib)    mv zlib-$ver "$dest" 2>/dev/null || mv zlib-* "$dest" 2>/dev/null || true ;;
        libpng)  mv libpng-$ver "$dest" 2>/dev/null || mv libpng-* "$dest" 2>/dev/null || true ;;
        openssl) mv openssl-$ver "$dest" 2>/dev/null || mv openssl-* "$dest" 2>/dev/null || true ;;
      esac
      [[ -d "$dest" ]] || die "failed to extract $name $ver"
      ok=1
      break
    else
      info "Download failed: $u"
    fi
  done
  [[ $ok -eq 1 ]] || die "All mirrors failed for $name $ver"
}

ZLIB_SRC="$SRCDIR/zlib-$ZLIB_VER"
LIBPNG_SRC="$SRCDIR/libpng-$LIBPNG_VER"
OPENSSL_SRC="$SRCDIR/openssl-$OPENSSL_VER"

download_and_extract "zlib" "$ZLIB_VER" "$ZLIB_SRC"
download_and_extract "libpng" "$LIBPNG_VER" "$LIBPNG_SRC"
download_and_extract "openssl" "$OPENSSL_VER" "$OPENSSL_SRC"

cd "$ROOT"

# ---------------- build per ABI ----------------
for ABI in "${ABIS[@]}"; do
  info "========== Build ABI: $ABI =========="
  ANDROID_API="$ANDROID_API_DEFAULT"

  case "$ABI" in
    armeabi-v7a)
      ANDROID_ABI="armeabi-v7a"
      OPENSSL_TARGET="android-arm"
      TRIPLE="armv7a-linux-androideabi"
      ;;
    arm64-v8a)
      ANDROID_ABI="arm64-v8a"
      OPENSSL_TARGET="android-arm64"
      TRIPLE="aarch64-linux-android"
      ;;
    *)
      die "Unsupported ABI: $ABI"
      ;;
  esac

  CC="${TOOLCHAIN_BIN}/${TRIPLE}${ANDROID_API}-clang"
  CXX="${TOOLCHAIN_BIN}/${TRIPLE}${ANDROID_API}-clang++"
  [[ -x "$CC" && -x "$CXX" ]] || die "clang for ${TRIPLE}${ANDROID_API} not found: CC=$CC CXX=$CXX"

  OUT="$BUILDDIR/$ABI"
  mkdir -p "$OUT"
  DIST_ABI="$DISTDIR/$ABI"
  mkdir -p "$DIST_ABI/lib" "$DIST_ABI/include"

  # ---- zlib ----
  info "[zlib] $ABI"
  Z_BUILD="$OUT/zlib"
  rm -rf "$Z_BUILD"; mkdir -p "$Z_BUILD"

  cmake -S "$ZLIB_SRC" -B "$Z_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$Z_BUILD" -- -j"$(nproc)"

  ZLIB_A="$(find "$Z_BUILD" -maxdepth 2 -type f -name 'libz*.a' | head -n1 || true)"
  [[ -n "${ZLIB_A:-}" ]] || die "libz.a not found for $ABI"
  cp -av "$ZLIB_A" "$DIST_ABI/lib/libz.a"
  cp -av "$ZLIB_SRC/zlib.h" "$DIST_ABI/include/"
  if [[ -f "$Z_BUILD/zconf.h" ]]; then
    cp -av "$Z_BUILD/zconf.h" "$DIST_ABI/include/"
  else
    find "$Z_BUILD" -type f -name zconf.h -exec cp -av {} "$DIST_ABI/include/" \; || true
    [[ -f "$DIST_ABI/include/zconf.h" ]] || cp -av "$ZLIB_SRC/zconf.h" "$DIST_ABI/include/" || true
  fi

  # ---- libpng ----
  info "[libpng] $ABI"
  P_BUILD="$OUT/libpng"
  rm -rf "$P_BUILD"; mkdir -p "$P_BUILD"

  cmake -S "$LIBPNG_SRC" -B "$P_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DPNG_SHARED=OFF \
    -DHAVE_LD_VERSION_SCRIPT=OFF \
    -DZLIB_LIBRARY="$DIST_ABI/lib/libz.a" \
    -DZLIB_INCLUDE_DIR="$DIST_ABI/include" \
    -DCMAKE_C_FLAGS="-fPIC -I$DIST_ABI/include" \
    -DCMAKE_CXX_FLAGS="-fPIC -I$DIST_ABI/include"
  cmake --build "$P_BUILD" -- -j"$(nproc)"

  PNG_A="$(find "$P_BUILD" -maxdepth 2 -type f -name 'libpng*.a' | head -n1 || true)"
  [[ -n "${PNG_A:-}" ]] || die "libpng.a not found for $ABI"
  cp -av "$PNG_A" "$DIST_ABI/lib/"
  cp -av "$LIBPNG_SRC/png.h" "$DIST_ABI/include/"
  cp -av "$LIBPNG_SRC/pngconf.h" "$DIST_ABI/include/"
  find "$P_BUILD" -type f -name "pnglibconf.h" -exec cp -av {} "$DIST_ABI/include/" \; || true

  # ---- OpenSSL (libcrypto static) with dynamic option filtering ----
  info "[OpenSSL(libcrypto static)] $ABI"
  O_BUILD="$OUT/openssl"
  rm -rf "$O_BUILD"; mkdir -p "$O_BUILD"

  pushd "$OPENSSL_SRC" >/dev/null
    make clean >/dev/null 2>&1 || true

    export CC="$CC"
    export CXX="$CXX"
    export AR="$TOOLCHAIN_BIN/llvm-ar"
    export NM="$TOOLCHAIN_BIN/llvm-nm"
    export RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
    export STRIP="$TOOLCHAIN_BIN/llvm-strip"
    export LD="$TOOLCHAIN_BIN/ld.lld"

  
    API_DEF="-D__ANDROID_API__=${ANDROID_API}"

  
    CANDIDATE_OPTS=( "no-shared" "no-tests" "no-ssl3" "no-ssl2" "no-comp" "no-engine" "no-async" "no-ui-console" )

    info "Probing supported Configure options..."
    
    CONFIG_HELP="$(perl ./Configure --help 2>&1 || true)"

    
    CONF_OPTS=""
    for opt in "${CANDIDATE_OPTS[@]}"; do
      if echo "$CONFIG_HELP" | grep -F -q "$opt"; then
        CONF_OPTS+=" $opt"
      else
        info "Configure does not advertise option: $opt (skipping)"
      fi
    done

  
    if [[ -z "${CONF_OPTS// /}" ]]; then
      if echo "$CONFIG_HELP" | grep -F -q "no-shared"; then
        CONF_OPTS=" no-shared"
      else
        info "No candidate options recognized by Configure; will call Configure without 'no-*' options"
      fi
    fi

    info "Configure: target=${OPENSSL_TARGET} API=${ANDROID_API} opts='${CONF_OPTS}'"

    
    if ! perl ./Configure "${OPENSSL_TARGET}" ${API_DEF} ${CONF_OPTS} --prefix="$O_BUILD/prefix" --openssldir="$O_BUILD/ssl"; then
      echo "---- Configure failed: show first 200 lines of configdata.pm and config.log (if present) ----" >&2
      [[ -f "configdata.pm" ]] && sed -n '1,200p' configdata.pm >&2 || true
      [[ -f "config.log" ]] && sed -n '1,200p' config.log >&2 || true
    
      echo "---- Configure --help output (snippet) ----" >&2
      echo "$CONFIG_HELP" | sed -n '1,200p' >&2 || true
      die "OpenSSL Configure failed for ${OPENSSL_TARGET}"
    fi

    
    make -j"$(nproc)" build_generated libcrypto.a

    
    make install_dev >/dev/null || true

    [[ -f "libcrypto.a" ]] || die "OpenSSL libcrypto.a not built for $ABI"
    cp -av "libcrypto.a" "$DIST_ABI/lib/"
    mkdir -p "$DIST_ABI/include/openssl"
    if [[ -d "$O_BUILD/prefix/include/openssl" ]]; then
      cp -av "$O_BUILD/prefix/include/openssl/"* "$DIST_ABI/include/openssl/"
    else
      cp -av "include/openssl/"* "$DIST_ABI/include/openssl/" || true
    fi
  popd >/dev/null

  info "Done ABI $ABI → $DIST_ABI"
done


info "Copying into Android project: $PROJECT_DIR"
for ABI in "${ABIS[@]}"; do
  ABI_DIST="$DISTDIR/$ABI"
  DEST_LIB_DIR="$PROJECT_DIR/src/main/jni/libs/$ABI"
  DEST_INC_DIR="$PROJECT_DIR/src/main/jni/include"
  mkdir -p "$DEST_LIB_DIR" "$DEST_INC_DIR"
  cp -av "$ABI_DIST/lib/"* "$DEST_LIB_DIR/" || true
  cp -av "$ABI_DIST/include/"* "$DEST_INC_DIR/" || true
done

info "All done. Dist: $DISTDIR"
