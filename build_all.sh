#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

ZLIB_VER="1.2.13"
LIBPNG_VER="1.6.37"
OPENSSL_VER="1.1.1u"

DEFAULT_ABIS=("armeabi-v7a" "arm64-v8a")

function die {
  echo "ERROR: $*" >&2
  exit 1
}

function info {
  echo "==> $*"
}

function check_cmd {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

NDK_ARG="${1:-}"
PROJECT_DIR_ARG="${2:-}"

if [ -n "$NDK_ARG" ]; then
  ANDROID_NDK_ROOT="$NDK_ARG"
elif [ -n "${ANDROID_NDK_ROOT:-}" ]; then
  ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT}"
else
  die "NDK path not provided. Usage: $0 /path/to/android-ndk /path/to/android-project"
fi

if [ -n "$PROJECT_DIR_ARG" ]; then
  PROJECT_DIR="$PROJECT_DIR_ARG"
else
  PROJECT_DIR="$(pwd)"
fi

[ -d "$ANDROID_NDK_ROOT" ] || die "ANDROID_NDK_ROOT directory not found: $ANDROID_NDK_ROOT"
[ -d "$PROJECT_DIR" ] || die "PROJECT_DIR not found: $PROJECT_DIR"

UNAME="$(uname -s)"
case "$UNAME" in
  Linux*)   HOST_TAG="linux-x86_64" ;;
  Darwin*)  HOST_TAG="darwin-x86_64" ;;
  *)        die "Unsupported host OS: $UNAME" ;;
esac

TOOLCHAIN_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
[ -d "$TOOLCHAIN_BIN" ] || die "NDK toolchain bin not found at: $TOOLCHAIN_BIN"
export PATH="$TOOLCHAIN_BIN:$PATH"

for cmd in curl tar cmake make perl sed awk xz unzip; do
  check_cmd "$cmd"
done

command -v git >/dev/null 2>&1 || echo "git not found (optional)."

WD="$(pwd)"
SRCDIR="$WD/src"
BUILD_DIR_BASE="$WD/build"
DIST="$WD/dist"
ABIS=("${DEFAULT_ABIS[@]}")

mkdir -p "$SRCDIR" "$BUILD_DIR_BASE" "$DIST"

info "NDK: $ANDROID_NDK_ROOT"
info "Project dir: $PROJECT_DIR"
info "Toolchain bin: $TOOLCHAIN_BIN"

cd "$SRCDIR"

