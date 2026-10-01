#include "gpu/scene_renderer.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "shaders/bloom_down_frag.h"
#include "shaders/bloom_up_frag.h"
#include "shaders/dof_frag.h"
#include "shaders/final_frag.h"
#include "shaders/final_forest_frag.h"
#include "shaders/dof_forest_frag.h"
#include "shaders/forest_geometry_frag.h"
#include "shaders/forest_geometry_vert.h"
#include "shaders/forest_light_frag.h"
#include "shaders/forest_rain_frag.h"
#include "shaders/forest_rain_vert.h"
#include "shaders/forest_shadow_frag.h"
#include "shaders/forest_sky_frag.h"
#include "shaders/taa_forest_frag.h"
#include "shaders/particles_comp.h"
#include "shaders/particles_frag.h"
#include "shaders/particles_vert.h"
#include "shaders/fullscreen_vert.h"
#include "shaders/pool_scene_frag.h"
#include "shaders/taa_frag.h"
#include "shaders/water_step_comp.h"
#include "shaders/water_surface_comp.h"

namespace stress {
namespace {

// The forest's draw sizes: the same file the shaders include.
#include "gpu/shaders/forest_counts.glsl"

constexpr VkFormat kHdr = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkFormat kDistance = VK_FORMAT_R32_SFLOAT;

/** Weight of the newest frame in the history: about ten frames are averaged. */
constexpr float kHistoryBlend = 0.1f;
/** Light below this (linear HDR) does not bloom. */
constexpr float kBloomThreshold = 1.0f;
constexpr float kBloomStrength = 0.12f;

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

/** Says which pipeline the driver refused: the VkResult alone does not. */
bool named(bool created, const char* what) {
    if (!created) LOGE("SceneRenderer: the %s pipeline was not created", what);
    return created;
}

/** The R2 sequence: sub-pixel offsets that cover the pixel evenly over any run of frames. */
void jitter(uint32_t frame, float& x, float& y) {
    constexpr double g = 1.32471795724474602596;
    const double n = static_cast<double>(frame % 1024u);
    x = static_cast<float>(std::fmod(0.5 + n / g, 1.0) - 0.5);
    y = static_cast<float>(std::fmod(0.5 + n / (g * g), 1.0) - 0.5);
}

}  // namespace

bool SceneRenderer::init(const vk::Context& vk, VkExtent2D screen, float scale, VkRenderPass presentPass, Kind kind) {
    vk_ = &vk;
    kind_ = kind;
    extent_ = {std::max(1u, static_cast<uint32_t>(static_cast<float>(screen.width) * scale)),
               std::max(1u, static_cast<uint32_t>(static_cast<float>(screen.height) * scale))};
    if (!createTargets() || !createPasses() || !createDescriptors() || !createWater() ||
        !createPipelines(presentPass) || (kind_ == Kind::Forest && !createForest())) {
        return false;
    }
    // The first frame samples the history before anything wrote it: give it a readable layout.
    return vk.runOnce([&](VkCommandBuffer cmd) {
        for (auto& h : history_) {
            vk::transition(cmd, h.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0,
                           VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
        clearWater(cmd);
    });
}

// Still water to start from; the water images stay in GENERAL layout for good.
void SceneRenderer::clearWater(VkCommandBuffer cmd) {
    const VkClearColorValue zero{};
    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    for (const vk::Image* image : {&waterHeights_[0], &waterHeights_[1], &waterHeights_[2], &waterSurface_}) {
        vk::transition(cmd, image->image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0,
                       VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        vkCmdClearColorImage(cmd, image->image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
    }
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &barrier, 0,
                         nullptr, 0, nullptr);
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
    if (!vk_->createImage(bloom_[0].extent, kHdr, usage, dof_)) return false;
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
    const std::array<VkImageView, 1> dofView{dof_.view};
    return vk_->framebuffer(writePass_, dofView, dof_.extent, dofFramebuffer_);
}

bool SceneRenderer::createDescriptors() {
    // Up to five images a pass samples; each pass's set fills the bindings its shader names.
    std::array<VkDescriptorSetLayoutBinding, 5> bindings{};
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

    // Sampling sets, the particles' set, the water's three, the scene's one, the forest's two.
    constexpr uint32_t kSets = 2 + 2 + kBloomLevels + kBloomLevels + 2 + 2 + 1 + 3 + 1 + 2;
    const std::array<VkDescriptorPoolSize, 3> sizes{{{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSets * 5},
                                                     {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1},
                                                     {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3 * 4}}};
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
        if (!allocate(finalSets_[w], {&history_[w], &bloom_[0], &particles_, &dof_, &distance_})) return false;
        if (!allocate(dofSets_[w], {&history_[w], &distance_})) return false;
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

bool SceneRenderer::createWater() {
    const VkExtent2D cells{kWaterCells, kWaterCells};
    for (auto& h : waterHeights_) {
        if (!vk_->createImage(cells, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, h)) {
            return false;
        }
    }
    if (!vk_->createImage(cells, kHdr,
                          VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                          waterSurface_)) {
        return false;
    }

    // Steps and the surface pass: heights now, before and next, then the surface (water_*.comp).
    std::array<VkDescriptorSetLayoutBinding, 4> storage{};
    for (uint32_t i = 0; i < storage.size(); ++i) {
        storage[i].binding = i;
        storage[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        storage[i].descriptorCount = 1;
        storage[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(storage.size());
    layoutInfo.pBindings = storage.data();
    VK_TRY(vkCreateDescriptorSetLayout(vk_->device, &layoutInfo, nullptr, &waterSetLayout_));
    VkDescriptorSetLayoutBinding sampled{};
    sampled.binding = 0;
    sampled.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sampled.descriptorCount = 1;
    sampled.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &sampled;
    VK_TRY(vkCreateDescriptorSetLayout(vk_->device, &layoutInfo, nullptr, &sceneSetLayout_));

    auto allocate = [&](VkDescriptorSetLayout layout, VkDescriptorSet& set) {
        VkDescriptorSetAllocateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        info.descriptorPool = descriptorPool_;
        info.descriptorSetCount = 1;
        info.pSetLayouts = &layout;
        VK_TRY(vkAllocateDescriptorSets(vk_->device, &info, &set));
        return true;
    };
    for (uint32_t i = 0; i < waterSets_.size(); ++i) {
        if (!allocate(waterSetLayout_, waterSets_[i])) return false;
        const std::array<const vk::Image*, 4> images{&waterHeights_[(i + 1) % 3], &waterHeights_[i],
                                                     &waterHeights_[(i + 2) % 3], &waterSurface_};
        std::array<VkDescriptorImageInfo, 4> infos{};
        std::array<VkWriteDescriptorSet, 4> writes{};
        for (uint32_t b = 0; b < images.size(); ++b) {
            infos[b] = {VK_NULL_HANDLE, images[b]->view, VK_IMAGE_LAYOUT_GENERAL};
            writes[b].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[b].dstSet = waterSets_[i];
            writes[b].dstBinding = b;
            writes[b].descriptorCount = 1;
            writes[b].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[b].pImageInfo = &infos[b];
        }
        vkUpdateDescriptorSets(vk_->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }
    if (!allocate(sceneSetLayout_, sceneSet_)) return false;
    const VkDescriptorImageInfo surface{sampler_, waterSurface_.view, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = sceneSet_;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &surface;
    vkUpdateDescriptorSets(vk_->device, 1, &write, 0, nullptr);

    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(WaterParams)};
    static_assert(sizeof(WaterParams) == 16, "push constant block must match water_params.glsl");
    VkPipelineLayoutCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    info.setLayoutCount = 1;
    info.pSetLayouts = &waterSetLayout_;
    info.pushConstantRangeCount = 1;
    info.pPushConstantRanges = &push;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &info, nullptr, &waterLayout_));
    return named(vk_->computePipeline(waterLayout_, kWaterStepCompSpirv, waterStepPipeline_), "water_step") &&
           named(vk_->computePipeline(waterLayout_, kWaterSurfaceCompSpirv, waterSurfacePipeline_), "water_surface");
}

bool SceneRenderer::createPipelines(VkRenderPass presentPass) {
    VkPushConstantRange push{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(FrameParams)};
    static_assert(sizeof(FrameParams) == 32 && sizeof(BloomParams) == 32 && sizeof(DofParams) == 32 &&
                      sizeof(FinalParams) <= 32,
                  "push constant blocks must match the shaders");
    VkPipelineLayoutCreateInfo sceneInfo{};
    sceneInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    sceneInfo.setLayoutCount = 1;
    sceneInfo.pSetLayouts = &sceneSetLayout_;
    sceneInfo.pushConstantRangeCount = 1;
    sceneInfo.pPushConstantRanges = &push;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &sceneInfo, nullptr, &sceneLayout_));
    VkPipelineLayoutCreateInfo samplingInfo = sceneInfo;
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
    if (!named(vk_->computePipeline(particleLayout_, kParticlesCompSpirv, simulatePipeline_), "particles.comp") ||
        !named(vk_->graphicsPipeline(particlePass_, 1, particleLayout_, kParticlesVertSpirv, kParticlesFragSpirv, vk::Blend::Additive,
                               particlePipeline_, VK_PRIMITIVE_TOPOLOGY_POINT_LIST), "Particles")) {
        return false;
    }

    const std::span<const uint32_t> vertex = kFullscreenVertSpirv;
    // The passes that follow the camera are built for the scene drawn (scene.glsl).
    const bool forest = kind_ == Kind::Forest;
    const std::span<const uint32_t> taa = forest ? std::span<const uint32_t>(kTaaForestFragSpirv) : kTaaFragSpirv;
    const std::span<const uint32_t> dof = forest ? std::span<const uint32_t>(kDofForestFragSpirv) : kDofFragSpirv;
    const std::span<const uint32_t> final = forest ? std::span<const uint32_t>(kFinalForestFragSpirv) : kFinalFragSpirv;
    return named(vk_->graphicsPipeline(scenePass_, 2, sceneLayout_, vertex, kPoolSceneFragSpirv, vk::Blend::None,
                                 scenePipeline_), "PoolScene") &&
           named(vk_->graphicsPipeline(writePass_, 1, samplingLayout_, vertex, taa, vk::Blend::None, taaPipeline_), "Taa") &&
           named(vk_->graphicsPipeline(writePass_, 1, samplingLayout_, vertex, kBloomDownFragSpirv, vk::Blend::None,
                                 downPipeline_), "BloomDown") &&
           named(vk_->graphicsPipeline(addPass_, 1, samplingLayout_, vertex, kBloomUpFragSpirv, vk::Blend::Additive,
                                 upPipeline_), "BloomUp") &&
           named(vk_->graphicsPipeline(writePass_, 1, samplingLayout_, vertex, dof, vk::Blend::None, dofPipeline_), "Dof") &&
           named(vk_->graphicsPipeline(presentPass, 1, samplingLayout_, vertex, final, vk::Blend::None,
                                 finalPipeline_), "Final");
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

    if (kind_ == Kind::Forest) {
        recordForest(cmd, frame);
        recordRain(cmd, frame);
    } else {
        recordWater(cmd, time);
        pass(cmd, scenePass_, sceneFramebuffer_, extent_);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, scenePipeline_);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, sceneLayout_, 0, 1, &sceneSet_, 0, nullptr);
        vkCmdPushConstants(cmd, sceneLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(frame), &frame);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
        readable(cmd);
        recordParticles(cmd, time);
    }

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

    DofParams dof{};
    dof.texelX = 1.0f / static_cast<float>(extent_.width);
    dof.texelY = 1.0f / static_cast<float>(extent_.height);
    dof.targetWidth = static_cast<float>(dof_.extent.width);
    dof.targetHeight = static_cast<float>(dof_.extent.height);
    dof.time = time;
    dof.sceneHeight = static_cast<float>(extent_.height);
    pass(cmd, writePass_, dofFramebuffer_, dof_.extent);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, dofPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, samplingLayout_, 0, 1, &dofSets_[w], 0, nullptr);
    vkCmdPushConstants(cmd, samplingLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(dof), &dof);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    readable(cmd);

    written_ = w;
    previousTime_ = time;
    ++frame_;
}

bool SceneRenderer::createForest() {
    if (!vk_->createImage(extent_, vk::kDepthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, depth_)) return false;
    // Colour and distance as the pool's pass leaves them; the sky covers every pixel first.
    const std::array<VkAttachmentDescription, 2> colours{
        attachment(kHdr, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED),
        attachment(kDistance, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED),
    };
    VkAttachmentDescription depth{};
    depth.format = vk::kDepthFormat;
    depth.samples = VK_SAMPLE_COUNT_1_BIT;
    depth.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    depth.storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    depth.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    depth.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    depth.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    depth.finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    if (!vk_->renderPass(colours, true, forestPass_, &depth)) return false;
    const std::array<VkImageView, 3> views{color_.view, distance_.view, depth_.view};
    if (!vk_->framebuffer(forestPass_, views, extent_, forestFramebuffer_)) return false;
    // The sunbeams are added onto the colour the forest pass leaves.
    const std::array<VkImageView, 1> lit{color_.view};
    if (!vk_->framebuffer(addPass_, lit, extent_, lightFramebuffer_)) return false;
    if (!createForestShadow()) return false;

    VkDescriptorSetLayoutBinding sampled[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        sampled[i].binding = i;
        sampled[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        sampled[i].descriptorCount = 1;
        sampled[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    }
    sampled[0].stageFlags |= VK_SHADER_STAGE_VERTEX_BIT;  // the rain asks the shadow map per drop
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = sampled;
    VK_TRY(vkCreateDescriptorSetLayout(vk_->device, &layoutInfo, nullptr, &forestSetLayout_));
    const VkDescriptorImageInfo shadowInfo{shadowSampler_, shadow_.view, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL};
    const VkDescriptorImageInfo distanceInfo{sampler_, distance_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    for (VkDescriptorSet* set : {&forestSurfaceSet_, &forestLightSet_}) {
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = descriptorPool_;
        allocate.descriptorSetCount = 1;
        allocate.pSetLayouts = &forestSetLayout_;
        VK_TRY(vkAllocateDescriptorSets(vk_->device, &allocate, set));
        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t b = 0; b < 2; ++b) {
            writes[b].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[b].dstSet = *set;
            writes[b].dstBinding = b;
            writes[b].descriptorCount = 1;
            writes[b].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[b].pImageInfo = b == 0 ? &shadowInfo : &distanceInfo;
        }
        // The surfaces' set names only the shadow map: the distances are being drawn while it is bound.
        const uint32_t count = set == &forestSurfaceSet_ ? 1u : 2u;
        vkUpdateDescriptorSets(vk_->device, count, writes.data(), 0, nullptr);
    }

    VkPushConstantRange push{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(ForestParams)};
    static_assert(sizeof(ForestParams) == 32, "push constant block must match forest_params.glsl");
    VkPipelineLayoutCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    info.setLayoutCount = 1;
    info.pSetLayouts = &forestSetLayout_;
    info.pushConstantRangeCount = 1;
    info.pPushConstantRanges = &push;
    VK_TRY(vkCreatePipelineLayout(vk_->device, &info, nullptr, &forestLayout_));
    return named(vk_->graphicsPipeline(forestPass_, 2, forestLayout_, kFullscreenVertSpirv, kForestSkyFragSpirv,
                                       vk::Blend::None, forestSkyPipeline_), "ForestSky") &&
           named(vk_->graphicsPipeline(forestPass_, 2, forestLayout_, kForestGeometryVertSpirv, kForestGeometryFragSpirv,
                                       vk::Blend::None, forestGeometryPipeline_, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                                       vk::Depth::TestWrite), "ForestGeometry") &&
           named(vk_->graphicsPipeline(shadowPass_, 0, forestLayout_, kForestGeometryVertSpirv, kForestShadowFragSpirv,
                                       vk::Blend::None, forestShadowPipeline_, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                                       vk::Depth::TestWrite), "ForestShadow") &&
           named(vk_->graphicsPipeline(addPass_, 1, forestLayout_, kFullscreenVertSpirv, kForestLightFragSpirv,
                                       vk::Blend::Additive, forestLightPipeline_), "ForestLight") &&
           named(vk_->graphicsPipeline(particlePass_, 1, forestLayout_, kForestRainVertSpirv, kForestRainFragSpirv,
                                       vk::Blend::Additive, forestRainPipeline_), "ForestRain");
}

// The sun's shadow map: 16-bit depth, which every device can draw into and
// sample; nearest texels, compared in the shader's own disc of taps.
bool SceneRenderer::createForestShadow() {
    const VkExtent2D size{static_cast<uint32_t>(SHADOW_RES), static_cast<uint32_t>(SHADOW_RES)};
    if (!vk_->createImage(size, VK_FORMAT_D16_UNORM,
                          VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, shadow_)) {
        return false;
    }
    VkAttachmentDescription depth{};
    depth.format = VK_FORMAT_D16_UNORM;
    depth.samples = VK_SAMPLE_COUNT_1_BIT;
    depth.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    depth.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    depth.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    depth.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    depth.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    depth.finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
    if (!vk_->renderPass({}, false, shadowPass_, &depth)) return false;
    const std::array<VkImageView, 1> view{shadow_.view};
    if (!vk_->framebuffer(shadowPass_, view, size, shadowFramebuffer_)) return false;

    VkSamplerCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    info.magFilter = VK_FILTER_NEAREST;
    info.minFilter = VK_FILTER_NEAREST;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.compareEnable = VK_TRUE;
    info.compareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
    info.maxLod = 0.0f;
    VK_TRY(vkCreateSampler(vk_->device, &info, nullptr, &shadowSampler_));
    return true;
}

// The trunks and cards seen from the sun, cut to their outlines. Once: nothing moves.
void SceneRenderer::recordForestShadow(VkCommandBuffer cmd) {
    VkClearValue clear{};
    clear.depthStencil = {1.0f, 0};
    VkRenderPassBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = shadowPass_;
    begin.framebuffer = shadowFramebuffer_;
    begin.renderArea = {{0, 0}, shadow_.extent};
    begin.clearValueCount = 1;
    begin.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    const auto side = static_cast<float>(SHADOW_RES);
    const VkViewport viewport{0.0f, 0.0f, side, side, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, shadow_.extent};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestShadowPipeline_);
    ForestParams params{side, side, 0.0f, 0.0f, 0.0f, 0, 0, 0.0f};
    const VkShaderStageFlags stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    const int trees = FOREST_GRID * FOREST_GRID;
    params.part = 8 + 1;  // forest_params.glsl: PART_SHADOW + PART_TRUNKS
    vkCmdPushConstants(cmd, forestLayout_, stages, 0, sizeof(params), &params);
    vkCmdDraw(cmd, static_cast<uint32_t>(TRUNK_SIDES * TRUNK_RINGS * 6), static_cast<uint32_t>(trees), 0, 0);
    params.part = 8 + 2;  // PART_SHADOW + PART_CARDS
    vkCmdPushConstants(cmd, forestLayout_, stages, 0, sizeof(params), &params);
    vkCmdDraw(cmd, static_cast<uint32_t>(CARDS * 6), static_cast<uint32_t>(trees), 0, 0);
    params.part = 8 + 3;  // PART_SHADOW + PART_FERNS
    vkCmdPushConstants(cmd, forestLayout_, stages, 0, sizeof(params), &params);
    vkCmdDraw(cmd, static_cast<uint32_t>(FRONDS * FROND_SEGMENTS * 6), static_cast<uint32_t>(FERN_GRID * FERN_GRID), 0, 0);
    params.part = 8 + 4;  // PART_SHADOW + PART_LOGS
    vkCmdPushConstants(cmd, forestLayout_, stages, 0, sizeof(params), &params);
    vkCmdDraw(cmd, static_cast<uint32_t>(TRUNK_SIDES * LOG_RINGS * 6), static_cast<uint32_t>(LOGS), 0, 0);
    vkCmdEndRenderPass(cmd);
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &barrier,
                         0, nullptr, 0, nullptr);
}

// The forest: the sky behind everything, then the ground, the trunks and the
// leaf cards, each a draw whose vertex shader makes its own geometry.
void SceneRenderer::recordForest(VkCommandBuffer cmd, const FrameParams& frame) {
    if (!shadowDrawn_) {
        recordForestShadow(cmd);
        shadowDrawn_ = true;
    }
    std::array<VkClearValue, 3> clears{};
    clears[2].depthStencil = {1.0f, 0};
    VkRenderPassBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = forestPass_;
    begin.framebuffer = forestFramebuffer_;
    begin.renderArea = {{0, 0}, extent_};
    begin.clearValueCount = static_cast<uint32_t>(clears.size());
    begin.pClearValues = clears.data();
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    const VkViewport viewport{0.0f, 0.0f, frame.width, frame.height, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);

    ForestParams params{frame.width, frame.height, frame.jitterX, frame.jitterY, frame.time, frame.frame, 0, 0.0f};
    const VkShaderStageFlags stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    auto draw = [&](uint32_t part, int vertices, int instances) {
        params.part = part;
        vkCmdPushConstants(cmd, forestLayout_, stages, 0, sizeof(params), &params);
        vkCmdDraw(cmd, static_cast<uint32_t>(vertices), static_cast<uint32_t>(instances), 0, 0);
    };
    const int trees = FOREST_GRID * FOREST_GRID;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestSkyPipeline_);
    draw(0, 3, 1);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestGeometryPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestLayout_, 0, 1, &forestSurfaceSet_, 0, nullptr);
    draw(0, TERRAIN_N * 6, TERRAIN_N);                     // forest_params.glsl: PART_TERRAIN
    draw(1, TRUNK_SIDES * TRUNK_RINGS * 6, trees);         // PART_TRUNKS
    draw(2, CARDS * 6, trees);            // PART_CARDS
    draw(3, FRONDS * FROND_SEGMENTS * 6, FERN_GRID * FERN_GRID);  // PART_FERNS
    draw(4, TRUNK_SIDES * LOG_RINGS * 6, LOGS);                    // PART_LOGS
    vkCmdEndRenderPass(cmd);
    readable(cmd);

