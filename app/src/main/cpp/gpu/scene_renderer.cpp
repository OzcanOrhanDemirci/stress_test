#include "gpu/scene_renderer.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "shaders/bloom_down_frag.h"
#include "shaders/bloom_up_frag.h"
#include "shaders/final_frag.h"
#include "shaders/particles_comp.h"
#include "shaders/particles_frag.h"
#include "shaders/particles_vert.h"
#include "shaders/fullscreen_vert.h"
#include "shaders/pool_scene_frag.h"
#include "shaders/taa_frag.h"

namespace stress {
namespace {

constexpr VkFormat kHdr = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkFormat kDistance = VK_FORMAT_R32_SFLOAT;

/** Weight of the newest frame in the history: about ten frames are averaged. */
constexpr float kHistoryBlend = 0.1f;
/** Light below this (linear HDR) does not bloom. */
constexpr float kBloomThreshold = 1.0f;
constexpr float kBloomStrength = 0.35f;

VkAttachmentDescription attachment(VkFormat format, VkAttachmentLoadOp load, VkImageLayout initial) {
    VkAttachmentDescription a{};
    a.format = format;
    a.samples = VK_SAMPLE_COUNT_1_BIT;
    a.loadOp = load;
    a.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    a.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a.initialLayout = initial;
    a.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    return a;
}

/** The R2 sequence: sub-pixel offsets that cover the pixel evenly over any run of frames. */
void jitter(uint32_t frame, float& x, float& y) {
    constexpr double g = 1.32471795724474602596;
    const double n = static_cast<double>(frame % 1024u);
    x = static_cast<float>(std::fmod(0.5 + n / g, 1.0) - 0.5);
    y = static_cast<float>(std::fmod(0.5 + n / (g * g), 1.0) - 0.5);
}

}  // namespace

bool SceneRenderer::init(const vk::Context& vk, VkExtent2D screen, float scale, VkRenderPass presentPass) {
    vk_ = &vk;
    extent_ = {std::max(1u, static_cast<uint32_t>(static_cast<float>(screen.width) * scale)),
               std::max(1u, static_cast<uint32_t>(static_cast<float>(screen.height) * scale))};
    if (!createTargets() || !createPasses() || !createDescriptors() || !createPipelines(presentPass)) return false;
    // The first frame samples the history before anything wrote it: give it a readable layout.
    return vk.runOnce([&](VkCommandBuffer cmd) {
        for (auto& h : history_) {
            vk::transition(cmd, h.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0,
                           VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
    });
}

bool SceneRenderer::createTargets() {
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!vk_->createImage(extent_, kHdr, usage, color_)) return false;
    if (!vk_->createImage(extent_, kDistance, usage, distance_)) return false;
    if (!vk_->createImage(extent_, kHdr, usage, particles_)) return false;
    // Two vec4 a particle; the first physics step writes every one.
    if (!vk_->createBuffer(VkDeviceSize{kParticles} * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                           {VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT}, particleBuffer_)) {
        return false;
    }
    for (auto& h : history_) {
        if (!vk_->createImage(extent_, kHdr, usage, h)) return false;
    }
    VkExtent2D level = extent_;
    for (auto& b : bloom_) {
        level = {std::max(1u, level.width / 2), std::max(1u, level.height / 2)};
        if (!vk_->createImage(level, kHdr, usage, b)) return false;
    }
    return vk_->linearClampSampler(sampler_);
}

bool SceneRenderer::createPasses() {
    // Every pass here replaces what the previous frame's passes sampled: readBefore.
    const std::array<VkAttachmentDescription, 2> scene{
        attachment(kHdr, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED),
        attachment(kDistance, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED),
    };
    const std::array<VkAttachmentDescription, 1> write{
        attachment(kHdr, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED)};
    const std::array<VkAttachmentDescription, 1> add{
        attachment(kHdr, VK_ATTACHMENT_LOAD_OP_LOAD, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)};
    const std::array<VkAttachmentDescription, 1> clear{
        attachment(kHdr, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_IMAGE_LAYOUT_UNDEFINED)};
    if (!vk_->renderPass(scene, true, scenePass_) || !vk_->renderPass(write, true, writePass_) ||
        !vk_->renderPass(add, true, addPass_) || !vk_->renderPass(clear, true, particlePass_)) {
        return false;
    }
    const std::array<VkImageView, 1> particleView{particles_.view};
    if (!vk_->framebuffer(particlePass_, particleView, extent_, particleFramebuffer_)) return false;

    const std::array<VkImageView, 2> sceneViews{color_.view, distance_.view};
    if (!vk_->framebuffer(scenePass_, sceneViews, extent_, sceneFramebuffer_)) return false;
    for (size_t i = 0; i < history_.size(); ++i) {
        const std::array<VkImageView, 1> view{history_[i].view};
        if (!vk_->framebuffer(writePass_, view, extent_, historyFramebuffers_[i])) return false;
    }
    // The write and add passes are compatible, so one framebuffer per level serves both.
    for (size_t i = 0; i < bloom_.size(); ++i) {
        const std::array<VkImageView, 1> view{bloom_[i].view};
        if (!vk_->framebuffer(writePass_, view, bloom_[i].extent, bloomWrite_[i])) return false;
        bloomAdd_[i] = bloomWrite_[i];
    }
    return true;
}

bool SceneRenderer::createDescriptors() {
    std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
    for (uint32_t i = 0; i < bindings.size(); ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    VK_TRY(vkCreateDescriptorSetLayout(vk_->device, &layoutInfo, nullptr, &setLayout_));

    constexpr uint32_t kSets = 2 + 2 + kBloomLevels + kBloomLevels + 2 + 1;
    const std::array<VkDescriptorPoolSize, 2> sizes{{{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSets * 3},
                                                     {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1}}};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kSets;
    poolInfo.poolSizeCount = static_cast<uint32_t>(sizes.size());
    poolInfo.pPoolSizes = sizes.data();
    VK_TRY(vkCreateDescriptorPool(vk_->device, &poolInfo, nullptr, &descriptorPool_));

    auto allocate = [&](VkDescriptorSet& set, std::initializer_list<const vk::Image*> images) {
        VkDescriptorSetAllocateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        info.descriptorPool = descriptorPool_;
        info.descriptorSetCount = 1;
        info.pSetLayouts = &setLayout_;
        VK_TRY(vkAllocateDescriptorSets(vk_->device, &info, &set));
        std::vector<VkDescriptorImageInfo> infos;
        infos.reserve(images.size());
        for (const vk::Image* image : images) {
            infos.push_back({sampler_, image->view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL});
        }
        std::vector<VkWriteDescriptorSet> writes;
        for (uint32_t i = 0; i < infos.size(); ++i) {
            VkWriteDescriptorSet w{};
            w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w.dstSet = set;
            w.dstBinding = i;
            w.descriptorCount = 1;
            w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            w.pImageInfo = &infos[i];
            writes.push_back(w);
        }
        vkUpdateDescriptorSets(vk_->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
        return true;
    };

    // The downsample shader names the particles on every step (it reads them on the first only).
    for (uint32_t w = 0; w < 2; ++w) {
        if (!allocate(taaSets_[w], {&color_, &distance_, &history_[1 - w]})) return false;
        if (!allocate(firstDownSets_[w], {&history_[w], &particles_})) return false;
        if (!allocate(finalSets_[w], {&history_[w], &bloom_[0], &particles_})) return false;
    }
    for (uint32_t i = 1; i < kBloomLevels; ++i) {
        if (!allocate(downSets_[i], {&bloom_[i - 1], &particles_})) return false;
    }
    for (uint32_t i = 0; i + 1 < kBloomLevels; ++i) {
        if (!allocate(upSets_[i], {&bloom_[i + 1]})) return false;
    }
    return createParticleDescriptors();
}

bool SceneRenderer::createParticleDescriptors() {
    std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_VERTEX_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    VK_TRY(vkCreateDescriptorSetLayout(vk_->device, &layoutInfo, nullptr, &particleSetLayout_));

    VkDescriptorSetAllocateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    info.descriptorPool = descriptorPool_;
    info.descriptorSetCount = 1;
    info.pSetLayouts = &particleSetLayout_;
    VK_TRY(vkAllocateDescriptorSets(vk_->device, &info, &particleSet_));
    VkDescriptorBufferInfo buffer{particleBuffer_.buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorImageInfo image{sampler_, distance_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    std::array<VkWriteDescriptorSet, 2> writes{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = particleSet_;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &buffer;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = particleSet_;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[1].pImageInfo = &image;
    vkUpdateDescriptorSets(vk_->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    return true;
}

bool SceneRenderer::createPipelines(VkRenderPass presentPass) {
    VkPushConstantRange push{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(FrameParams)};
    static_assert(sizeof(FrameParams) == 32 && sizeof(BloomParams) == 32 && sizeof(FinalParams) <= 32,
                  "push constant blocks must match the shaders");
    VkPipelineLayoutCreateInfo sceneInfo{};
    sceneInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    sceneInfo.pushConstantRangeCount = 1;
    sceneInfo.pPushConstantRanges = &push;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &sceneInfo, nullptr, &sceneLayout_));
    VkPipelineLayoutCreateInfo samplingInfo = sceneInfo;
    samplingInfo.setLayoutCount = 1;
    samplingInfo.pSetLayouts = &setLayout_;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &samplingInfo, nullptr, &samplingLayout_));

    VkPushConstantRange particlePush{
        VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(ParticleParams)};
    static_assert(sizeof(ParticleParams) == 32, "push constant block must match particle_params.glsl");
    VkPipelineLayoutCreateInfo particleInfo{};
    particleInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    particleInfo.setLayoutCount = 1;
    particleInfo.pSetLayouts = &particleSetLayout_;
    particleInfo.pushConstantRangeCount = 1;
    particleInfo.pPushConstantRanges = &particlePush;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &particleInfo, nullptr, &particleLayout_));
    if (!vk_->computePipeline(particleLayout_, kParticlesCompSpirv, simulatePipeline_) ||
        !vk_->graphicsPipeline(particlePass_, 1, particleLayout_, kParticlesVertSpirv, kParticlesFragSpirv, vk::Blend::Additive,
                               particlePipeline_, VK_PRIMITIVE_TOPOLOGY_POINT_LIST)) {
        return false;
    }

    const std::span<const uint32_t> vertex = kFullscreenVertSpirv;
    return vk_->graphicsPipeline(scenePass_, 2, sceneLayout_, vertex, kPoolSceneFragSpirv, vk::Blend::None,
                                 scenePipeline_) &&
           vk_->graphicsPipeline(writePass_, 1, samplingLayout_, vertex, kTaaFragSpirv, vk::Blend::None, taaPipeline_) &&
           vk_->graphicsPipeline(writePass_, 1, samplingLayout_, vertex, kBloomDownFragSpirv, vk::Blend::None,
                                 downPipeline_) &&
           vk_->graphicsPipeline(addPass_, 1, samplingLayout_, vertex, kBloomUpFragSpirv, vk::Blend::Additive,
                                 upPipeline_) &&
           vk_->graphicsPipeline(presentPass, 1, samplingLayout_, vertex, kFinalFragSpirv, vk::Blend::None,
                                 finalPipeline_);
}

void SceneRenderer::pass(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer, VkExtent2D extent) {
    VkRenderPassBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = renderPass;
    begin.framebuffer = framebuffer;
    begin.renderArea = {{0, 0}, extent};
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    const VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent.width), static_cast<float>(extent.height), 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
}

// What a pass just wrote is sampled by the next one.
void SceneRenderer::readable(VkCommandBuffer cmd) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1,
                         &barrier, 0, nullptr, 0, nullptr);
}

