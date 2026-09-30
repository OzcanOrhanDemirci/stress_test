// Particles of the pool scene (SceneRenderer::ParticleParams).
//   position.xyz  where it is          position.w  age, seconds
//   velocity.xyz  how it moves         velocity.w  kind: 0 bubble, 1 spark
struct Particle {
    vec4 position;
    vec4 velocity;
};

layout(std430, set = 0, binding = 0) buffer Particles {
    Particle particles[];
};

layout(push_constant) uniform ParticleParams {
    vec2 resolution;  // scene resolution, pixels
    float time;
    float dt;         // seconds since the last step
    uint count;
    uint frame;       // 0 on the first step: every particle is born
    float spare0;
    float spare1;
} params;

const float KIND_BUBBLE = 0.0;
const float KIND_SPARK = 1.0;
