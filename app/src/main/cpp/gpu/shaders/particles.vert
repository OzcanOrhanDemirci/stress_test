#version 450
// Projects each particle with the scene's camera and sizes it as a point.
#extension GL_GOOGLE_include_directive : require

#include "particle_params.glsl"
#include "pool.glsl"

layout(location = 0) out vec4 color;
layout(location = 1) out float viewDistance;
layout(location = 2) flat out float kind;

void main() {
    Particle p = loadParticle(uint(gl_VertexIndex));
    kind = p.velocity.w;
    vec3 world = p.position.xyz;
    // Under a flat surface seen from above, depth looks shorter by the refractive index.
    if (kind == KIND_BUBBLE) world.y /= 1.33;

    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 v = world - ro;
    float z = dot(v, fw);
    viewDistance = length(v);
    vec2 uv = vec2(dot(v, rt), dot(v, up)) / max(z, 1e-3) * FOCAL;
    // The scene's pixel is (uv * height + size / 2) with y up; Vulkan's clip
    // space has y down, OpenGL's (the desktop preview) up.
#ifdef VULKAN
    const float clipUp = -1.0;
#else
    const float clipUp = 1.0;
#endif
    gl_Position = z > 0.1 ? vec4(uv.x * 2.0 * params.resolution.y / params.resolution.x, clipUp * uv.y * 2.0, 0.5, 1.0)
                          : vec4(2.0, 2.0, 2.0, 1.0);

    float worldSize = kind == KIND_BUBBLE ? 0.022 : 0.014;
    gl_PointSize = clamp(worldSize * FOCAL * params.resolution.y / max(z, 1e-3), 1.0, 20.0);

    if (kind == KIND_BUBBLE) {
        // Brighter near the core, fading towards the surface.
        float depthGlow = clamp((-p.position.y) / 3.0, 0.15, 1.0);
        color = vec4(CHERENKOV * 1.6 * depthGlow, 1.0);
    } else {
        float lifetime = 3.0 + fract(float(gl_VertexIndex) * 0.618034) * 3.0;
        float fade = smoothstep(0.0, 0.3, p.position.w) * (1.0 - smoothstep(lifetime - 1.0, lifetime, p.position.w));
        vec3 hue = mix(AMBER * 5.0, CHERENKOV * 6.0, fract(float(gl_VertexIndex) * 0.37));
        color = vec4(hue * fade, 1.0);
    }
}
