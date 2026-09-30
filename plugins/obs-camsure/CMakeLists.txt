cmake_minimum_required(VERSION 3.28...3.30)

include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)

project(${_name} VERSION ${_version})

option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" OFF)
option(ENABLE_QT "Use Qt functionality" OFF)

include(compilerconfig)
include(defaults)
include(helpers)

add_library(${CMAKE_PROJECT_NAME} MODULE)

find_package(libobs REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::libobs)

if(ENABLE_FRONTEND_API)
  find_package(obs-frontend-api REQUIRED)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::obs-frontend-api)
endif()

if(ENABLE_QT)
  find_package(Qt6 COMPONENTS Widgets Core)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets)
  target_compile_options(
    ${CMAKE_PROJECT_NAME}
    PRIVATE $<$<C_COMPILER_ID:Clang,AppleClang>:-Wno-quoted-include-in-framework-header -Wno-comma>
  )
  set_target_properties(
    ${CMAKE_PROJECT_NAME}
    PROPERTIES AUTOMOC ON AUTOUIC ON AUTORCC ON
  )
endif()

target_sources(${CMAKE_PROJECT_NAME} PRIVATE src/plugin-main.cpp src/video-decoder.cpp src/video-session.cpp)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/.deps/obs-studio-31.1.1/cmake/finders")
find_package(FFmpeg REQUIRED COMPONENTS avcodec avutil)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE FFmpeg::avcodec FFmpeg::avutil)
# The pinned avcodec binary imports these runtime dependencies even though
# CamSure only decodes H.264. Never replace OBS's own FFmpeg DLLs.
set(camsure_codec_dlls)
foreach(dll avcodec-61.dll avutil-59.dll swresample-5.dll libx264-164.dll zlib.dll)
  find_file(camsure_${dll} NAMES ${dll} PATH_SUFFIXES bin REQUIRED)
  list(APPEND camsure_codec_dlls "${camsure_${dll}}")
endforeach()
add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different ${camsure_codec_dlls} "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>")
install(FILES ${camsure_codec_dlls} DESTINATION "obs-camsure/bin/64bit")
foreach(license FFmpeg x264 zlib)
  install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.deps/obs-deps-2025-07-11-x64/licenses/${license}/"
    DESTINATION "obs-camsure/data/licenses/${license}")
endforeach()
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE NOMINMAX)

set_target_properties_plugin(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME ${_name})

target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_17)

option(CAMSURE_BUILD_TESTS "Build the libobs lifecycle integration probe" OFF)
if(CAMSURE_BUILD_TESTS)
  add_executable(camsure-decoder-test tests/decoder.cpp src/video-decoder.cpp)
  target_include_directories(camsure-decoder-test PRIVATE src)
  target_compile_definitions(camsure-decoder-test PRIVATE NOMINMAX)
  target_compile_features(camsure-decoder-test PRIVATE cxx_std_17)
  target_link_libraries(camsure-decoder-test PRIVATE mfplat mf mfuuid wmcodecdspuuid ole32 oleaut32)
  target_link_libraries(camsure-decoder-test PRIVATE FFmpeg::avcodec FFmpeg::avutil)
  add_custom_command(TARGET camsure-decoder-test POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${camsure_codec_dlls} "$<TARGET_FILE_DIR:camsure-decoder-test>")
  add_executable(camsure-lifecycle-test tests/lifecycle.cpp)
  target_link_libraries(camsure-lifecycle-test PRIVATE OBS::libobs)
  target_compile_features(camsure-lifecycle-test PRIVATE cxx_std_17)
endif()
