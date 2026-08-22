#!/usr/bin/env bash
set -euo pipefail

FFMPEG_VERSION="8.1.2"
ANDROID_API="24"
PINNED_NDK_VERSION="26.1.10909125"
OUTPUT_ROOT_INPUT="${1:?Usage: build_ffmpeg_android.sh <output-root>}"

case "$(uname -s)" in
  Linux*) HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

# Build steps change into ABI-specific directories, so keep every generated path absolute.
mkdir -p "${OUTPUT_ROOT_INPUT}"
OUTPUT_ROOT="$(cd "${OUTPUT_ROOT_INPUT}" && pwd)"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${SDK_ROOT}" && "${HOST_TAG}" == "windows-x86_64" && -n "${LOCALAPPDATA:-}" ]]; then
  if command -v cygpath >/dev/null 2>&1; then
    SDK_ROOT="$(cygpath -u "${LOCALAPPDATA}/Android/Sdk")"
  else
    SDK_ROOT="${LOCALAPPDATA}/Android/Sdk"
  fi
fi

# Prefer the exact project-pinned side-by-side NDK. ANDROID_NDK_HOME is often set by
# CI images to a different preinstalled version, which made builds non-reproducible.
NDK_ROOT="${CINESTREAM_NDK_ROOT:-}"
if [[ -z "${NDK_ROOT}" && -n "${SDK_ROOT}" ]]; then
  NDK_ROOT="${SDK_ROOT}/ndk/${PINNED_NDK_VERSION}"
fi

# Fall back to the conventional NDK environment variables only when they really
# point to the pinned revision. Use CINESTREAM_NDK_ROOT for an intentional override.
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [[ -n "${candidate}" && -d "${candidate}" ]] || continue
    revision="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "${candidate}/source.properties" 2>/dev/null | head -n 1)"
    if [[ "${revision}" == "${PINNED_NDK_VERSION}" ]]; then
      NDK_ROOT="${candidate}"
      break
    fi
  done
fi

if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "Android NDK ${PINNED_NDK_VERSION} was not found." >&2
  echo "Install it with sdkmanager \"ndk;${PINNED_NDK_VERSION}\" or set CINESTREAM_NDK_ROOT explicitly." >&2
  exit 1
fi

NDK_REVISION="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "${NDK_ROOT}/source.properties" 2>/dev/null | head -n 1)"
if [[ -z "${CINESTREAM_NDK_ROOT:-}" && "${NDK_REVISION}" != "${PINNED_NDK_VERSION}" ]]; then
  echo "Resolved NDK revision '${NDK_REVISION:-unknown}' does not match pinned ${PINNED_NDK_VERSION}." >&2
  exit 1
fi

echo "Using Android NDK ${NDK_REVISION:-unknown} at ${NDK_ROOT}"

TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "NDK LLVM toolchain not found: ${TOOLCHAIN}" >&2
  exit 1
fi

WORK_ROOT="${OUTPUT_ROOT}/.work-${FFMPEG_VERSION}"
SOURCE_ARCHIVE="${WORK_ROOT}/ffmpeg-${FFMPEG_VERSION}.tar.xz"
SOURCE_DIR="${WORK_ROOT}/ffmpeg-${FFMPEG_VERSION}"
mkdir -p "${WORK_ROOT}" "${OUTPUT_ROOT}"

if [[ ! -f "${SOURCE_ARCHIVE}" ]]; then
  echo "Downloading FFmpeg ${FFMPEG_VERSION} from ffmpeg.org..."
  curl --fail --location --retry 3 \
    "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    -o "${SOURCE_ARCHIVE}"
fi

if [[ ! -f "${SOURCE_DIR}/configure" ]]; then
  rm -rf "${SOURCE_DIR}"
  tar -xJf "${SOURCE_ARCHIVE}" -C "${WORK_ROOT}"
fi

JOBS="${CINESTREAM_NATIVE_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"
MAKE_COMMAND="${CINESTREAM_MAKE_COMMAND:-make}"
if ! command -v "${MAKE_COMMAND}" >/dev/null 2>&1; then
  if [[ "${HOST_TAG}" == "windows-x86_64" && -x "/c/mingw64/bin/mingw32-make.exe" ]]; then
    MAKE_COMMAND="/c/mingw64/bin/mingw32-make.exe"
  else
    echo "GNU Make was not found. Set CINESTREAM_MAKE_COMMAND to its executable." >&2
    exit 1
  fi
fi

DECODERS=(
  av1
  h264
  hevc
  mpeg4
  mpeg2video
  mpeg1video
  vc1
  msmpeg4v2
  msmpeg4v3
  h263
  flv
  mjpeg
  vp8
  vp9
)

COMMON_DECODER_FLAGS=()
for decoder in "${DECODERS[@]}"; do
  COMMON_DECODER_FLAGS+=("--enable-decoder=${decoder}")
done

stage_headers() {
  local install_dir="$1"
  local required_header="${OUTPUT_ROOT}/include/libavcodec/avcodec.h"

  if [[ -f "${required_header}" ]]; then
    return 0
  fi
  if [[ ! -f "${install_dir}/include/libavcodec/avcodec.h" ]]; then
    return 1
  fi

  rm -rf "${OUTPUT_ROOT}/include"
  mkdir -p "${OUTPUT_ROOT}/include"
  cp -R "${install_dir}/include/." "${OUTPUT_ROOT}/include/"
  [[ -f "${required_header}" ]]
}

