// The scene whose camera the shared passes (TAA, depth of field, final)
// follow: the pool, the forest when SCENE_FOREST is defined, the white world
// when SCENE_WHITE is. The renderer builds those passes once for each scene.
#if defined(SCENE_FOREST)
#include "forest.glsl"
#elif defined(SCENE_WHITE)
#include "white.glsl"
#else
#include "pool.glsl"
#endif
