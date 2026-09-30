// Push constants of the scene and TAA passes (SceneRenderer::FrameParams).
layout(push_constant) uniform Frame {
    vec2 resolution;    // scene resolution, pixels
    vec2 jitter;        // this frame's sub-pixel offset, pixels
    float time;         // seconds
    float previousTime; // the previous frame's time, for reprojection
    uint frame;         // frames since the history was last valid
    float blend;        // weight of the new frame in the history
} params;

// NaN or infinity would live forever in the history: replace them.
vec3 sanitize(vec3 c) {
    return any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
}
