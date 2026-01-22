cmake_minimum_required(VERSION 3.10)
project(mquickjs C)

set(CMAKE_C_STANDARD 99)

# 配置选项
option(CONFIG_PROFILE "Enable profiling" OFF)
option(CONFIG_X86_32 "32-bit x86 architecture" OFF)
option(CONFIG_ARM32 "32-bit ARM architecture" OFF)
option(CONFIG_WIN32 "Windows platform" OFF)
option(CONFIG_SOFTFLOAT "Soft float support" OFF)
option(CONFIG_ASAN "AddressSanitizer" OFF)
option(CONFIG_GPROF "gprof profiling" OFF)
option(CONFIG_SMALL "Optimize for size" ON)
option(CONFIG_WERROR "Treat warnings as errors" OFF)

# 编译器设置
set(HOST_C_FLAGS_STRING "-Wall -g -D_GNU_SOURCE -fno-math-errno -fno-trapping-math")

# 初始化编译选项列表
set(COMMON_COMPILE_OPTIONS -Wall -g -D_GNU_SOURCE -fno-math-errno -fno-trapping-math)

if(CONFIG_WERROR)
    list(APPEND COMMON_COMPILE_OPTIONS -Werror)
    string(APPEND HOST_C_FLAGS_STRING " -Werror")
endif()

if(CONFIG_ARM32)
    list(APPEND COMMON_COMPILE_OPTIONS -mthumb)
    set(MQJS_BUILD_FLAGS "-m32")
endif()

if(CONFIG_SMALL)
    list(APPEND COMMON_COMPILE_OPTIONS -Os)
else()
    list(APPEND COMMON_COMPILE_OPTIONS -O2)
endif()

if(CONFIG_SOFTFLOAT)
    list(APPEND COMMON_COMPILE_OPTIONS -msoft-float -DUSE_SOFTFLOAT)
    string(APPEND HOST_C_FLAGS_STRING " -msoft-float -DUSE_SOFTFLOAT")
endif()

if(CONFIG_ASAN)
    list(APPEND COMMON_COMPILE_OPTIONS -fsanitize=address -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address -fno-omit-frame-pointer)
    string(APPEND HOST_C_FLAGS_STRING " -fsanitize=address -fno-omit-frame-pointer")
endif()

if(CONFIG_GPROF OR CONFIG_PROFILE)
    list(APPEND COMMON_COMPILE_OPTIONS -p)
    add_link_options(-p)
    string(APPEND HOST_C_FLAGS_STRING " -p")
endif()

if(CONFIG_X86_32)
    list(APPEND COMMON_COMPILE_OPTIONS -m32)
    add_link_options(-m32)
    set(MQJS_BUILD_FLAGS "-m32")
    string(APPEND HOST_C_FLAGS_STRING " -m32")
endif()

# 将 HOST_C_FLAGS_STRING 拆分为列表
string(REPLACE " " ";" HOST_C_FLAGS_LIST "${HOST_C_FLAGS_STRING}")

# 源文件
set(COMMON_SOURCES
    mquickjs.c
    dtoa.c
    libm.c
    cutils.c
)

set(MQJS_SOURCES
    mqjs.c
    readline_tty.c
    readline.c
)

set(EXAMPLE_SOURCES
    example.c
)

# 主机工具构建
set(HOST_C_COMPILER ${CMAKE_C_COMPILER})

# 1. 构建 mquickjs_build.host.o
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o
    COMMAND ${HOST_C_COMPILER} ${HOST_C_FLAGS_LIST} -c -o ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o ${CMAKE_CURRENT_SOURCE_DIR}/mquickjs_build.c
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/mquickjs_build.c
    VERBATIM
)

# 2. 构建 mqjs_stdlib.host.o
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.host.o
    COMMAND ${HOST_C_COMPILER} ${HOST_C_FLAGS_LIST} -c -o ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.host.o ${CMAKE_CURRENT_SOURCE_DIR}/mqjs_stdlib.c
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/mqjs_stdlib.c
    VERBATIM
)

# 3. 构建 mqjs_stdlib 工具
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe
    COMMAND ${HOST_C_COMPILER} ${HOST_C_FLAGS_LIST} -o ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.host.o ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o
    DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.host.o ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o
    VERBATIM
)

# 4. 生成 mquickjs_atom.h
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_atom.h
    COMMAND cmd /c "${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe" -a ${MQJS_BUILD_FLAGS} > "${CMAKE_CURRENT_BINARY_DIR}/mquickjs_atom.h"
    DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe
    VERBATIM
)

