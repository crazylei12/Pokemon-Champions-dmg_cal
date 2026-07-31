cmake_minimum_required(VERSION 3.5.0)
project(pcprobe)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_OHOS_ARCH_ABI STREQUAL "x86_64")
  set(PC_OPENCV_BUILD "D:/HarmonyOS/Build/opencv-4.13.0-x86_64")
elseif(CMAKE_OHOS_ARCH_ABI STREQUAL "arm64-v8a")
  set(PC_OPENCV_BUILD "D:/HarmonyOS/Build/opencv-4.13.0-arm64-v8a")
else()
  message(FATAL_ERROR "Unsupported HarmonyOS ABI for OpenCV: ${CMAKE_OHOS_ARCH_ABI}")
endif()

if(DEFINED ENV{HARMONY_OPENCV_BUILD} AND NOT "$ENV{HARMONY_OPENCV_BUILD}" STREQUAL "")
  set(PC_OPENCV_BUILD "$ENV{HARMONY_OPENCV_BUILD}")
endif()

set(PC_OPENCV_SOURCE "D:/HarmonyOS/ThirdParty/opencv-4.13.0")
if(DEFINED ENV{HARMONY_OPENCV_SOURCE} AND NOT "$ENV{HARMONY_OPENCV_SOURCE}" STREQUAL "")
  set(PC_OPENCV_SOURCE "$ENV{HARMONY_OPENCV_SOURCE}")
endif()

if(NOT EXISTS "${PC_OPENCV_BUILD}/lib/libopencv_core.a")
  message(FATAL_ERROR "OpenCV 4.13.0 build not found at ${PC_OPENCV_BUILD}")
endif()

add_library(pcprobe SHARED napi_init.cpp)

target_include_directories(pcprobe PRIVATE
  "${PC_OPENCV_BUILD}"
  "${PC_OPENCV_SOURCE}/include"
  "${PC_OPENCV_SOURCE}/modules/core/include"
  "${PC_OPENCV_SOURCE}/modules/imgproc/include"
)

target_link_libraries(pcprobe PUBLIC
  "${PC_OPENCV_BUILD}/lib/libopencv_imgproc.a"
  "${PC_OPENCV_BUILD}/lib/libopencv_core.a"
  $<$<STREQUAL:${CMAKE_OHOS_ARCH_ABI},arm64-v8a>:${PC_OPENCV_BUILD}/3rdparty/lib/libtegra_hal.a>
  "${PC_OPENCV_BUILD}/3rdparty/lib/libittnotify.a"
  libace_napi.z.so
  libhilog_ndk.z.so
  libnative_avscreen_capture.so
  libnative_media_core.so
  z
)
