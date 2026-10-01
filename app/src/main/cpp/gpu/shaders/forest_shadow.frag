#version 450
// The sun's shadow map: depth only. Cards are cut to their needle and leaf
// outlines, so the light comes through between them.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(location = 2) in vec2 uv;
layout(location = 3) flat in uint material;
layout(location = 5) flat in float variant;

void main() {
    if (material == M_NEEDLES && needleCover(uv, variant) < 0.5) discard;
    if (material == M_LEAVES && leafCover(uv, variant) < 0.5) discard;
    if (material == M_TWIGS && twigCover(uv, variant) < 0.5) discard;
    if (material == M_FERN && fernCover(uv, variant) < 0.5) discard;
}
