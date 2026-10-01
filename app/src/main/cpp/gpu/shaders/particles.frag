#version 450
// A particle's point: a glowing dot for a spark, a bright-rimmed ring for a
// bubble. Sparks hide behind the scene's surfaces; bubbles are under the water,
// whose surface the scene's distance would put in front of them all.
#extension GL_GOOGLE_include_directive : require

#include "particle_params.glsl"

layout(set = 0, binding = 1) uniform sampler2D sceneDistance;

layout(location = 0) in vec4 color;
layout(location = 1) in float viewDistance;
layout(location = 2) flat in float kind;
layout(location = 3) in float surfaceDistance;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(c, c);
    if (r2 > 1.0) discard;
    float shape;
    float scene = texelFetch(sceneDistance, ivec2(gl_FragCoord.xy), 0).r;
    if (kind == KIND_BUBBLE) {
        if (surfaceDistance > 0.0 && scene < surfaceDistance - 0.05) discard;
        float r = sqrt(r2);
        shape = exp(-(r - 0.72) * (r - 0.72) * 60.0) + 0.15 * (1.0 - r2);
    } else {
        if (viewDistance > scene + 0.05) discard;
        shape = (1.0 - r2) * (1.0 - r2);
    }
    outColor = vec4(color.rgb * shape, 1.0);
}
