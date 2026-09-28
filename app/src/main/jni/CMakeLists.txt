cmake_minimum_required(VERSION 3.22.1)
project("rtmpnative")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -Wall")

add_library(rtmpnative SHARED
    rtmp_multiplexer.cpp
    native_interface.cpp
)

find_library(log-lib log)
target_link_libraries(rtmpnative ${log-lib})