void SceneRenderer::record(VkCommandBuffer cmd, float time) {
    const uint32_t w = frame_ % 2;
    FrameParams frame{};
    frame.width = static_cast<float>(extent_.width);
    frame.height = static_cast<float>(extent_.height);
    jitter(frame_, frame.jitterX, frame.jitterY);
    frame.time = time;
    frame.previousTime = frame_ == 0 ? time : previousTime_;
    frame.frame = frame_;
    frame.blend = kHistoryBlend;

    pass(cmd, scenePass_, sceneFramebuffer_, extent_);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, scenePipeline_);
    vkCmdPushConstants(cmd, sceneLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(frame), &frame);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    readable(cmd);
    recordParticles(cmd, time);

    pass(cmd, writePass_, historyFramebuffers_[w], extent_);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, taaPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, samplingLayout_, 0, 1, &taaSets_[w], 0, nullptr);
    vkCmdPushConstants(cmd, samplingLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(frame), &frame);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    readable(cmd);

    VkExtent2D source = extent_;
    for (uint32_t i = 0; i < kBloomLevels; ++i) {
        const VkExtent2D target = bloom_[i].extent;
        BloomParams bloom{};
        bloom.texelX = 1.0f / static_cast<float>(source.width);
        bloom.texelY = 1.0f / static_cast<float>(source.height);
        bloom.targetWidth = static_cast<float>(target.width);
        bloom.targetHeight = static_cast<float>(target.height);
        bloom.threshold = kBloomThreshold;
        bloom.first = i == 0 ? 1.0f : 0.0f;
        pass(cmd, writePass_, bloomWrite_[i], target);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, downPipeline_);
        const VkDescriptorSet set = i == 0 ? firstDownSets_[w] : downSets_[i];
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, samplingLayout_, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(cmd, samplingLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(bloom), &bloom);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
        readable(cmd);
        source = target;
    }
    for (uint32_t i = kBloomLevels - 1; i-- > 0;) {
        const VkExtent2D from = bloom_[i + 1].extent;
        const VkExtent2D target = bloom_[i].extent;
        BloomParams bloom{};
        bloom.texelX = 1.0f / static_cast<float>(from.width);
        bloom.texelY = 1.0f / static_cast<float>(from.height);
        bloom.targetWidth = static_cast<float>(target.width);
        bloom.targetHeight = static_cast<float>(target.height);
        bloom.radius = 1.0f;
        bloom.weight = 1.0f;
        pass(cmd, addPass_, bloomAdd_[i], target);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, upPipeline_);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, samplingLayout_, 0, 1, &upSets_[i], 0, nullptr);
        vkCmdPushConstants(cmd, samplingLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(bloom), &bloom);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
        readable(cmd);
    }

    written_ = w;
    previousTime_ = time;
    ++frame_;
}