# 5. 生成 mqjs_stdlib.h
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.h
    COMMAND cmd /c "${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe" ${MQJS_BUILD_FLAGS} > "${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.h"
    DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe
    VERBATIM
)

# 6. 构建 example_stdlib.host.o
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.host.o
    COMMAND ${HOST_C_COMPILER} ${HOST_C_FLAGS_LIST} -c -o ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.host.o ${CMAKE_CURRENT_SOURCE_DIR}/example_stdlib.c
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/example_stdlib.c
    VERBATIM
)

# 7. 构建 example_stdlib 工具
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.exe
    COMMAND ${HOST_C_COMPILER} ${HOST_C_FLAGS_LIST} -o ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.exe ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.host.o ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o
    DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.host.o ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o
    VERBATIM
)

# 8. 生成 example_stdlib.h
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.h
    COMMAND cmd /c "${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.exe" ${MQJS_BUILD_FLAGS} > "${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.h"
    DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.exe
    VERBATIM
)

# 构建mquickjs静态库
add_library(mquickjs_static STATIC ${COMMON_SOURCES})
target_compile_options(mquickjs_static PRIVATE ${COMMON_COMPILE_OPTIONS})
target_include_directories(mquickjs_static PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})

# 构建mquickjs动态库
add_library(mquickjs_shared SHARED ${COMMON_SOURCES})
target_compile_options(mquickjs_shared PRIVATE ${COMMON_COMPILE_OPTIONS})
target_include_directories(mquickjs_shared PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(mquickjs_shared PROPERTIES OUTPUT_NAME "mquickjs")

# 构建目标
add_executable(mqjs ${MQJS_SOURCES})
add_executable(example ${EXAMPLE_SOURCES})

# 应用编译选项
target_compile_options(mqjs PRIVATE ${COMMON_COMPILE_OPTIONS})
target_compile_options(example PRIVATE ${COMMON_COMPILE_OPTIONS})

# 设置头文件目录
target_include_directories(mqjs PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(example PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})

# 链接库
if(NOT WIN32)
    target_link_libraries(mquickjs_static m)
    target_link_libraries(mquickjs_shared m)
    target_link_libraries(mqjs mquickjs_static m)
    target_link_libraries(example mquickjs_static m)
else()
    target_link_libraries(mqjs mquickjs_static)
    target_link_libraries(example mquickjs_static)
endif()

# 确保生成的头文件在编译前被创建
add_custom_target(generate_all_headers ALL
    DEPENDS 
        ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_atom.h 
        ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.h 
        ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.h
)

# 添加依赖关系
add_dependencies(mquickjs_static generate_all_headers)
add_dependencies(mquickjs_shared generate_all_headers)
add_dependencies(mqjs generate_all_headers)
add_dependencies(example generate_all_headers)

# 测试
add_custom_target(test
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_closure.js
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_language.js
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_loop.js
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_builtin.js
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs -o test_builtin.bin ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_builtin.js
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs -b test_builtin.bin
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/example ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_rect.js
    DEPENDS mqjs example
    WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)

# 微基准测试
add_custom_target(microbench
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs ${CMAKE_CURRENT_SOURCE_DIR}/tests/microbench.js
    DEPENDS mqjs
    WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)

# octane 测试
add_custom_target(octane
    COMMAND ${CMAKE_CURRENT_BINARY_DIR}/mqjs --memory-limit 256M ${CMAKE_CURRENT_SOURCE_DIR}/tests/octane/run.js
    DEPENDS mqjs
    WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)

# 大小检查
if(WIN32)
    # Windows平台使用dumpbin命令
    add_custom_target(size
        COMMAND dumpbin /SIZE ${CMAKE_CURRENT_BINARY_DIR}/mqjs.exe
        DEPENDS mqjs
        WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    )
else()
    # Unix-like平台使用size命令
    add_custom_target(size
        COMMAND size $<TARGET_FILE:mqjs> $<TARGET_OBJECTS:mqjs>
        DEPENDS mqjs
        WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    )
endif()

# 清理
set_directory_properties(PROPERTIES ADDITIONAL_CLEAN_FILES 
    "${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.exe ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.exe 
    ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_atom.h ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.h 
    ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.h ${CMAKE_CURRENT_BINARY_DIR}/mquickjs_build.host.o 
    ${CMAKE_CURRENT_BINARY_DIR}/mqjs_stdlib.host.o ${CMAKE_CURRENT_BINARY_DIR}/example_stdlib.host.o 
    ${CMAKE_CURRENT_BINARY_DIR}/test_builtin.bin"
)
