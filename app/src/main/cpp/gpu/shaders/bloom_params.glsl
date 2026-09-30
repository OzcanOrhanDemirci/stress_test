// Push constants of the bloom passes (SceneRenderer::BloomParams).
layout(push_constant) uniform Bloom {
    vec2 sourceTexel;  // 1 / source size
    vec2 targetSize;   // pixels
    float threshold;   // first downsample only: light below this does not bloom
    float first;       // 1 on the first downsample
    float radius;      // upsample tent radius, in source texels
    float weight;      // upsample contribution
} params;
