cmake_minimum_required(VERSION 3.22.1)
project(shima_analyzer C)

# The pinned source-only archive avoids network access and avoids checking 25 MB
# of generated parser C files into Git. Extract separately for each ABI build.
set(VENDOR_DIR "${CMAKE_CURRENT_BINARY_DIR}/vendor")
set(ARCHIVE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/parsers-0.25.0-java-0.23.5-kotlin-1.1.0.tar.gz")
if(NOT EXISTS "${VENDOR_DIR}/kotlin/src/parser.c")
    file(MAKE_DIRECTORY "${VENDOR_DIR}")
    execute_process(COMMAND "${CMAKE_COMMAND}" -E tar xzf "${ARCHIVE}"
                    WORKING_DIRECTORY "${VENDOR_DIR}" RESULT_VARIABLE EXTRACT_RESULT)
    if(NOT EXTRACT_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot unpack pinned parser sources: ${EXTRACT_RESULT}")
    endif()
endif()

add_library(tree_sitter_java OBJECT "${VENDOR_DIR}/java/src/parser.c")
target_include_directories(tree_sitter_java PRIVATE "${VENDOR_DIR}/java/src")
add_library(tree_sitter_kotlin OBJECT "${VENDOR_DIR}/kotlin/src/parser.c" "${VENDOR_DIR}/kotlin/src/scanner.c")
target_include_directories(tree_sitter_kotlin PRIVATE "${VENDOR_DIR}/kotlin/src")
add_library(shima_analyzer SHARED native_bridge.c
    "${VENDOR_DIR}/tree-sitter/lib/src/lib.c"
    $<TARGET_OBJECTS:tree_sitter_java> $<TARGET_OBJECTS:tree_sitter_kotlin>)
target_include_directories(shima_analyzer PRIVATE
    "${VENDOR_DIR}/tree-sitter/lib/include" "${VENDOR_DIR}/tree-sitter/lib/src")
set_target_properties(shima_analyzer tree_sitter_java tree_sitter_kotlin PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED YES POSITION_INDEPENDENT_CODE YES)
