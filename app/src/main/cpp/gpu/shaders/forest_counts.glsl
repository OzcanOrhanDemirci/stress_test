// Sizes of the forest's draws, in one place for the shaders, the renderer
// (scene_renderer.cpp includes this file, so it holds only lines that are
// both GLSL and C++) and the desktop preview.
const int FOREST_GRID = 26;   // tree cells a side
const int TERRAIN_N = 140;    // ground quads a side
const int TRUNK_SIDES = 10;
const int TRUNK_RINGS = 14;
const int WHORLS = 24;        // a spruce's whorls of branches
const int PER_WHORL = 6;      // branches a whorl
const int SPRAYS = 3;         // needle cards along a branch
const int DEAD_BRANCHES = 12; // bare twigs on the trunk below the crown
const int CARDS = WHORLS * PER_WHORL * SPRAYS + DEAD_BRANCHES;  // a tree's cards: a beech's are all leaf clusters
const int SHADOW_RES = 2048;  // the sun's shadow map, texels a side
const int FERN_GRID = 64;     // fern cells a side, a metre each
const int FRONDS = 8;         // fronds a fern
const int FROND_SEGMENTS = 4; // quads along a frond
const int LOGS = 14;          // fallen trunks
const int LOG_RINGS = 8;
