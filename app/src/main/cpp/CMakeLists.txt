cmake_minimum_required(VERSION 3.22)
project(stress LANGUAGES C CXX ASM)

add_library(stress SHARED
    jni_bridge.cpp
    cpu/cpu_load.cpp
    cpu/kernel_table.cpp
    cpu/operands.cpp
    cpu/kernels_fp.S
    cpu/kernels_int.S
    cpu/kernels_mem.S
    sense/sysfs_reader.cpp
    gpu/gpu_load.cpp
    gpu/scene_renderer.cpp
    gpu/vk.cpp
)

# Shaders: compiled by the NDK's glslc and embedded as uint32_t arrays, so the
# library carries its SPIR-V and there are no assets to load at run time.
if(CMAKE_HOST_WIN32)
    set(GLSLC "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc.exe")
else()
    set(GLSLC "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
endif()
set(SHADER_OUT "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${SHADER_OUT}")

# Every shader is rebuilt when a shared include changes.
file(GLOB SHADER_INCLUDES "${CMAKE_CURRENT_SOURCE_DIR}/gpu/shaders/*.glsl")

function(add_shader source name symbol)
    set(spv "${SHADER_OUT}/${name}.spv")
    set(header "${SHADER_OUT}/${name}.h")
    add_custom_command(
        OUTPUT "${header}"
        COMMAND "${GLSLC}" --target-env=vulkan1.2 -O -Werror ${ARGN} "${CMAKE_CURRENT_SOURCE_DIR}/gpu/shaders/${source}" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}" -DINPUT=${spv} -DOUTPUT=${header} -DSYMBOL=${symbol}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/gpu/shaders/${source}" ${SHADER_INCLUDES}
                "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
        VERBATIM)
    target_sources(stress PRIVATE "${header}")
endfunction()

add_shader(alu.comp alu_fp32_comp kAluFp32CompSpirv)
add_shader(alu.comp alu_fp16_comp kAluFp16CompSpirv -DFP16)
add_shader(texture.comp texture_comp kTextureCompSpirv)
add_shader(bandwidth.comp bandwidth_comp kBandwidthCompSpirv)
add_shader(fullscreen.vert fullscreen_vert kFullscreenVertSpirv)
add_shader(blend.frag blend_frag kBlendFragSpirv)
add_shader(preview.frag preview_frag kPreviewFragSpirv)
add_shader(pool_scene.frag pool_scene_frag kPoolSceneFragSpirv)
add_shader(taa.frag taa_frag kTaaFragSpirv)
add_shader(bloom_down.frag bloom_down_frag kBloomDownFragSpirv)
add_shader(bloom_up.frag bloom_up_frag kBloomUpFragSpirv)
add_shader(dof.frag dof_frag kDofFragSpirv)
# The forest, and the passes that follow the camera built again for it.
add_shader(forest_geometry.vert forest_geometry_vert kForestGeometryVertSpirv)
add_shader(forest_geometry.frag forest_geometry_frag kForestGeometryFragSpirv)
add_shader(forest_sky.frag forest_sky_frag kForestSkyFragSpirv)
add_shader(taa.frag taa_forest_frag kTaaForestFragSpirv -DSCENE_FOREST)
add_shader(dof.frag dof_forest_frag kDofForestFragSpirv -DSCENE_FOREST)
add_shader(final.frag final_forest_frag kFinalForestFragSpirv -DSCENE_FOREST)
add_shader(final.frag final_frag kFinalFragSpirv)
add_shader(particles.comp particles_comp kParticlesCompSpirv)
add_shader(particles.vert particles_vert kParticlesVertSpirv)
add_shader(particles.frag particles_frag kParticlesFragSpirv)
add_shader(water_step.comp water_step_comp kWaterStepCompSpirv)
add_shader(water_surface.comp water_surface_comp kWaterSurfaceCompSpirv)

target_compile_features(stress PRIVATE cxx_std_20)
target_include_directories(stress PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR})

# The same optimisation in every build type: instrumented tests run the debug
# build and must exercise the code the release build ships.
target_compile_options(stress PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-O2 -Wall -Wextra -Wshadow -Wconversion -Werror -fno-exceptions -fno-rtti>
)

target_link_libraries(stress PRIVATE log android vulkan)
