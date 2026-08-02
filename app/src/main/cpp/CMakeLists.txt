cmake_minimum_required(VERSION 3.22.1)

project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED WHISPER_SOURCE_DIR)
    message(FATAL_ERROR "WHISPER_SOURCE_DIR must point to the official whisper.cpp checkout")
endif()

file(TO_CMAKE_PATH "${WHISPER_SOURCE_DIR}" WHISPER_SOURCE_DIR)
if(NOT EXISTS "${WHISPER_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Invalid WHISPER_SOURCE_DIR: ${WHISPER_SOURCE_DIR}")
endif()

# Build the checked-out official source as a library, without its command line tools.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory("${WHISPER_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/whisper.cpp")

add_library(whisper_jni SHARED whisper_jni.cpp)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(whisper_jni PRIVATE -O3)
endif()

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(whisper_jni PRIVATE
    "${WHISPER_SOURCE_DIR}/include"
)

target_link_libraries(whisper_jni PRIVATE
    whisper
    ${android-lib}
    ${log-lib}
)
