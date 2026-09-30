#pragma once

// Small Vulkan helpers shared by the renderer's parts: allocation, render
// passes, pipelines. Every function logs and returns false on failure.

#include <vulkan/vulkan.h>

#include <cstdint>
#include <initializer_list>
#include <span>

#include "common/log.h"

#define VK_TRY(expr)                                                  \
    do {                                                              \
        const VkResult result_ = (expr);                              \
        if (result_ != VK_SUCCESS) {                                  \
            LOGE("%s failed: %d", #expr, static_cast<int>(result_));  \
            return false;                                             \
        }                                                             \
    } while (0)

namespace stress::vk {

struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
};

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkExtent2D extent{};
    VkFormat format = VK_FORMAT_UNDEFINED;
};

enum class Blend { None, Additive };

/** Records one image layout transition. */
void transition(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess,
                VkAccessFlags dstAccess, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage);

/** The device and what allocating on it needs. Plain handles: whoever creates it destroys the device. */
struct Context {
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory{};
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;

    bool memoryType(uint32_t bits, VkMemoryPropertyFlags wanted, uint32_t& out) const;

    /** Tries each memory preference in order; host-visible memory is mapped. */
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, std::initializer_list<VkMemoryPropertyFlags> preferences,
                      Buffer& out) const;
    bool createImage(VkExtent2D size, VkFormat format, VkImageUsageFlags usage, Image& out) const;
    bool createView(VkImage image, VkFormat format, VkImageView& out) const;
    bool shaderModule(std::span<const uint32_t> code, VkShaderModule& out) const;
    void destroy(Buffer& buffer) const;
    void destroy(Image& image) const;

    /**
     * A single-subpass render pass over [colors]. `readBefore` makes it wait
     * for earlier fragment-shader reads of its targets (a target sampled by
     * the previous frame and drawn again now).
     */
    bool renderPass(std::span<const VkAttachmentDescription> colors, bool readBefore, VkRenderPass& out) const;

    bool framebuffer(VkRenderPass pass, std::span<const VkImageView> views, VkExtent2D extent, VkFramebuffer& out) const;

    /** A full-screen-triangle pipeline: `vertex` draws the triangle, `fragment` shades it. Viewport is dynamic. */
    bool graphicsPipeline(VkRenderPass pass, uint32_t subpassColors, VkPipelineLayout layout, std::span<const uint32_t> vertex,
                          std::span<const uint32_t> fragment, Blend blend, VkPipeline& out) const;

    bool linearClampSampler(VkSampler& out) const;

    /** Records and runs `record` once, waiting for it: for uploads during setup. */
    template <typename Fn>
    bool runOnce(Fn&& record) const {
        VkCommandBufferAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = commandPool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = 1;
        VkCommandBuffer cmd;
        VK_TRY(vkAllocateCommandBuffers(device, &allocate, &cmd));
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        VK_TRY(vkBeginCommandBuffer(cmd, &begin));
        record(cmd);
        VK_TRY(vkEndCommandBuffer(cmd));
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cmd;
        VK_TRY(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE));
        VK_TRY(vkQueueWaitIdle(queue));
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        return true;
    }
};

}  // namespace stress::vk
