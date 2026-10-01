#version 450
// What the screen shows while a power mode runs (the burner has the GPU, no
// scene): the benchmark's own instrument, a load gauge on a faint grid with a
// slow scan line. Its scale is a 240-degree arc from lower left to lower
// right, filled up to the burner's share of the frame.
layout(push_constant) uniform Params {
    vec2 resolution;
    float time;
    float load;
} params;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

const vec3 ACCENT = vec3(1.0, 0.42, 0.13);  // StressColors.Accent, linear-ish

void main() {
    vec2 frag = gl_FragCoord.xy;
    // Image coordinates with y up (Vulkan's window y runs down), height 1.
    vec2 uv = vec2(frag.x - 0.5 * params.resolution.x, 0.5 * params.resolution.y - frag.y) / params.resolution.y;

    vec2 g = abs(fract(frag / 48.0) - 0.5);
    vec3 c = vec3(0.035, 0.04, 0.045) + 0.035 * smoothstep(0.47, 0.5, max(g.x, g.y));
    float scan = fract(params.time * 0.15);
    c += ACCENT * 0.05 * exp(-abs(frag.y / params.resolution.y - scan) * 60.0);

    float reading = clamp(params.load, 0.0, 1.0) * 0.9 + 0.05 + 0.01 * sin(params.time * 3.0);
    float r = length(uv);
    float angle = degrees(atan(uv.y, uv.x));
    if (angle < -150.0) angle += 360.0;
    float along = (210.0 - angle) / 240.0;  // 0 at the lower left, 1 at the lower right
    bool onScale = along >= 0.0 && along <= 1.0;
    float band = smoothstep(0.011, 0.005, abs(r - 0.17));
    if (onScale) {
        c = mix(c, ACCENT * (along <= reading ? 1.0 : 0.22), band);
        // A tick every tenth of the scale, outside the band.
        float tick = smoothstep(0.004, 0.0, abs(fract(along * 10.0 + 0.5) - 0.5) / 10.0 * 240.0 * 0.0174533 * r)
                     * step(0.188, r) * step(r, 0.206);
        c = mix(c, vec3(0.75), tick);
    }
    // The needle and its hub.
    float pointing = radians(210.0 - 240.0 * reading);
    vec2 dir = vec2(cos(pointing), sin(pointing));
    float across = abs(dot(uv, vec2(-dir.y, dir.x)));
    float needle = smoothstep(0.006, 0.002, across) * step(0.0, dot(uv, dir)) * step(r, 0.12);
    c = mix(c, ACCENT, max(needle, smoothstep(0.018, 0.012, r)));
    color = vec4(c, 1.0);
}
