// Push constants of the forest's draws (SceneRenderer, scene 2). Shared by
// the Vulkan renderer and the desktop preview, hence the macro.
#ifdef VULKAN
#define FOREST_PUSH layout(push_constant)
#else
#define FOREST_PUSH layout(std140, binding = 0)
#endif

FOREST_PUSH uniform Forest {
    vec2 resolution;  // scene resolution, pixels
    vec2 jitter;      // this frame's sub-pixel offset, pixels
    float time;       // seconds
    uint frame;       // reseeds the random samples
    uint part;        // which geometry the draw makes: PART_*
    float spare;
} params;

const uint PART_TERRAIN = 0u;
const uint PART_TRUNKS = 1u;
const uint PART_CARDS = 2u;
const uint PART_SHADOW = 8u;  // added to a part: drawn into the sun's shadow map

// Materials, passed from the vertex to the fragment shader.
const uint M_GROUND = 0u;
const uint M_BARK = 1u;
const uint M_NEEDLES = 2u;
const uint M_LEAVES = 3u;
const uint M_TWIGS = 4u;
const uint M_SMOOTH_BARK = 5u;  // a beech's
