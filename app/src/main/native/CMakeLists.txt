cmake_minimum_required(VERSION 3.22)
project(pdf_builder C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 最大最適化 + サイズ重視 (板書アプリは速度より圧縮率が重要)
add_compile_options(
    -Os
    -Wall
    -Wextra
    -Wpedantic
    -ffunction-sections
    -fdata-sections
)

add_link_options(-Wl,--gc-sections)

add_library(
    pdf_builder
    SHARED
    src/vfs.c
    src/pdf_builder.c
    src/pdf_builder_jni.c
)

target_include_directories(pdf_builder PRIVATE include)

# Android NDK 同梱の zlib と log を使用
find_library(zlib-lib z)
find_library(log-lib  log)

target_link_libraries(
    pdf_builder
    ${zlib-lib}
    ${log-lib}
)