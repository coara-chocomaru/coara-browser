cmake_minimum_required(VERSION 3.10.2)
project(browseropt)

add_library(browseropt SHARED browseropt.cpp)

target_include_directories(browseropt PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})


find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(browseropt
    ${log-lib}
    ${android-lib}
)

target_compile_options(browseropt PRIVATE -O3 -march=armv8-a+simd -fPIC -Wall -Wextra -Wno-unused-parameter)
target_compile_options(browseropt PRIVATE -fno-exceptions -fno-rtti)
