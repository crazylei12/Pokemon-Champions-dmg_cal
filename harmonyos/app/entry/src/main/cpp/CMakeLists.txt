cmake_minimum_required(VERSION 3.5.0)
project(pcbridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(PC_REPLAY_ENABLED "Build replay recording support" OFF)

if(CMAKE_OHOS_ARCH_ABI STREQUAL "x86_64")
  set(PC_OPENCV_BUILD "$ENV{HARMONY_OPENCV_BUILD_X64}")
elseif(CMAKE_OHOS_ARCH_ABI STREQUAL "arm64-v8a")
  set(PC_OPENCV_BUILD "$ENV{HARMONY_OPENCV_BUILD_ARM64}")
else()
  message(FATAL_ERROR "Unsupported HarmonyOS ABI for OpenCV: ${CMAKE_OHOS_ARCH_ABI}")
endif()

if(PC_OPENCV_BUILD STREQUAL "")
  message(FATAL_ERROR "Set HARMONY_OPENCV_BUILD_ARM64 and HARMONY_OPENCV_BUILD_X64 to the ABI-specific OpenCV builds")
endif()

set(PC_OPENCV_SOURCE "$ENV{HARMONY_OPENCV_SOURCE}")
if(PC_OPENCV_SOURCE STREQUAL "")
  message(FATAL_ERROR "Set HARMONY_OPENCV_SOURCE to the OpenCV source tree")
endif()

if(NOT EXISTS "${PC_OPENCV_BUILD}/lib/libopencv_core.a")
  message(FATAL_ERROR "OpenCV 4.13.0 build not found at ${PC_OPENCV_BUILD}")
endif()

set(PCBRIDGE_SOURCES napi_init.cpp team_preview_engine.cpp)
if(PC_REPLAY_ENABLED)
  list(APPEND PCBRIDGE_SOURCES replay_recorder.cpp)
endif()

add_library(pcbridge SHARED ${PCBRIDGE_SOURCES})
target_compile_definitions(pcbridge PRIVATE PC_REPLAY_ENABLED=$<BOOL:${PC_REPLAY_ENABLED}>)
target_compile_options(pcbridge PRIVATE -Wall -Wextra -Werror -Wno-unused-command-line-argument)

target_include_directories(pcbridge PRIVATE
  "${PC_OPENCV_BUILD}"
  "${PC_OPENCV_SOURCE}/include"
  "${PC_OPENCV_SOURCE}/modules/core/include"
  "${PC_OPENCV_SOURCE}/modules/imgproc/include"
)

target_link_libraries(pcbridge PUBLIC
  "${PC_OPENCV_BUILD}/lib/libopencv_imgproc.a"
  "${PC_OPENCV_BUILD}/lib/libopencv_core.a"
  $<$<STREQUAL:${CMAKE_OHOS_ARCH_ABI},arm64-v8a>:${PC_OPENCV_BUILD}/3rdparty/lib/libtegra_hal.a>
  "${PC_OPENCV_BUILD}/3rdparty/lib/libittnotify.a"
  libace_napi.z.so
  libhilog_ndk.z.so
  libnative_avscreen_capture.so
  libnative_buffer.so
  libnative_media_core.so
  z
)

if(PC_REPLAY_ENABLED)
  target_link_libraries(pcbridge PUBLIC
    libnative_media_acodec.so
    libnative_media_avmuxer.so
    libnative_media_codecbase.so
    libnative_media_venc.so
  )
endif()
