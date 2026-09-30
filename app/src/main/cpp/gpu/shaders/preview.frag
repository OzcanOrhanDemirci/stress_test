#version 450
// What the screen shows until the reactor scene exists: a pulsing Cherenkov ring.
layout(push_constant) uniform Params {
    vec2 resolution;
    float time;
    float load;
} params;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * params.resolution) / params.resolution.y;
    float r = length(uv);
    float ring = 0.018 / (abs(r - 0.24 - 0.015 * sin(params.time * 2.1)) + 0.012);
    float waves = 0.5 + 0.5 * sin(48.0 * r - params.time * 5.0);
    vec3 cherenkov = vec3(0.22, 0.78, 1.0);
    vec3 c = cherenkov * ring * (0.55 + 0.45 * waves) * (0.6 + 0.4 * params.load) * exp(-1.8 * r);
    color = vec4(c / (1.0 + c), 1.0);
}
