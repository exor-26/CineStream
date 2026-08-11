cmake_minimum_required(VERSION 3.22.1)
project(cinestream_ffmpeg LANGUAGES C CXX)

if(NOT DEFINED FFMPEG_ROOT)
    message(FATAL_ERROR "FFMPEG_ROOT must point to CineStream's prebuilt FFmpeg directory")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FFMPEG_ABI_ROOT "${FFMPEG_ROOT}/${ANDROID_ABI}")
set(FFMPEG_INCLUDE_ROOT "${FFMPEG_ROOT}/include")

foreach(FFMPEG_LIB avutil avcodec)
    add_library(${FFMPEG_LIB} SHARED IMPORTED)
    set_target_properties(${FFMPEG_LIB} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_ABI_ROOT}/lib${FFMPEG_LIB}.so"
    )
endforeach()

add_library(cinestream_ffmpeg SHARED cinestream_ffmpeg.cpp)

target_include_directories(cinestream_ffmpeg PRIVATE "${FFMPEG_INCLUDE_ROOT}")

target_compile_options(cinestream_ffmpeg PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(cinestream_ffmpeg PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

target_link_libraries(cinestream_ffmpeg
    avcodec
    avutil
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles3-lib}
)
