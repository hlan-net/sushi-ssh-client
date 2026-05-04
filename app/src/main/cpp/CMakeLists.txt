cmake_minimum_required(VERSION 3.22.1)
project(sushi-pty C)

add_library(sushi-pty SHARED sushi-pty.c)
target_link_libraries(sushi-pty log)