    // Sunbeams in the mist, added onto the colour.
    pass(cmd, addPass_, lightFramebuffer_, extent_);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestLightPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestLayout_, 0, 1, &forestLightSet_, 0, nullptr);
    draw(0, 3, 1);
    vkCmdEndRenderPass(cmd);
    readable(cmd);
}

// The forest's particle layer: rain, drawn as streaks in the pool's particle
// pass (cleared, added to), hidden behind the forest by its distances.
void SceneRenderer::recordRain(VkCommandBuffer cmd, const FrameParams& frame) {
    VkClearValue clear{};
    VkRenderPassBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    begin.renderPass = particlePass_;
    begin.framebuffer = particleFramebuffer_;
    begin.renderArea = {{0, 0}, extent_};
    begin.clearValueCount = 1;
    begin.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    const VkViewport viewport{0.0f, 0.0f, frame.width, frame.height, 0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestRainPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, forestLayout_, 0, 1, &forestLightSet_, 0, nullptr);
    const ForestParams params{frame.width, frame.height, 0.0f, 0.0f, frame.time, frame.frame, 0, 0.0f};
    vkCmdPushConstants(cmd, forestLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params),
                       &params);
    vkCmdDraw(cmd, 6, static_cast<uint32_t>(RAIN_DROPS), 0, 0);
    vkCmdEndRenderPass(cmd);
    readable(cmd);
}

