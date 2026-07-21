# CMakeLists.txt - NDK 构建配置
#
# 编译优化策略：
# - Release 模式：-O3 -ffast-math -funroll-loops
# - NEON 向量化：自动启用（ARMv7/ARMv8）
# - LTO（链接时优化）：可选启用
#
# 性能预期：
# - arm64-v8a：128×128 @ σ=12 ≈ 0.3 ms（Pixel 7）
# - armeabi-v7a：128×128 @ σ=12 ≈ 0.6 ms（Snapdragon 660）

cmake_minimum_required(VERSION 3.18.1)

project("nativegauss")

# C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NEON 向量化支持（所有构建类型）
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfloat-abi=softfp")
    add_definitions(-D__ARM_NEON__)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8 默认支持 NEON
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
    add_definitions(-D__ARM_NEON__)
endif()

# 编译选项（Release 模式）
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    # 激进优化
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffast-math")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -funroll-loops")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -fomit-frame-pointer")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -fno-exceptions")

    # LTO（链接时优化）- 可选，增加编译时间但提升性能 5-10%
    # set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)

    message(STATUS "Release build with optimizations: ${CMAKE_CXX_FLAGS_RELEASE}")
endif()

# Debug 模式
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O0 -g")
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -DDEBUG")
    message(STATUS "Debug build: ${CMAKE_CXX_FLAGS_DEBUG}")
endif()

# 源文件
add_library(
    nativegauss
    SHARED
    native-lib.cpp
    gauss_iir.cpp
    gauss_iir_neon.cpp
    boxblur.cpp
    chromatic_aberration.cpp
)

# 包含目录
target_include_directories(
    nativegauss
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# 链接库
target_link_libraries(
    nativegauss
    jnigraphics  # AndroidBitmap API
    log          # __android_log_print
    m            # 数学库（exp, sqrt 等）
)

# 编译定义
target_compile_definitions(
    nativegauss
    PRIVATE
    ANDROID
)

# 警告选项
target_compile_options(
    nativegauss
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# 输出信息
message(STATUS "Building nativegauss for ${ANDROID_ABI}")
message(STATUS "CMake build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ standard: ${CMAKE_CXX_STANDARD}")

