#include "gpu/vk.h"

#include <array>
#include <vector>

namespace stress::vk {

void transition(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess,
                VkAccessFlags dstAccess, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = from;
    barrier.newLayout = to;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

bool Context::memoryType(uint32_t bits, VkMemoryPropertyFlags wanted, uint32_t& out) const {
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & wanted) == wanted) {
            out = i;
            return true;
        }
    }
    return false;
}

bool Context::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                           std::initializer_list<VkMemoryPropertyFlags> preferences, Buffer& out) const {
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_TRY(vkCreateBuffer(device, &info, nullptr, &out.buffer));
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(device, out.buffer, &requirements);
    uint32_t type = 0;
    VkMemoryPropertyFlags chosen = 0;
    bool found = false;
    for (VkMemoryPropertyFlags wanted : preferences) {
        if (memoryType(requirements.memoryTypeBits, wanted, type)) {
            chosen = wanted;
            found = true;
            break;
        }
    }
    if (!found) {
        LOGE("no memory type for buffer");
        return false;
    }
    VkMemoryAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = type;
    VK_TRY(vkAllocateMemory(device, &allocate, nullptr, &out.memory));
    VK_TRY(vkBindBufferMemory(device, out.buffer, out.memory, 0));
    if (chosen & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
        VK_TRY(vkMapMemory(device, out.memory, 0, VK_WHOLE_SIZE, 0, &out.mapped));
    }
    return true;
}

bool Context::createImage(VkExtent2D size, VkFormat format, VkImageUsageFlags usage, Image& out) const {
    VkImageCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {size.width, size.height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(vkCreateImage(device, &info, nullptr, &out.image));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(device, out.image, &requirements);
    uint32_t type = 0;
    if (!memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, type)) return false;
    VkMemoryAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = type;
    VK_TRY(vkAllocateMemory(device, &allocate, nullptr, &out.memory));
    VK_TRY(vkBindImageMemory(device, out.image, out.memory, 0));
    out.extent = size;
    out.format = format;
    return createView(out.image, format, out.view);
}

bool Context::createView(VkImage image, VkFormat format, VkImageView& out) const {
    VkImageViewCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    info.image = image;
    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    info.format = format;
    info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(device, &info, nullptr, &out));
    return true;
}

bool Context::shaderModule(std::span<const uint32_t> code, VkShaderModule& out) const {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = code.size_bytes();
    info.pCode = code.data();
    VK_TRY(vkCreateShaderModule(device, &info, nullptr, &out));
    return true;
}

void Context::destroy(Buffer& b) const {
    if (b.mapped) vkUnmapMemory(device, b.memory);
    if (b.buffer) vkDestroyBuffer(device, b.buffer, nullptr);
    if (b.memory) vkFreeMemory(device, b.memory, nullptr);
    b = Buffer{};
}

void Context::destroy(Image& i) const {
    if (i.view) vkDestroyImageView(device, i.view, nullptr);
    if (i.image) vkDestroyImage(device, i.image, nullptr);
    if (i.memory) vkFreeMemory(device, i.memory, nullptr);
    i = Image{};
}

bool Context::renderPass(std::span<const VkAttachmentDescription> colors, bool readBefore, VkRenderPass& out) const {
    std::vector<VkAttachmentReference> references;
    for (uint32_t i = 0; i < colors.size(); ++i) references.push_back({i, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL});
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = static_cast<uint32_t>(references.size());
    subpass.pColorAttachments = references.data();
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask =
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | (readBefore ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : 0);
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = static_cast<uint32_t>(colors.size());
    info.pAttachments = colors.data();
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dependency;
    VK_TRY(vkCreateRenderPass(device, &info, nullptr, &out));
    return true;
}

bool Context::framebuffer(VkRenderPass pass, std::span<const VkImageView> views, VkExtent2D extent,
                          VkFramebuffer& out) const {
    VkFramebufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    info.renderPass = pass;
    info.attachmentCount = static_cast<uint32_t>(views.size());
    info.pAttachments = views.data();
    info.width = extent.width;
    info.height = extent.height;
    info.layers = 1;
    VK_TRY(vkCreateFramebuffer(device, &info, nullptr, &out));
    return true;
}

bool Context::graphicsPipeline(VkRenderPass pass, uint32_t subpassColors, VkPipelineLayout layout,
                               std::span<const uint32_t> vertex, std::span<const uint32_t> fragment, Blend blend,
                               VkPipeline& out) const {
    VkShaderModule vert, frag;
    if (!shaderModule(vertex, vert)) return false;
    if (!shaderModule(fragment, frag)) {
        vkDestroyShaderModule(device, vert, nullptr);
        return false;
    }
    std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo assembly{};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{};
    viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    std::vector<VkPipelineColorBlendAttachmentState> blends(subpassColors);
    for (auto& b : blends) {
        b.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        if (blend == Blend::Additive) {
            b.blendEnable = VK_TRUE;
            b.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
            b.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
            b.colorBlendOp = VK_BLEND_OP_ADD;
            b.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            b.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            b.alphaBlendOp = VK_BLEND_OP_ADD;
        }
    }
    VkPipelineColorBlendStateCreateInfo blendState{};
    blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blendState.attachmentCount = subpassColors;
    blendState.pAttachments = blends.data();
    const VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamics;

    VkGraphicsPipelineCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = static_cast<uint32_t>(stages.size());
    info.pStages = stages.data();
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blendState;
    info.pDynamicState = &dynamic;
    info.layout = layout;
    info.renderPass = pass;
    const VkResult result = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &out);
    vkDestroyShaderModule(device, vert, nullptr);
    vkDestroyShaderModule(device, frag, nullptr);
    VK_TRY(result);
    return true;
}

bool Context::linearClampSampler(VkSampler& out) const {
    VkSamplerCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    info.magFilter = VK_FILTER_LINEAR;
    info.minFilter = VK_FILTER_LINEAR;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VK_TRY(vkCreateSampler(device, &info, nullptr, &out));
    return true;
}

}  // namespace stress::vk
