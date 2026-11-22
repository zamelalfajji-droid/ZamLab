cmake_minimum_required(VERSION 3.22.1)
project("zamlab_native")

add_library(zamlab_native SHARED
        native-lib.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(zamlab_native
        ${log-lib}
        ${jnigraphics-lib}
)
