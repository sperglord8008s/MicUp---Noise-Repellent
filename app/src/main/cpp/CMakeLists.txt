cmake_minimum_required(VERSION 3.22)
project(micplugin_native VERSION 1.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── FetchContent for CLAP headers ─────────────────────────────────────────────
include(FetchContent)
set(MICUP_THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party")

if(EXISTS "${MICUP_THIRD_PARTY_DIR}/clap/include/clap/clap.h")
    FetchContent_Declare(clap SOURCE_DIR "${MICUP_THIRD_PARTY_DIR}/clap")
else()
    FetchContent_Declare(
        clap
        GIT_REPOSITORY https://github.com/free-audio/clap.git
        GIT_TAG        1.2.1
        GIT_SHALLOW    TRUE
        GIT_PROGRESS   FALSE
    )
endif()
FetchContent_MakeAvailable(clap)

if(EXISTS "${MICUP_THIRD_PARTY_DIR}/lv2/include/lv2/core/lv2.h")
    FetchContent_Declare(lv2_headers SOURCE_DIR "${MICUP_THIRD_PARTY_DIR}/lv2")
else()
    FetchContent_Declare(
        lv2_headers
        GIT_REPOSITORY https://github.com/lv2/lv2.git
        GIT_TAG        v1.18.10
        GIT_SHALLOW    TRUE
        GIT_PROGRESS   FALSE
    )
endif()
FetchContent_MakeAvailable(lv2_headers)

# Build Oboe from source with this project's NDK. The prebuilt Prefab AAR was
# produced with NDK 21 and AGP rejects it for this ARM64/NDK 26 build.
set(OBOE_BUILD_TESTING OFF CACHE BOOL "Do not build Oboe tests" FORCE)
set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "Do not build Oboe examples" FORCE)
if(EXISTS "${MICUP_THIRD_PARTY_DIR}/oboe/CMakeLists.txt")
    FetchContent_Declare(oboe SOURCE_DIR "${MICUP_THIRD_PARTY_DIR}/oboe")
else()
    FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG        1.8.1
        GIT_SHALLOW    TRUE
        GIT_PROGRESS   FALSE
    )
endif()
FetchContent_MakeAvailable(oboe)

# Noise Repellent's DSP engine.  The desktop LV2 binary cannot run on
# Android/ARM, so build the portable C library directly into MicUp instead.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static dependencies" FORCE)
set(ENABLE_AVX OFF CACHE BOOL "AVX is not available on Android ARM" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "Do not build dependency tests" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "Do not build dependency examples" FORCE)
set(SPECBLEACH_BUILD_EXTRAS OFF CACHE BOOL "Mono processing is sufficient" FORCE)
if(EXISTS "${MICUP_THIRD_PARTY_DIR}/libspecbleach/CMakeLists.txt")
    FetchContent_Declare(
        libspecbleach SOURCE_DIR "${MICUP_THIRD_PARTY_DIR}/libspecbleach")
else()
    FetchContent_Declare(
        libspecbleach
        GIT_REPOSITORY https://github.com/lucianodato/libspecbleach.git
        GIT_TAG        ab4ae8b4be74f5c52c5a3386a6d9bbea29fbd129
        GIT_SHALLOW    FALSE
        GIT_PROGRESS   FALSE
    )
endif()
FetchContent_MakeAvailable(libspecbleach)

# VST3 headers bundled locally — no FetchContent needed


# Oboe is built above through FetchContent.
# ─── Native library ───────────────────────────────────────────────────────────
add_library(micplugin_native SHARED
    AudioEngine.cpp
    PluginHost.cpp
    ClapHost.cpp
    Lv2Host.cpp
    Vst3Bridge.cpp
    TtlParser.cpp
    vst3/vst3_iids.cpp
)

target_include_directories(micplugin_native PRIVATE
    ${clap_SOURCE_DIR}/include
    ${lv2_headers_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/vst3/include
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/vst3
)

target_compile_options(micplugin_native PRIVATE
    -O2
    -ffast-math
    -DANDROID
    -Wall
    -Wno-unused-parameter
)

target_link_libraries(micplugin_native
    libspecbleach::libspecbleach
    oboe
    log
    android
    dl        # dlopen for plugin loading
    OpenSLES  # OpenSL ES fallback
)