void SceneRenderer::recordParticles(VkCommandBuffer cmd, float time) {
    ParticleParams params{};
    params.width = static_cast<float>(extent_.width);
    params.height = static_cast<float>(extent_.height);
    params.time = time;
    params.dt = frame_ == 0 ? 0.0f : std::clamp(time - previousTime_, 0.0f, 0.05f);
    params.count = kParticles;
    params.frame = frame_;
    const VkShaderStageFlags stages = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

    // Last frame's draw read the particles this step overwrites.
    VkMemoryBarrier before{};
    before.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    before.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    before.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &before, 0, nullptr, 0, nullptr);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, simulatePipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, particleLayout_, 0, 1, &particleSet_, 0, nullptr);
    vkCmdPushConstants(cmd, particleLayout_, stages, 0, sizeof(params), &params);
    vkCmdDispatch(cmd, (kParticles + 63) / 64, 1, 1);

    VkMemoryBarrier after{};
    after.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    after.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    after.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0, 1, &after, 0,
                         nullptr, 0, nullptr);

    VkClearValue clear{};
    VkRenderPassBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = particlePass_;
    begin.framebuffer = particleFramebuffer_;
    begin.renderArea = {{0, 0}, extent_};
    begin.clearValueCount = 1;
    begin.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    const VkViewport viewport{0.0f, 0.0f, params.width, params.height, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, particlePipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, particleLayout_, 0, 1, &particleSet_, 0, nullptr);
    vkCmdPushConstants(cmd, particleLayout_, stages, 0, sizeof(params), &params);
    vkCmdDraw(cmd, kParticles, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    readable(cmd);
}

