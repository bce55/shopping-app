cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

# whisper.cpp lives in a git submodule at <project root>/third_party/whisper.cpp
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_DIR}. Run: git submodule update --init")
endif()

# Static libs folded into one .so. No examples/tests/server.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
# ggml's own thread pool. Avoids packaging libomp.so from the NDK.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
# Optional speed-up for phones with ARMv8.2+ cores (Galaxy S10 / A-series 2019 and newer).
# Crashes with SIGILL on older cores, so it is off by default.
# set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log android)
target_compile_options(whisper_jni PRIVATE -O3)
