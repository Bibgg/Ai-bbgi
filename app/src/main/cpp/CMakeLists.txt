add_library(aibbgi_jni SHARED NativeBridge.cpp)
target_include_directories(aibbgi_jni PRIVATE ${CMAKE_SOURCE_DIR}/include)
target_link_libraries(aibbgi_jni PRIVATE llama android log)
