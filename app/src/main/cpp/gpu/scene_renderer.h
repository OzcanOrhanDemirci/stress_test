#pragma once

#include <array>
#include <cstdint>

#include "gpu/vk.h"

namespace stress {

/**
 * The cinematic scene: drawn at a reduced resolution, accumulated over time,
 * bloomed, and finished onto the screen.
 *
 *   scene   pool reactor -> HDR colour + ray distance       (scene resolution)
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
    bool createPipelines(VkRenderPass presentPass);
    void pass(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer, VkExtent2D extent);
    void readable(VkCommandBuffer cmd);

    const vk::Context* vk_ = nullptr;
    VkExtent2D extent_{};

    vk::Image color_;
    vk::Image distance_;
    std::array<vk::Image, 2> history_{};
    std::array<vk::Image, kBloomLevels> bloom_{};
    VkSampler sampler_ = VK_NULL_HANDLE;

    VkRenderPass scenePass_ = VK_NULL_HANDLE;
    VkRenderPass writePass_ = VK_NULL_HANDLE;  // one HDR target, contents replaced
    VkRenderPass addPass_ = VK_NULL_HANDLE;    // one HDR target, contents kept, added to
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
