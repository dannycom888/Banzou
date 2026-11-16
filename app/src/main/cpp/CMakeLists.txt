cmake_minimum_required(VERSION 3.22.1)
project(banzou_lame C)

# Vendored LAME 3.100 (LGPL-2.0-or-later): libmp3lame + mpglib sources.
file(GLOB LAME_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/mpglib/*.c
)
# The x86 SIMD helpers and the decoder test main are not part of the library.
list(REMOVE_ITEM LAME_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/mpglib/main.c
)

add_library(banzou_lame SHARED
    lame_jni.c
    ${LAME_SOURCES}
)

target_include_directories(banzou_lame PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/include
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/mpglib
)

target_compile_definitions(banzou_lame PRIVATE HAVE_CONFIG_H=1)

find_library(log-lib log)
target_link_libraries(banzou_lame ${log-lib} m)
