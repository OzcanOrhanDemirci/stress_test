#version 450
// Rain: RAIN_DROPS streaks falling through a box that follows the camera.
// Nothing is simulated: a drop's place is a pure function of its number and
// the time, wrapped round the box, so the rain never runs out. Each drop is
// drawn as a thin quad along its motion over the shutter time, at least a
// pixel wide; a thinner drop gives up brightness instead. It shines where a
// sunbeam crosses it (the shadow map), dim grey elsewhere.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(set = 0, binding = 0) uniform sampler2DShadow sunDepth;

layout(location = 0) out vec3 colour;
layout(location = 1) out vec2 local;          // along the streak 0..1, across -1..1
layout(location = 2) out float viewDistance;

const vec3 BOX = vec3(18.0, 14.0, 18.0);
const float SHUTTER = 0.03;  // seconds the streak stands for
const float DROP = 0.003;    // metres across

vec4 clipOf(vec3 p, vec3 ro, vec3 fw, vec3 rt, vec3 up) {
    vec3 v = p - ro;
    return vec4(dot(v, rt) * FOCAL * 2.0 * params.resolution.y / params.resolution.x, dot(v, up) * FOCAL * 2.0, 0.0, dot(v, fw));
}

void main() {
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    uint s = hashu(uint(gl_InstanceIndex) * 2654435761u + 17u);
    vec3 velocity = vec3(0.7, -mix(7.0, 9.5, rnd(s)), 0.3);
    vec3 corner = ro + fw * 7.0 - BOX * 0.5;
    vec3 p = corner + mod(vec3(rnd(s), rnd(s), rnd(s)) * BOX + velocity * params.time - corner, BOX);
    vec3 tail = p - velocity * SHUTTER;

    vec4 a = clipOf(p, ro, fw, rt, up);
    vec4 b = clipOf(tail, ro, fw, rt, up);
    float sunlit = 1.0;
    vec3 sh = sunSpace(p);
    if (all(lessThan(abs(sh.xy), vec2(1.0)))) sunlit = texture(sunDepth, vec3(sh.xy * 0.5 + 0.5, sh.z - 0.0005));
    if (a.w < 0.3 || b.w < 0.3) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        colour = vec3(0.0);
        local = vec2(0.0);
        viewDistance = 0.0;
        return;
    }
    // The streak in pixels: from the head to the tail, a pixel wide at least.
    vec2 pa = a.xy / a.w * 0.5 * params.resolution;
    vec2 pb = b.xy / b.w * 0.5 * params.resolution;
    vec2 along = pb - pa;
    float span = max(length(along), 1.0);
    vec2 dir = along / span;
    vec2 across = vec2(-dir.y, dir.x);
    float width = DROP * FOCAL * params.resolution.y / a.w;
    float drawn = max(width, 1.0);

    int c = gl_VertexIndex;
    vec2 q = vec2(c == 1 || c == 2 || c == 4 ? 1.0 : 0.0, c == 2 || c == 4 || c == 5 ? 1.0 : 0.0);
    vec2 pixel = mix(pa, pa + dir * span, q.x) + across * (q.y * 2.0 - 1.0) * drawn * 0.5;
    float w = mix(a.w, b.w, q.x);
    gl_Position = vec4(pixel / (0.5 * params.resolution) * w, 0.5 * w, w);
#ifdef VULKAN
    gl_Position.y = -gl_Position.y;
#endif
    local = vec2(q.x, q.y * 2.0 - 1.0);
    viewDistance = length(mix(p, tail, q.x) - ro);
    // Grey sky light, and the sun scattered forwards through the drop where a beam crosses it.
    vec3 rd = normalize(p - ro);
    vec3 light = SKY_HORIZON * 0.25 + SUN * sunlit * (0.15 + 6.0 * sunPhase(rd));
    float fade = exp(-viewDistance * FOG_DENSITY);
    colour = light * fade * (width / drawn) * 0.6;
}
