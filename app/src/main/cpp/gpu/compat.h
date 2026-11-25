#pragma once

#include <vulkan/vulkan.h>

#include <algorithm>
#include <span>

namespace stress::vk {

// Two or more small subgroups per group, with fewer live registers than a
// 64-lane group. Keep the measured 64-lane recipe on wide-subgroup devices.
constexpr uint32_t burnerGroupSize(uint32_t subgroup, uint32_t maxX, uint32_t maxInvocations) {
    return std::min({subgroup > 0 && subgroup <= 32 ? 32u : 64u, maxX, maxInvocations});
}

constexpr bool srgbAttachment(VkFormat format) {
    return format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB ||
           format == VK_FORMAT_A8B8G8R8_SRGB_PACK32;
}

constexpr VkSurfaceFormatKHR surfaceFormat(std::span<const VkSurfaceFormatKHR> formats) {
    // The shaders produce SDR for the sRGB color space. Prefer its 8-bit
    // formats even when the surface lists wide-gamut or 10-bit formats first.
    for (VkFormat wanted : {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_A8B8G8R8_UNORM_PACK32,
                            VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_A8B8G8R8_SRGB_PACK32,
                            VK_FORMAT_A2R10G10B10_UNORM_PACK32, VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                            VK_FORMAT_R5G6B5_UNORM_PACK16, VK_FORMAT_B5G6R5_UNORM_PACK16,
                            VK_FORMAT_R16G16B16A16_SFLOAT}) {
        for (auto f : formats) {
            if (f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR && f.format == wanted) return f;
        }
    }
    if (formats.size() == 1 && formats[0].format == VK_FORMAT_UNDEFINED &&
        formats[0].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
        return {VK_FORMAT_R8G8B8A8_UNORM, formats[0].colorSpace};
    }
    return {VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
}

constexpr VkCompositeAlphaFlagBitsKHR compositeAlpha(VkCompositeAlphaFlagsKHR flags) {
    for (auto mode : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                      VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if (flags & mode) return mode;
    }
    return {};
}

constexpr bool quarterTurn(VkSurfaceTransformFlagBitsKHR transform) {
    return transform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ||
           transform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR ||
           transform == VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR ||
           transform == VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR;
}

static_assert(burnerGroupSize(128, 1024, 1024) == 64);
static_assert(burnerGroupSize(16, 128, 128) == 32);
static_assert(burnerGroupSize(32, 128, 128) == 32);
static_assert(burnerGroupSize(0, 128, 128) == 64);
static_assert(compositeAlpha(VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) == VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR);
constexpr VkSurfaceFormatKHR kSrgbOnly[] = {{VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR}};
static_assert(srgbAttachment(surfaceFormat(kSrgbOnly).format));
constexpr VkSurfaceFormatKHR kMixedFormats[] = {{VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
                                               {VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR}};
static_assert(surfaceFormat(kMixedFormats).format == VK_FORMAT_B8G8R8A8_UNORM);

}  // namespace stress::vk
