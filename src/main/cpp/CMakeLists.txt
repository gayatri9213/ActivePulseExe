cmake_minimum_required(VERSION 3.16)
project(WindowsUserDetector)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find JNI
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

# Create shared library
add_library(WindowsUserDetector SHARED WindowsUserDetector.cpp)

# Link Windows libraries
target_link_libraries(WindowsUserDetector 
    ${JNI_LIBRARIES}
    wtsapi32
    advapi32
)

# Set output directory
set_target_properties(WindowsUserDetector PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../resources/native"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../resources/native"
)

# Compiler-specific options
if(MSVC)
    target_compile_definitions(WindowsUserDetector PRIVATE _CRT_SECURE_NO_WARNINGS)
    set_target_properties(WindowsUserDetector PROPERTIES
        COMPILE_FLAGS "/W4"
    )
endif()

# Copy library to target directory after build
add_custom_command(TARGET WindowsUserDetector POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy 
    "$<TARGET_FILE:WindowsUserDetector>"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../resources/native/WindowsUserDetector.dll"
)