download_and_extract() {
  
  local primary="$1"
  local desired_dir="$2"
  local pkg="$3"
  local ver="${4:-}"
  local archive=""
  local success=false
  local candidates=()

  case "$pkg" in
    zlib)
    
      candidates+=("https://zlib.net/zlib-$ver.tar.gz")
      candidates+=("https://zlib.net/zlib-$ver.tar.xz")
      candidates+=("https://zlib.net/current/zlib.tar.gz")
      candidates+=("https://github.com/madler/zlib/releases/download/v$ver/zlib-$ver.tar.gz")
      candidates+=("https://github.com/madler/zlib/archive/refs/tags/v$ver.tar.gz")
      ;;
    libpng)
      candidates+=("https://download.sourceforge.net/libpng/libpng-$ver.tar.gz")
      candidates+=("https://sourceforge.net/projects/libpng/files/libpng16/$ver/libpng-$ver.tar.xz/download")
      candidates+=("https://github.com/glennrp/libpng/releases/download/v$ver/libpng-$ver.tar.gz")
      candidates+=("https://github.com/glennrp/libpng/archive/refs/tags/v$ver.tar.gz")
      ;;
    openssl)
      candidates+=("https://www.openssl.org/source/openssl-$ver.tar.gz")
      candidates+=("https://www.openssl.org/source/openssl-$ver.tar.xz")
      candidates+=("https://www.openssl.org/source/old/1.1.1/openssl-$ver.tar.gz")
      candidates+=("https://github.com/openssl/openssl/archive/refs/tags/OpenSSL_$ver.tar.gz")
      ;;
    *)
      
      candidates+=("$primary")
      ;;
  esac


  if [ -n "$primary" ]; then
    candidates+=("$primary")
  fi

  for cand in "${candidates[@]}"; do
    info "Trying download: $cand"
    archive="$(basename "$cand")"
    
    if curl -fSL --retry 3 --retry-delay 2 -o "$archive" "$cand"; then
      info "Downloaded $archive"
      
      case "$archive" in
        *.tar.gz|*.tgz)
          tar xzf "$archive" || (info "tar xzf failed on $archive" && rm -f "$archive" && continue)
          ;;
        *.tar.xz)
          tar xJf "$archive" || (info "tar xJf failed on $archive" && rm -f "$archive" && continue)
          ;;
        *.zip)
          unzip -q "$archive" || (info "unzip failed on $archive" && rm -f "$archive" && continue)
          ;;
        *)
          
          file "$archive" | grep -qE 'gzip|XZ compressed' || true
          if file "$archive" | grep -q gzip; then
            tar xzf "$archive" || (info "tar xzf failed on $archive" && rm -f "$archive" && continue)
          elif file "$archive" | grep -q 'XZ compressed'; then
            tar xJf "$archive" || (info "tar xJf failed on $archive" && rm -f "$archive" && continue)
          else
            info "Unknown archive type for $archive, skipping"
            rm -f "$archive" || true
            continue
          fi
          ;;
      esac

    
      if [ -n "$desired_dir" ]; then
        if [ -d "$desired_dir" ]; then
          info "$desired_dir exists after extraction"
        else
        
          found="$(ls -d ./* 2>/dev/null | grep -E "/(${pkg}|${pkg}[-_0-9v]+)" | head -n1 || true)"
          if [ -z "$found" ]; then
            
            found="$(for d in */ ; do echo "$d"; done | head -n1 || true)"
          fi
          if [ -n "$found" ]; then
            
            found="${found%/}"
            if [ "$found" != "$desired_dir" ]; then
              mv -v "$found" "$desired_dir" 2>/dev/null || true
            fi
          fi
        fi
      fi

      rm -f "$archive" || true
      success=true
      break
    else
      info "Download failed for $cand"
      rm -f "$archive" || true
    fi
  done

  if [ "$success" != true ]; then
    die "Failed to download and extract $pkg (version $ver). Tried multiple mirrors."
  fi

  
  if [ -n "$desired_dir" ] && [ ! -d "$desired_dir" ]; then
  
    maybe="$(ls -d ${pkg}* ${pkg}*${ver}* 2>/dev/null | head -n1 || true)"
    if [ -n "$maybe" ] && [ -d "$maybe" ]; then
      mv -v "$maybe" "$desired_dir" || true
    fi
  fi

  info "Extraction for $pkg done -> ${desired_dir}"
}


ZLIB_SRCDIR="$SRCDIR/zlib-$ZLIB_VER"
if [ ! -d "$ZLIB_SRCDIR" ]; then
  download_and_extract "" "$ZLIB_SRCDIR" "zlib" "$ZLIB_VER"
fi


LIBPNG_SRCDIR="$SRCDIR/libpng-$LIBPNG_VER"
if [ ! -d "$LIBPNG_SRCDIR" ]; then
  download_and_extract "" "$LIBPNG_SRCDIR" "libpng" "$LIBPNG_VER"
fi


OPENSSL_SRCDIR="$SRCDIR/openssl-$OPENSSL_VER"
if [ ! -d "$OPENSSL_SRCDIR" ]; then
  download_and_extract "" "$OPENSSL_SRCDIR" "openssl" "$OPENSSL_VER"
fi

cd "$WD"

