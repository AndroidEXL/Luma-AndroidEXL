cmake_minimum_required(VERSION 3.22.1)
project(avr_android)

add_library(avr_android SHARED avr_android.cpp)

target_compile_features(avr_android PRIVATE cxx_std_17)

target_link_libraries(avr_android
    android
    log
)