build_abi() {
  local abi="$1"
  local arch="$2"
  local cpu="$3"
  local compiler_prefix="$4"

  local final_dir="${OUTPUT_ROOT}/${abi}"
  local marker="${final_dir}/.ffmpeg-${FFMPEG_VERSION}-static"
  local build_dir="${WORK_ROOT}/build-${abi}-static"
  local install_dir="${WORK_ROOT}/install-${abi}-static"

  if [[ -f "${marker}" \
      && -f "${final_dir}/libavcodec.a" \
      && -f "${final_dir}/libavutil.a" \
      && -f "${final_dir}/libswscale.a" ]]; then
    if stage_headers "${install_dir}"; then
      echo "FFmpeg ${FFMPEG_VERSION} static libraries already built for ${abi}."
      return
    fi
    echo "Cached ${abi} libraries exist but public headers are missing; rebuilding ${abi}."
  fi

  rm -rf "${build_dir}" "${install_dir}" "${final_dir}"
  mkdir -p "${build_dir}" "${install_dir}" "${final_dir}"

  # Keep Windows source paths relative. Absolute MSYS /c/... paths are not understood by native
  # MinGW Make, while in-tree builds make FFmpeg's .S inputs collide with generated .s files on
  # case-insensitive NTFS.
  local configure_script="${SOURCE_DIR}/configure"
  if [[ "${HOST_TAG}" == "windows-x86_64" ]]; then
    configure_script="../ffmpeg-${FFMPEG_VERSION}/configure"
  fi

  local cc="${TOOLCHAIN}/bin/${compiler_prefix}${ANDROID_API}-clang"
  local cxx="${TOOLCHAIN}/bin/${compiler_prefix}${ANDROID_API}-clang++"
  if [[ ! -x "${cc}" && -f "${cc}.cmd" ]]; then
    cc="${cc}.cmd"
  fi
  if [[ ! -x "${cxx}" && -f "${cxx}.cmd" ]]; then
    cxx="${cxx}.cmd"
  fi
  if [[ ! -e "${cc}" ]]; then
    echo "Android compiler not found: ${cc}" >&2
    exit 1
  fi
  if [[ ! -e "${cxx}" ]]; then
    echo "Android C++ compiler not found: ${cxx}" >&2
    exit 1
  fi

  local configure_source_dir="${SOURCE_DIR}"
  local configure_install_dir="${install_dir}"
  local configure_cc="${cc}"
  local configure_cxx="${cxx}"
  local configure_ar="${TOOLCHAIN}/bin/llvm-ar"
  local configure_ranlib="${TOOLCHAIN}/bin/llvm-ranlib"
  local configure_strip="${TOOLCHAIN}/bin/llvm-strip"
  if [[ "${HOST_TAG}" == "windows-x86_64" ]] && command -v cygpath >/dev/null 2>&1; then
    # Use relative source/install paths so MinGW Make never parses a drive-letter colon as a
    # Make path separator. Compiler tool paths remain native Windows paths.
    configure_source_dir="../ffmpeg-${FFMPEG_VERSION}"
    configure_install_dir="../install-${abi}-static"
    configure_cc="$(cygpath -m "${cc}")"
    configure_cxx="$(cygpath -m "${cxx}")"
    configure_ar="$(cygpath -m "${configure_ar}")"
    configure_ranlib="$(cygpath -m "${configure_ranlib}")"
    configure_strip="$(cygpath -m "${configure_strip}")"
  fi

  pushd "${build_dir}" >/dev/null
  "${configure_script}" \
    --prefix="${configure_install_dir}" \
    --target-os=android \
    --arch="${arch}" \
    --cpu="${cpu}" \
    --enable-cross-compile \
    --cc="${configure_cc}" \
    --cxx="${configure_cxx}" \
    --ar="${configure_ar}" \
    --ranlib="${configure_ranlib}" \
    --strip="${configure_strip}" \
    --disable-shared \
    --enable-static \
    --enable-pic \
    --disable-symver \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --disable-network \
    --disable-avdevice \
    --disable-avformat \
    --disable-avfilter \
    --disable-swresample \
    --disable-everything \
    --enable-avcodec \
    --enable-avutil \
    --enable-swscale \
    --extra-cflags="-O3 -fPIC -ffunction-sections -fdata-sections" \
    "${COMMON_DECODER_FLAGS[@]}"

  if [[ "${HOST_TAG}" == "windows-x86_64" ]]; then
    # FFmpeg canonicalizes the relative source directory back to /c/... during configure.
    # Restore the relative sibling path for native MinGW Make after all feature probes finish.
    sed -i "1cinclude ${configure_source_dir}/Makefile" Makefile
    sed -i \
      -e "s|^SRC_PATH=.*$|SRC_PATH=${configure_source_dir}|" \
      -e "s|^SRC_LINK=.*$|SRC_LINK=${configure_source_dir}|" \
      ffbuild/config.mak
  fi

  "${MAKE_COMMAND}" -j"${JOBS}"
  "${MAKE_COMMAND}" install
  popd >/dev/null

  cp "${install_dir}/lib/libavcodec.a" "${final_dir}/libavcodec.a"
  cp "${install_dir}/lib/libavutil.a" "${final_dir}/libavutil.a"
  cp "${install_dir}/lib/libswscale.a" "${final_dir}/libswscale.a"

  if ! stage_headers "${install_dir}"; then
    echo "FFmpeg public headers were not staged correctly from ${install_dir}/include." >&2
    exit 1
  fi

  printf '%s\n' "${FFMPEG_VERSION}" > "${marker}"
}

build_abi "arm64-v8a" "aarch64" "armv8-a" "aarch64-linux-android"
build_abi "armeabi-v7a" "arm" "armv7-a" "armv7a-linux-androideabi"

if [[ ! -f "${OUTPUT_ROOT}/include/libavcodec/avcodec.h" ]]; then
  echo "FFmpeg staging is incomplete: libavcodec/avcodec.h is missing." >&2
  exit 1
fi

echo "Minimal FFmpeg video decoder static libraries ready at ${OUTPUT_ROOT}."
