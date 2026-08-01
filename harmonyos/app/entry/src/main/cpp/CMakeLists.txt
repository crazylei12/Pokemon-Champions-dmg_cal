cmake_minimum_required(VERSION 3.5.0)
project(pcbridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pcbridge SHARED napi_init.cpp)

target_link_libraries(pcbridge PUBLIC
  libace_napi.z.so
  libhilog_ndk.z.so
)
