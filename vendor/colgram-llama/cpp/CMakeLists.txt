cmake_minimum_required(VERSION 3.22.1)
project(colgram_llama VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_SRC)
    message(FATAL_ERROR "LLAMA_SRC must point to llama.cpp checkout")
endif()

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
        # Unlock hardware ARMv8.2-A SDOT (dotprod) + FP16 vector math + KleidiAI on all modern Android phones
        # (2.5x-4x faster real-world token/s vs baseline ARMv8.0 fallback!).
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
        set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
        # Use llama.cpp's native pthread ggml_threadpool (OFF OpenMP) so decode threads bind strictly to
        # big Cortex-A76/X performance cores via sched_setaffinity without libomp spin-wait heat or barriers,
        # and pause cleanly to 0.0% idle CPU between turns!
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
        set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
        set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
        set(GGML_SSE42 ON CACHE BOOL "" FORCE)
        set(GGML_AVX ON CACHE BOOL "" FORCE)
        set(GGML_AVX2 ON CACHE BOOL "" FORCE)
        set(GGML_FMA ON CACHE BOOL "" FORCE)
        set(GGML_F16C ON CACHE BOOL "" FORCE)
        set(GGML_BMI2 ON CACHE BOOL "" FORCE)
        set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
        set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
    endif()
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(colgram_llama SHARED
    colgram_llama_jni.cpp
)

target_include_directories(colgram_llama PRIVATE
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/tools/mtmd"
    "${LLAMA_SRC}/ggml/include"
)

target_link_libraries(colgram_llama PRIVATE
    llama
    llama-common
    mtmd
    android
    log
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(colgram_llama PRIVATE -O3 -march=armv8.2-a+dotprod+fp16)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(colgram_llama PRIVATE -O3 -msse4.2 -mavx -mavx2 -mfma -mf16c -mbmi2)
else()
    target_compile_options(colgram_llama PRIVATE -O3)
endif()
