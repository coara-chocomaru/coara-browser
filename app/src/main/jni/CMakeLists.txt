cmake_minimum_required(VERSION 3.10.2)
project(browseropt)

find_package(OpenSSL REQUIRED)
find_package(PNG REQUIRED) 
find_package(ZLIB REQUIRED) 

add_library(browseropt SHARED browseropt.cpp)

target_include_directories(browseropt PRIVATE
    ${OPENSSL_INCLUDE_DIR}
    ${PNG_INCLUDE_DIRS}
    ${ZLIB_INCLUDE_DIRS}
)

target_link_libraries(browseropt
    android 
    log
    ${OPENSSL_LIBRARIES}
    ${PNG_LIBRARIES}
    ${ZLIB_LIBRARIES}
)


target_compile_options(browseropt PRIVATE -O3 -march=armv8-a+simd -fPIC -Wall -Wextra -Wno-unused-parameter)
target_compile_definitions(browseropt PRIVATE -fno-exceptions -fno-rtti)