void SceneRenderer::recordWater(VkCommandBuffer cmd, float time) {
    // The steps due by now, at a fixed rate; a slow frame catches up only so far.
    const auto due = static_cast<uint64_t>(static_cast<double>(std::max(time, 0.0f)) * kWaterHz);
    if (due > waterSteps_ + kMaxWaterSteps) waterSteps_ = due - kMaxWaterSteps;

    // Last frame's steps wrote heights these read; its scene read the surface written below.
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);

    constexpr uint32_t groups = kWaterCells / 8;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, waterStepPipeline_);
    for (; waterSteps_ < due; ++waterSteps_, ++waterTurn_) {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, waterLayout_, 0, 1, &waterSets_[waterTurn_ % 3], 0,
                                nullptr);
        const WaterParams params{static_cast<uint32_t>(waterSteps_), 0, 0, 0};
        vkCmdPushConstants(cmd, waterLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
        vkCmdDispatch(cmd, groups, groups, 1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                             0, nullptr, 0, nullptr);
    }
    // The set of the next turn sees the newest heights as "now".
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, waterSurfacePipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, waterLayout_, 0, 1, &waterSets_[waterTurn_ % 3], 0,
                            nullptr);
    vkCmdDispatch(cmd, groups, groups, 1);
    VkMemoryBarrier sampled{};
    sampled.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    sampled.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    sampled.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &sampled, 0,
                         nullptr, 0, nullptr);
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
                         particlePipeline_, waterStepPipeline_, waterSurfacePipeline_, dofPipeline_, forestSkyPipeline_,
                         forestGeometryPipeline_, forestShadowPipeline_, forestLightPipeline_, forestRainPipeline_}) {
        if (p) vkDestroyPipeline(d, p, nullptr);
    }
    if (forestLayout_) vkDestroyPipelineLayout(d, forestLayout_, nullptr);
    if (forestSetLayout_) vkDestroyDescriptorSetLayout(d, forestSetLayout_, nullptr);
    if (lightFramebuffer_) vkDestroyFramebuffer(d, lightFramebuffer_, nullptr);
    if (shadowFramebuffer_) vkDestroyFramebuffer(d, shadowFramebuffer_, nullptr);
    if (shadowPass_) vkDestroyRenderPass(d, shadowPass_, nullptr);
    if (shadowSampler_) vkDestroySampler(d, shadowSampler_, nullptr);
    vk_->destroy(shadow_);
    if (forestFramebuffer_) vkDestroyFramebuffer(d, forestFramebuffer_, nullptr);
    if (forestPass_) vkDestroyRenderPass(d, forestPass_, nullptr);
    vk_->destroy(depth_);
    if (waterLayout_) vkDestroyPipelineLayout(d, waterLayout_, nullptr);
    if (waterSetLayout_) vkDestroyDescriptorSetLayout(d, waterSetLayout_, nullptr);
    if (sceneSetLayout_) vkDestroyDescriptorSetLayout(d, sceneSetLayout_, nullptr);
    for (auto& h : waterHeights_) vk_->destroy(h);
    vk_->destroy(waterSurface_);
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
    if (dofFramebuffer_) vkDestroyFramebuffer(d, dofFramebuffer_, nullptr);
    for (VkRenderPass p : {scenePass_, writePass_, addPass_}) {
        if (p) vkDestroyRenderPass(d, p, nullptr);
    }
    if (sampler_) vkDestroySampler(d, sampler_, nullptr);
    vk_->destroy(color_);
    vk_->destroy(distance_);
    for (auto& h : history_) vk_->destroy(h);
    for (auto& b : bloom_) vk_->destroy(b);
    vk_->destroy(dof_);
    *this = SceneRenderer{};
}

}  // namespace stress