for ABI in "${ABIS[@]}"; do
  info "-------- Building for ABI: $ABI --------"

  case "$ABI" in
    armeabi-v7a)
      ANDROID_API=24
      ANDROID_ABI="armeabi-v7a"
      OPENSSL_TARGET="android-arm"
      ;;
    arm64-v8a)
      ANDROID_API=24
      ANDROID_ABI="arm64-v8a"
      OPENSSL_TARGET="android-arm64"
      ;;
    *)
      die "Unsupported ABI: $ABI"
      ;;
  esac

  BUILD_DIR="$BUILD_DIR_BASE/$ABI"
  mkdir -p "$BUILD_DIR"
  cd "$BUILD_DIR"

  ABI_DIST="$DIST/$ABI"
  mkdir -p "$ABI_DIST/lib" "$ABI_DIST/include"

  info "[zlib] building for $ABI"
  ZLIB_BUILD="$BUILD_DIR/zlib"
  rm -rf "$ZLIB_BUILD"
  mkdir -p "$ZLIB_BUILD"

  cmake -S "$ZLIB_SRCDIR" -B "$ZLIB_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release

  cmake --build "$ZLIB_BUILD" -- -j$(nproc)

  if [ -f "$ZLIB_BUILD/libz.a" ]; then
    cp -av "$ZLIB_BUILD/libz.a" "$ABI_DIST/lib/"
  else
    find "$ZLIB_BUILD" -type f -name "libz*.a" -exec cp -av {} "$ABI_DIST/lib/" \; || true
  fi

  cp -av "$ZLIB_SRCDIR/zlib.h" "$ABI_DIST/include/" || true
  cp -av "$ZLIB_SRCDIR/zconf.h" "$ABI_DIST/include/" || true

  info "[libpng] building for $ABI (static libpng.a)"
  LIBPNG_BUILD="$BUILD_DIR/libpng"
  rm -rf "$LIBPNG_BUILD"
  mkdir -p "$LIBPNG_BUILD"

  cmake -S "$LIBPNG_SRCDIR" -B "$LIBPNG_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DZLIB_LIBRARY="$ABI_DIST/lib/libz.a" \
    -DZLIB_INCLUDE_DIR="$ABI_DIST/include" \
    -DPNG_SHARED=OFF \
    -DHAVE_LD_VERSION_SCRIPT=OFF

  cmake --build "$LIBPNG_BUILD" -- -j$(nproc)

  find "$LIBPNG_BUILD" -type f -name "libpng*.a" -exec cp -av {} "$ABI_DIST/lib/" \; || true

  cp -av "$LIBPNG_SRCDIR/png.h" "$ABI_DIST/include/" || true
  cp -av "$LIBPNG_SRCDIR/pngconf.h" "$ABI_DIST/include/" || true
  find "$LIBPNG_BUILD" -type f -name "pnglibconf.h" -exec cp -av {} "$ABI_DIST/include/" \; || true

  info "[OpenSSL] building for $ABI (shared libcrypto / libssl if possible)"
  OPENSSL_BUILD="$BUILD_DIR/openssl-build"
  rm -rf "$OPENSSL_BUILD"
  mkdir -p "$OPENSSL_BUILD"

  case "$ABI" in
    armeabi-v7a)
      export CC="armv7a-linux-androideabi${ANDROID_API}-clang"
      ;;
    arm64-v8a)
      export CC="aarch64-linux-android${ANDROID_API}-clang"
      ;;
  esac

  if ! command -v perl >/dev/null 2>&1; then
    die "perl is required to build OpenSSL"
  fi

  cd "$OPENSSL_SRCDIR"

  if [ -f "Makefile" ]; then
    make clean || true
  fi

  info "Configuring OpenSSL target=${OPENSSL_TARGET}, API=${ANDROID_API}, CC=${CC}"
  ./Configure ${OPENSSL_TARGET} shared -D__ANDROID_API__=${ANDROID_API} || die "OpenSSL Configure failed"

  info "make (OpenSSL)"
  make -j$(nproc) || die "OpenSSL make failed"

  info "Collecting OpenSSL outputs"
  find . -type f \( -name "libcrypto.so" -o -name "libcrypto.*.so" -o -name "libcrypto.a" \) -exec cp -av {} "$ABI_DIST/lib/" \; || true
  # also libssl if present
  find . -type f \( -name "libssl.so" -o -name "libssl.*.so" -o -name "libssl.a" \) -exec cp -av {} "$ABI_DIST/lib/" \; || true

  mkdir -p "$ABI_DIST/include/openssl"
  if [ -d "include/openssl" ]; then
    cp -av include/openssl/* "$ABI_DIST/include/openssl/" || true
  else
    find . -type d -name "include" -maxdepth 3 -exec cp -av {} "$ABI_DIST/include/openssl/" \; || true
  fi

  cd "$BUILD_DIR"

  info "Completed ABI: $ABI. Dist at $ABI_DIST"
done

info "Copying built artifacts into Android project (project: $PROJECT_DIR)"
for ABI in "${ABIS[@]}"; do
  ABI_DIST="$DIST/$ABI"
  DEST_LIB_DIR="$PROJECT_DIR/src/main/jni/libs/$ABI"
  DEST_INCLUDE_DIR="$PROJECT_DIR/src/main/jni/include"
  mkdir -p "$DEST_LIB_DIR"
  mkdir -p "$DEST_INCLUDE_DIR"

  if [ -d "$ABI_DIST/lib" ]; then
    cp -av "$ABI_DIST/lib/"* "$DEST_LIB_DIR/" || true
  fi

  if [ -d "$ABI_DIST/include" ]; then
    cp -av "$ABI_DIST/include/"* "$DEST_INCLUDE_DIR/" || true
  fi
done

info "All done. Dist directory: $DIST"
info "Remember: make the script executable if not already: chmod +x build_all.sh"
