#pragma once

#include <array>
#include <cstdint>

#include "gpu/vk.h"

namespace stress {

/**
 * The cinematic scene: drawn at a reduced resolution, accumulated over time,
 * bloomed, and finished onto the screen.
 *
 *   water   wave-equation steps on the pool's surface (compute, fixed 60 Hz),
 *           then its slope and curvature for the scene to sample
 *   scene   pool reactor -> HDR colour + ray distance       (scene resolution)
 *   motes   particle physics step (compute), drawn as points into their own target
 *   taa     reproject last frame's history, clip, blend      (ping-pong pair)
 *   bloom   5 steps down (threshold on the first), 4 back up (additive)
 *   final   Catmull-Rom upscale + bloom + tone map, inside the caller's present pass
 *
 * Every frame moves the scene's samples by a sub-pixel jitter and reseeds its
 * random effects, so the history converges on a clean, anti-aliased image.
 */
class SceneRenderer {
public:
    struct FrameParams {
        float width;
        float height;
        float jitterX;
        float jitterY;
        float time;
        float previousTime;
        uint32_t frame;
        float blend;
    };

    struct BloomParams {
        float texelX;
        float texelY;
        float targetWidth;
        float targetHeight;
        float threshold;
        float first;
        float radius;
        float weight;
    };

    struct ParticleParams {
        float width;
        float height;
        float time;
        float dt;
        uint32_t count;
        uint32_t frame;
        float spare0;
        float spare1;
    };

    /** Bubbles and sparks together; six in ten are bubbles. */
    static constexpr uint32_t kParticles = 49152;

    struct WaterParams {
        uint32_t step;
        uint32_t pad0;
        uint32_t pad1;
        uint32_t pad2;
    };

    /** water_params.glsl: WATER_N cells a side, WATER_HZ steps a second. */
    static constexpr uint32_t kWaterCells = 256;
    static constexpr double kWaterHz = 60.0;
    /** A slow frame catches up at most this many steps; beyond that the water slows down. */
    static constexpr uint64_t kMaxWaterSteps = 4;

    struct FinalParams {
        float width;
        float height;
        float time;
        float bloomStrength;
    };

    static constexpr uint32_t kBloomLevels = 5;

    /** `presentPass` is the render pass the final picture is drawn in; `screen` its size. */
    bool init(const vk::Context& vk, VkExtent2D screen, float scale, VkRenderPass presentPass);

    /** Records scene, TAA and bloom for one frame; call before the present pass begins. */
    void record(VkCommandBuffer cmd, float time);

    /** Records the final picture; call inside the present pass. */
    void recordFinal(VkCommandBuffer cmd, VkExtent2D screen, float time);

    void destroy();

    VkExtent2D sceneExtent() const { return extent_; }

private:
    bool createTargets();
    bool createPasses();
    bool createDescriptors();
    bool createParticleDescriptors();
    bool createWater();
    void clearWater(VkCommandBuffer cmd);
    void recordWater(VkCommandBuffer cmd, float time);
    bool createPipelines(VkRenderPass presentPass);
    void recordParticles(VkCommandBuffer cmd, float time);
    void pass(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer, VkExtent2D extent);
    void readable(VkCommandBuffer cmd);

    const vk::Context* vk_ = nullptr;
    VkExtent2D extent_{};

    vk::Image color_;
    vk::Image particles_;
    vk::Buffer particleBuffer_;
    vk::Image distance_;
    std::array<vk::Image, 2> history_{};
    std::array<vk::Image, kBloomLevels> bloom_{};
    VkSampler sampler_ = VK_NULL_HANDLE;

    VkRenderPass scenePass_ = VK_NULL_HANDLE;
    VkRenderPass writePass_ = VK_NULL_HANDLE;  // one HDR target, contents replaced
    VkRenderPass addPass_ = VK_NULL_HANDLE;    // one HDR target, contents kept, added to
    VkRenderPass particlePass_ = VK_NULL_HANDLE;  // one HDR target, cleared
    VkFramebuffer particleFramebuffer_ = VK_NULL_HANDLE;
    VkFramebuffer sceneFramebuffer_ = VK_NULL_HANDLE;
    std::array<VkFramebuffer, 2> historyFramebuffers_{};
    std::array<VkFramebuffer, kBloomLevels> bloomWrite_{};
    std::array<VkFramebuffer, kBloomLevels> bloomAdd_{};

    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, 2> taaSets_{};        // indexed by the history being written
    std::array<VkDescriptorSet, 2> firstDownSets_{};  // bloom from the history just written
    std::array<VkDescriptorSet, kBloomLevels> downSets_{};
    std::array<VkDescriptorSet, kBloomLevels> upSets_{};
    std::array<VkDescriptorSet, 2> finalSets_{};
    VkDescriptorSetLayout particleSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorSet particleSet_ = VK_NULL_HANDLE;
    VkPipelineLayout particleLayout_ = VK_NULL_HANDLE;
    VkPipeline simulatePipeline_ = VK_NULL_HANDLE;
    VkPipeline particlePipeline_ = VK_NULL_HANDLE;

    // Heights take turns: step i reads [i % 3] (a step before) and [(i + 1) % 3]
    // (now), writes [(i + 2) % 3]; waterSets_[i % 3] binds them so.
    std::array<vk::Image, 3> waterHeights_{};
    vk::Image waterSurface_;
    VkDescriptorSetLayout waterSetLayout_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, 3> waterSets_{};
    VkPipelineLayout waterLayout_ = VK_NULL_HANDLE;
    VkPipeline waterStepPipeline_ = VK_NULL_HANDLE;
    VkPipeline waterSurfacePipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout sceneSetLayout_ = VK_NULL_HANDLE;  // the surface, sampled by the scene
    VkDescriptorSet sceneSet_ = VK_NULL_HANDLE;
    uint64_t waterSteps_ = 0;  // simulated time, in steps: seeds the bursts
    uint32_t waterTurn_ = 0;   // steps actually taken: picks the heights' turn

    VkPipelineLayout sceneLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout samplingLayout_ = VK_NULL_HANDLE;
    VkPipeline scenePipeline_ = VK_NULL_HANDLE;
    VkPipeline taaPipeline_ = VK_NULL_HANDLE;
    VkPipeline downPipeline_ = VK_NULL_HANDLE;
    VkPipeline upPipeline_ = VK_NULL_HANDLE;
    VkPipeline finalPipeline_ = VK_NULL_HANDLE;

    uint32_t frame_ = 0;
    uint32_t written_ = 0;  // history image written by the last record()
    float previousTime_ = 0.0f;
};

}  // namespace stress
