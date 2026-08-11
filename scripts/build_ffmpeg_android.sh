#!/usr/bin/env bash
set -euo pipefail

FFMPEG_VERSION="8.1.2"
ANDROID_API="24"
OUTPUT_ROOT="${1:?Usage: build_ffmpeg_android.sh <output-root>}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "ANDROID_NDK_HOME must point to an installed Android NDK." >&2
  exit 1
fi

case "$(uname -s)" in
  Linux*) HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac

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
DECODERS=(
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
)

COMMON_DECODER_FLAGS=()
for decoder in "${DECODERS[@]}"; do
  COMMON_DECODER_FLAGS+=("--enable-decoder=${decoder}")
done

build_abi() {
  local abi="$1"
  local arch="$2"
  local cpu="$3"
  local compiler_prefix="$4"

  local final_dir="${OUTPUT_ROOT}/${abi}"
  local marker="${final_dir}/.ffmpeg-${FFMPEG_VERSION}"
  if [[ -f "${marker}" && -f "${final_dir}/libavcodec.so" && -f "${final_dir}/libavutil.so" ]]; then
    echo "FFmpeg ${FFMPEG_VERSION} already built for ${abi}."
    return
  fi

  local build_dir="${WORK_ROOT}/build-${abi}"
  local install_dir="${WORK_ROOT}/install-${abi}"
  rm -rf "${build_dir}" "${install_dir}" "${final_dir}"
  mkdir -p "${build_dir}" "${install_dir}" "${final_dir}"

  local cc="${TOOLCHAIN}/bin/${compiler_prefix}${ANDROID_API}-clang"
  if [[ ! -x "${cc}" && -f "${cc}.cmd" ]]; then
    cc="${cc}.cmd"
  fi
  if [[ ! -e "${cc}" ]]; then
    echo "Android compiler not found: ${cc}" >&2
    exit 1
  fi

  pushd "${build_dir}" >/dev/null
  "${SOURCE_DIR}/configure" \
    --prefix="${install_dir}" \
    --target-os=android \
    --arch="${arch}" \
    --cpu="${cpu}" \
    --enable-cross-compile \
    --cc="${cc}" \
    --ar="${TOOLCHAIN}/bin/llvm-ar" \
    --ranlib="${TOOLCHAIN}/bin/llvm-ranlib" \
    --strip="${TOOLCHAIN}/bin/llvm-strip" \
    --enable-shared \
    --disable-static \
    --enable-pic \
    --disable-symver \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --disable-network \
    --disable-avdevice \
    --disable-avformat \
    --disable-avfilter \
    --disable-postproc \
    --disable-swresample \
    --disable-everything \
    --enable-avcodec \
    --enable-avutil \
    --extra-cflags="-O3 -fPIC -ffunction-sections -fdata-sections" \
    --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384" \
    "${COMMON_DECODER_FLAGS[@]}"

  make -j"${JOBS}"
  make install
  popd >/dev/null

  cp -L "${install_dir}/lib/libavcodec.so" "${final_dir}/libavcodec.so"
  cp -L "${install_dir}/lib/libavutil.so" "${final_dir}/libavutil.so"

  "${TOOLCHAIN}/bin/llvm-strip" --strip-unneeded "${final_dir}/libavcodec.so" || true
  "${TOOLCHAIN}/bin/llvm-strip" --strip-unneeded "${final_dir}/libavutil.so" || true

  if [[ ! -d "${OUTPUT_ROOT}/include" ]]; then
    cp -R "${install_dir}/include" "${OUTPUT_ROOT}/include"
  fi

  printf '%s\n' "${FFMPEG_VERSION}" > "${marker}"
}

build_abi "arm64-v8a" "aarch64" "armv8-a" "aarch64-linux-android"
build_abi "armeabi-v7a" "arm" "armv7-a" "armv7a-linux-androideabi"

echo "Minimal FFmpeg video decoder build ready at ${OUTPUT_ROOT}."
