cmake_minimum_required(VERSION 3.22)
project(stress LANGUAGES C CXX ASM)

add_library(stress SHARED
    jni_bridge.cpp
    cpu/cpu_load.cpp
    cpu/kernel_table.cpp
    cpu/operands.cpp
    cpu/kernels_fp.S
    cpu/kernels_int.S
    cpu/kernels_mem.S
    sense/sysfs_reader.cpp
)

target_compile_features(stress PRIVATE cxx_std_20)
target_include_directories(stress PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# The same optimisation in every build type: instrumented tests run the debug
# build and must exercise the code the release build ships.
target_compile_options(stress PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-O2 -Wall -Wextra -Wshadow -Wconversion -Werror -fno-exceptions -fno-rtti>
)

target_link_libraries(stress PRIVATE log)
