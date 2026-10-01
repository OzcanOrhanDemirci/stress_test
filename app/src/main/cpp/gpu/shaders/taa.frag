#version 450
// Temporal accumulation: each pixel's world point is found from its distance,
// projected into the previous frame's camera, and that frame's history is
// blended in. The history is clipped to the spread of the current 3x3
// neighbourhood (in YCoCg), so moving things leave no ghosts.
#extension GL_GOOGLE_include_directive : require

#include "frame_params.glsl"
#include "scene.glsl"

layout(set = 0, binding = 0) uniform sampler2D current;
layout(set = 0, binding = 1) uniform sampler2D currentDistance;
layout(set = 0, binding = 2) uniform sampler2D history;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Compresses bright samples so one firefly cannot dominate the average.
vec3 tame(vec3 c) {
    return c / (1.0 + max(c.r, max(c.g, c.b)));
}

vec3 untame(vec3 c) {
    return c / max(1.0 - max(c.r, max(c.g, c.b)), 1e-4);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 last = ivec2(params.resolution) - 1;
    vec3 now = tame(sanitize(texelFetch(current, pixel, 0).rgb));

    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            vec3 s = toYCoCg(tame(sanitize(texelFetch(current, clamp(pixel + ivec2(x, y), ivec2(0), last), 0).rgb)));
            m1 += s;
            m2 += s * s;
        }
    }
    vec3 mean = m1 / 9.0;
    vec3 sigma = sqrt(max(m2 / 9.0 - mean * mean, vec3(0.0)));
    vec3 low = mean - 1.25 * sigma;
    vec3 high = mean + 1.25 * sigma;

    // Where was this point last frame?
    float dist = texelFetch(currentDistance, pixel, 0).r;
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    vec2 uv = (fragCoord - 0.5 * params.resolution) / params.resolution.y;
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 world = ro + normalize(uv.x * rt + uv.y * up + FOCAL * fw) * dist;
    camera(params.previousTime, ro, fw, rt, up);
    vec3 v = world - ro;
    float z = dot(v, fw);
    vec2 previous = vec2(dot(v, rt), dot(v, up)) / max(z, 1e-4) * FOCAL * params.resolution.y + 0.5 * params.resolution;
    vec2 texcoord = vec2(previous.x, params.resolution.y - previous.y) / params.resolution;
    bool inside = all(greaterThanEqual(texcoord, vec2(0.0))) && all(lessThanEqual(texcoord, vec2(1.0)));
    // A cut to a new shot leaves nothing worth reprojecting.
    bool sameShot = shotAt(params.time) == shotAt(params.previousTime);
    bool valid = params.frame > 0u && sameShot && z > 0.0 && inside;

    vec3 result = now;
    if (valid) {
        vec3 past = toYCoCg(tame(sanitize(texture(history, texcoord).rgb)));
        past = clamp(past, low, high);
        result = mix(fromYCoCg(past), now, params.blend);
    }
    color = vec4(sanitize(untame(result)), 1.0);
}