void SceneRenderer::recordFinal(VkCommandBuffer cmd, VkExtent2D screen, float time) {
    const VkViewport viewport{0.0f, 0.0f, static_cast<float>(screen.width), static_cast<float>(screen.height), 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, screen};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    FinalParams params{static_cast<float>(screen.width), static_cast<float>(screen.height), time, kBloomStrength};
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, finalPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, samplingLayout_, 0, 1, &finalSets_[written_], 0, nullptr);
    vkCmdPushConstants(cmd, samplingLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params), &params);
    vkCmdDraw(cmd, 3, 1, 0, 0);
}

void SceneRenderer::destroy() {
    if (vk_ == nullptr) return;
    VkDevice d = vk_->device;
    for (VkPipeline p : {scenePipeline_, taaPipeline_, downPipeline_, upPipeline_, finalPipeline_, simulatePipeline_,
                         particlePipeline_}) {
        if (p) vkDestroyPipeline(d, p, nullptr);
    }
    if (particleLayout_) vkDestroyPipelineLayout(d, particleLayout_, nullptr);
    if (particleSetLayout_) vkDestroyDescriptorSetLayout(d, particleSetLayout_, nullptr);
    if (particleFramebuffer_) vkDestroyFramebuffer(d, particleFramebuffer_, nullptr);
    if (particlePass_) vkDestroyRenderPass(d, particlePass_, nullptr);
    vk_->destroy(particles_);
    vk_->destroy(particleBuffer_);
    if (sceneLayout_) vkDestroyPipelineLayout(d, sceneLayout_, nullptr);
    if (samplingLayout_) vkDestroyPipelineLayout(d, samplingLayout_, nullptr);
    if (descriptorPool_) vkDestroyDescriptorPool(d, descriptorPool_, nullptr);
    if (setLayout_) vkDestroyDescriptorSetLayout(d, setLayout_, nullptr);
    if (sceneFramebuffer_) vkDestroyFramebuffer(d, sceneFramebuffer_, nullptr);
    for (VkFramebuffer f : historyFramebuffers_) {
        if (f) vkDestroyFramebuffer(d, f, nullptr);
    }
    for (VkFramebuffer f : bloomWrite_) {
        if (f) vkDestroyFramebuffer(d, f, nullptr);
    }
    for (VkRenderPass p : {scenePass_, writePass_, addPass_}) {
        if (p) vkDestroyRenderPass(d, p, nullptr);
    }
    if (sampler_) vkDestroySampler(d, sampler_, nullptr);
    vk_->destroy(color_);
    vk_->destroy(distance_);
    for (auto& h : history_) vk_->destroy(h);
    for (auto& b : bloom_) vk_->destroy(b);
    *this = SceneRenderer{};
}

}  // namespace stress
