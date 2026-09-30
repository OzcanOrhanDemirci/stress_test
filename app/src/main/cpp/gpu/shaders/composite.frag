#version 450
// Scales the scene up to the screen, with a touch of lens chromatic aberration.
layout(set = 0, binding = 0) uniform sampler2D scene;

layout(push_constant) uniform Params {
    vec2 resolution;
    float time;
    float load;
} params;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy / params.resolution;
    vec2 shift = (uv - 0.5) * 0.0035;
    color = vec4(texture(scene, uv + shift).r, texture(scene, uv).g, texture(scene, uv - shift).b, 1.0);
}
