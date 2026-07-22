cmake_minimum_required(VERSION 3.10.2)
project(atrumcodec C)

add_library(atrumcodec SHARED tamper.c)

set_target_properties(atrumcodec PROPERTIES COMPILE_FLAGS "-O2 -fvisibility=hidden")
