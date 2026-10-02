#include "gpu/gpu_load.h"

#include <pthread.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#include "common/log.h"
#include "gpu/scene_renderer.h"
#include "gpu/vk.h"
#include "shaders/alu_fp16_comp.h"
#include "shaders/alu_fp32_comp.h"
#include "shaders/bandwidth_comp.h"
#include "shaders/blend_frag.h"
#include "shaders/fullscreen_vert.h"
#include "shaders/preview_frag.h"
#include "shaders/texture_comp.h"

namespace stress {
namespace {

enum Burner : int { kFp32 = 0, kFp16 = 1, kTexture = 2, kBandwidth = 3, kBlend = 4 };

constexpr std::array<GpuBurnerSpec, 5> kBurners{{
    {"gpu_fp32", "G1", "FLOP", true},
    {"gpu_fp16", "G2", "FLOP", true},
    {"gpu_texture", "G3", "TEXEL", true},
    {"gpu_bandwidth", "G4", "B", true},
    {"gpu_blend", "G5", "PIXEL", false},
}};

// Dispatch geometry, sized so one dispatch takes about a millisecond: short
// enough that no single piece of work nears the driver's hang detection.
constexpr uint32_t kGroupSize = 64;
constexpr uint32_t kAluGroups = 512;
constexpr uint32_t kAluIterations = 256;
constexpr double kAluFlopPerIteration = 128;
constexpr uint32_t kTextureGroups = 512;
// Each sample lands on a different cache line of a 16 MiB texture, so this one
// is bound by memory: 8 iterations already take ~12 ms on the Adreno 720.
constexpr uint32_t kTextureIterations = 8;
constexpr double kSamplesPerIteration = 4;
constexpr uint32_t kBandwidthGroups = 2048;
constexpr uint32_t kBandwidthIterations = 16;
constexpr VkDeviceSize kBandwidthBytes = VkDeviceSize{kBandwidthGroups} * kGroupSize * kBandwidthIterations * 16;
constexpr uint32_t kBlendLayers = 4;
constexpr uint32_t kTextureSize = 2048;

constexpr uint32_t kFramesInFlight = 3;
constexpr uint32_t kStampsPerFrame = 3;  // frame start, burner done, visible pass done
constexpr uint32_t kMaxDispatches = 256;
constexpr uint32_t kMaxGroups = 2048;  // result slots reserved per dispatch

static_assert(kBandwidthBytes == 32u * 1024 * 1024, "bandwidth buffers are meant to be 32 MiB");

int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

// SplitMix64, as for the CPU operands: fills buffers with bits that change.
void fillRandom(void* data, size_t bytes, uint64_t seed) {
    auto* out = static_cast<uint8_t*>(data);
    uint64_t state = seed;
    for (size_t i = 0; i < bytes; i += 8) {
        uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
        z ^= z >> 31;
        std::memcpy(out + i, &z, std::min<size_t>(8, bytes - i));
    }
}

struct PreviewParams {
    float width;
    float height;
    float time;
    float load;
};

struct ComputeParams {
    uint32_t iterations;
    uint32_t resultOffset;
};

using vk::Buffer;
using vk::Image;

}  // namespace

std::span<const GpuBurnerSpec> gpuBurnerTable() { return kBurners; }

struct GpuLoad::Renderer {
    ANativeWindow* window = nullptr;
    int burner = -1;
    int64_t targetFrameNanos = 0;
    bool scene = true;        // draw a scene; otherwise the cheap preview ring
    SceneRenderer::Kind sceneKind = SceneRenderer::Kind::Pool;
    SceneRenderer::Quality sceneQuality = SceneRenderer::Quality::Medium;
    float sceneScale = 0.5f;  // scene resolution relative to the screen
    uint32_t instanceVersion = VK_API_VERSION_1_1;
    bool timed = false;            // GPU timestamps available (else frames are timed on the CPU)
    int64_t lastCollectNanos = 0;  // when the previous frame was read back, for CPU timing

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    double timestampPeriodNanos = 1.0;
    uint64_t timestampMask = ~0ULL;

    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D extent{};
    VkRenderPass presentPass = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> framebuffers;
    std::vector<VkSemaphore> renderDone;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, kFramesInFlight> commands{};
    std::array<VkFence, kFramesInFlight> fences{};
    std::array<VkSemaphore, kFramesInFlight> acquired{};
    std::array<uint32_t, kFramesInFlight> dispatchesInFlight{};
    std::array<bool, kFramesInFlight> submitted{};
    VkQueryPool queries = VK_NULL_HANDLE;

    Buffer results;
    Buffer source;
    Buffer destination;
    Image noise;
    VkSampler sampler = VK_NULL_HANDLE;
    Image blendTarget;
    VkRenderPass blendPass = VK_NULL_HANDLE;
    VkFramebuffer blendFramebuffer = VK_NULL_HANDLE;

    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout computeLayout = VK_NULL_HANDLE;
    VkPipelineLayout graphicsLayout = VK_NULL_HANDLE;
    VkPipeline burnerPipeline = VK_NULL_HANDLE;
    VkPipeline previewPipeline = VK_NULL_HANDLE;

    vk::Context vk;
    SceneRenderer sceneRenderer;

    uint32_t groups = 0;
    uint32_t iterations = 0;
    double workPerDispatch = 0;
    uint32_t perFrame = 1;
    double perFrameExact = 1.0;  // smoothed ideal dispatch count, see collect()
    uint64_t frameIndex = 0;
    std::vector<uint32_t> golden;
    int64_t startNanos = 0;

    pthread_t thread{};
    bool threadStarted = false;
    std::atomic<bool> stopRequested{false};

    // Initialisation runs on the render thread; start() waits for its outcome.
    std::mutex initMutex;
    std::condition_variable initDone;
    int initResult = -1;  // -1 pending, 0 ok, 1 failed

    std::atomic<int> state{kStateIdle};
    std::atomic<int64_t> frames{0};
    std::atomic<int64_t> dispatches{0};
    std::atomic<int64_t> work{0};
    std::atomic<int64_t> gpuNanos{0};
    std::atomic<int64_t> burnerTotalNanos{0};
    std::atomic<int64_t> visibleTotalNanos{0};
    std::atomic<int64_t> lastFrameNanos{0};
    std::atomic<int64_t> dispatchesPerFrame{0};
    std::atomic<int64_t> errors{0};
    std::atomic<int64_t> checks{0};
    std::atomic<uint32_t> width{0};
    std::atomic<uint32_t> height{0};

    static void* threadMain(void* arg) {
        static_cast<Renderer*>(arg)->run();
        return nullptr;
    }

    void run() {
        // Above the burner threads, so a full CPU load cannot starve the GPU of work.
        setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -8);
        const bool ok = init();
        {
            std::lock_guard lock(initMutex);
            initResult = ok ? 0 : 1;
        }
        initDone.notify_all();
        if (!ok) {
            state.store(kStateFailed);
            return;
        }
        state.store(kStateRunning);
        startNanos = nowNanos();
        while (!stopRequested.load(std::memory_order_relaxed)) {
            if (!frame()) break;
        }
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
    }

    // ---- setup ----------------------------------------------------------------

    bool init() {
        // The loader of a phone may be older than its driver: ask for the
        // newer of 1.1 and what the loader knows, up to 1.2.
        uint32_t loader = VK_API_VERSION_1_0;
        if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS) loader = VK_API_VERSION_1_0;
        instanceVersion = std::min<uint32_t>(std::max<uint32_t>(loader, VK_API_VERSION_1_1), VK_API_VERSION_1_2);
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "stress";
        app.apiVersion = instanceVersion;
        const char* instanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &app;
        instanceInfo.enabledExtensionCount = 2;
        instanceInfo.ppEnabledExtensionNames = instanceExtensions;
        VK_TRY(vkCreateInstance(&instanceInfo, nullptr, &instance));

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window;
        VK_TRY(vkCreateAndroidSurfaceKHR(instance, &surfaceInfo, nullptr, &surface));

        uint32_t count = 1;
        VkResult enumerated = vkEnumeratePhysicalDevices(instance, &count, &physical);
        if ((enumerated != VK_SUCCESS && enumerated != VK_INCOMPLETE) || count == 0) {
            LOGE("no Vulkan device");
            return false;
        }
        VkPhysicalDeviceProperties properties;
        vkGetPhysicalDeviceProperties(physical, &properties);
        vkGetPhysicalDeviceMemoryProperties(physical, &memoryProperties);
        timestampPeriodNanos = properties.limits.timestampPeriod;
        const uint32_t deviceVersion = std::min(properties.apiVersion, instanceVersion);
        if (deviceVersion < VK_API_VERSION_1_1) {
            LOGE("%s has Vulkan %u.%u; 1.1 is needed", properties.deviceName, VK_API_VERSION_MAJOR(properties.apiVersion),
                 VK_API_VERSION_MINOR(properties.apiVersion));
            return false;
        }

        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &familyCount, families.data());
        // A family that can draw, compute and present; one with timestamps if
        // there is one. Without them frames are timed on the CPU (collect()).
        bool foundFamily = false;
        for (uint32_t i = 0; i < familyCount; ++i) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(physical, i, surface, &present);
            const VkQueueFlags need = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            if ((families[i].queueFlags & need) != need || !present) continue;
            const bool stamps = families[i].timestampValidBits > 0 && timestampPeriodNanos > 0.0;
            if (foundFamily && (timed || !stamps)) continue;
            queueFamily = i;
            timed = stamps;
            if (stamps) {
                timestampMask = families[i].timestampValidBits >= 64 ? ~0ULL : (1ULL << families[i].timestampValidBits) - 1;
            }
            foundFamily = true;
        }
        if (!foundFamily) {
            LOGE("no queue family with graphics, compute and present");
            return false;
        }
        if (!timed) LOGW("no GPU timestamps: frames are timed on the CPU");

        // FP16 arithmetic (G2 only): a Vulkan 1.2 feature, or on 1.1 the extension it came from.
        const bool vulkan12 = deviceVersion >= VK_API_VERSION_1_2;
        const bool float16Extension = !vulkan12 && hasDeviceExtension(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
        VkPhysicalDeviceVulkan12Features supported12{};
        supported12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        VkPhysicalDeviceShaderFloat16Int8Features supportedFloat16{};
        supportedFloat16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        VkPhysicalDeviceFeatures2 supported{};
        supported.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        supported.pNext = vulkan12 ? static_cast<void*>(&supported12)
                                   : float16Extension ? static_cast<void*>(&supportedFloat16) : nullptr;
        vkGetPhysicalDeviceFeatures2(physical, &supported);
        const bool float16 = vulkan12 ? supported12.shaderFloat16 == VK_TRUE : supportedFloat16.shaderFloat16 == VK_TRUE;
        if (burner == kFp16 && !float16) {
            LOGE("FP16 arithmetic not supported");
            return false;
        }
        VkPhysicalDeviceVulkan12Features enabled12{};
        enabled12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        enabled12.shaderFloat16 = float16 ? VK_TRUE : VK_FALSE;
        VkPhysicalDeviceShaderFloat16Int8Features enabledFloat16{};
        enabledFloat16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        enabledFloat16.shaderFloat16 = float16 ? VK_TRUE : VK_FALSE;
        VkPhysicalDeviceFeatures enabledFeatures{};
        // Particle sprites; without the feature they are drawn a pixel wide.
        enabledFeatures.largePoints = supported.features.largePoints;
        if (!supported.features.largePoints) LOGW("large points not supported: particles are drawn a pixel wide");

        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamily;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        std::vector<const char*> deviceExtensions{VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        if (float16Extension && float16) deviceExtensions.push_back(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.pNext = vulkan12 ? static_cast<const void*>(&enabled12)
                                    : float16Extension && float16 ? static_cast<const void*>(&enabledFloat16) : nullptr;
        deviceInfo.pEnabledFeatures = &enabledFeatures;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
        deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();
        VK_TRY(vkCreateDevice(physical, &deviceInfo, nullptr, &device));
        vkGetDeviceQueue(device, queueFamily, 0, &queue);
        LOGI("GPU %s, API %u.%u, timestamp %.2f ns", properties.deviceName, VK_API_VERSION_MAJOR(properties.apiVersion),
             VK_API_VERSION_MINOR(properties.apiVersion), timestampPeriodNanos);

        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamily;
        VK_TRY(vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool));
        vk.device = device;
        vk.memory = memoryProperties;
        vk.queue = queue;
        vk.commandPool = commandPool;
        vk.largePoints = supported.features.largePoints == VK_TRUE;
        vk.depthFormat = VK_FORMAT_UNDEFINED;
        for (VkFormat candidate : vk::kDepthFormats) {
            VkFormatProperties formatProperties;
            vkGetPhysicalDeviceFormatProperties(physical, candidate, &formatProperties);
            if (formatProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
                vk.depthFormat = candidate;
                break;
            }
        }
        if (vk.depthFormat == VK_FORMAT_UNDEFINED) {
            LOGE("no depth format to draw into");
            return false;
        }

        return createPresentPass() && createSwapchain() && createFrameResources() && createBurnerResources() &&
               createPipelines();
    }

    bool hasDeviceExtension(const char* name) const {
        uint32_t count = 0;
        if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS) return false;
        std::vector<VkExtensionProperties> extensions(count);
        if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
        for (const auto& e : extensions) {
            if (std::strcmp(e.extensionName, name) == 0) return true;
        }
        return false;
    }

    bool createPresentPass() {
        VkSurfaceCapabilitiesKHR caps;
        VK_TRY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps));
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, formats.data());
        if (formats.empty()) return false;
        // The final pass writes display-ready (gamma-encoded) values, so an
        // sRGB format, which would encode them again, comes last.
        format = formats[0].format == VK_FORMAT_UNDEFINED ? VK_FORMAT_R8G8B8A8_UNORM : formats[0].format;
        colorSpace = formats[0].colorSpace;
        for (VkFormat wanted : {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}) {
            for (const auto& f : formats) {
                if (f.format == wanted && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    format = f.format;
                    colorSpace = f.colorSpace;
                }
            }
        }

        VkAttachmentDescription color{};
        color.format = format;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;  // the visible pass covers every pixel
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        return vk.renderPass(std::span(&color, 1), false, presentPass);
    }

    bool createSwapchain() {
        VkSurfaceCapabilitiesKHR caps;
        VK_TRY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps));
        extent = caps.currentExtent;
        if (extent.width == 0 || extent.height == 0) return false;
        width.store(extent.width, std::memory_order_relaxed);
        height.store(extent.height, std::memory_order_relaxed);
        uint32_t imageCount = std::max<uint32_t>(caps.minImageCount, 3);
        if (caps.maxImageCount > 0) imageCount = std::min(imageCount, caps.maxImageCount);
        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        for (VkCompositeAlphaFlagBitsKHR candidate : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}) {
            if (caps.supportedCompositeAlpha & candidate) {
                alpha = candidate;
                break;
            }
        }

        VkSwapchainKHR old = swapchain;
        VkSwapchainCreateInfoKHR info{};
        info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        info.surface = surface;
        info.minImageCount = imageCount;
        info.imageFormat = format;
        info.imageColorSpace = colorSpace;
        info.imageExtent = extent;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = caps.currentTransform;
        info.compositeAlpha = alpha;
        info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        info.clipped = VK_TRUE;
        info.oldSwapchain = old;
        VK_TRY(vkCreateSwapchainKHR(device, &info, nullptr, &swapchain));
        destroySwapchainViews();
        if (old != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, old, nullptr);

        uint32_t count = 0;
        vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr);
        std::vector<VkImage> images(count);
        vkGetSwapchainImagesKHR(device, swapchain, &count, images.data());
        for (VkImage image : images) {
            VkImageView view;
            if (!vk.createView(image, format, view)) return false;
            views.push_back(view);
            VkFramebufferCreateInfo fb{};
            fb.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fb.renderPass = presentPass;
            fb.attachmentCount = 1;
            fb.pAttachments = &view;
            fb.width = extent.width;
            fb.height = extent.height;
            fb.layers = 1;
            VkFramebuffer framebuffer;
            VK_TRY(vkCreateFramebuffer(device, &fb, nullptr, &framebuffer));
            framebuffers.push_back(framebuffer);
            VkSemaphoreCreateInfo semaphoreInfo{};
            semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            VkSemaphore semaphore;
            VK_TRY(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &semaphore));
            renderDone.push_back(semaphore);
        }
        return true;
    }

    void destroySwapchainViews() {
        for (VkFramebuffer f : framebuffers) vkDestroyFramebuffer(device, f, nullptr);
        for (VkImageView v : views) vkDestroyImageView(device, v, nullptr);
        for (VkSemaphore s : renderDone) vkDestroySemaphore(device, s, nullptr);
        framebuffers.clear();
        views.clear();
        renderDone.clear();
    }

    bool createFrameResources() {
        VkCommandBufferAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = commandPool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = kFramesInFlight;
        VK_TRY(vkAllocateCommandBuffers(device, &allocate, commands.data()));
        for (uint32_t i = 0; i < kFramesInFlight; ++i) {
            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            VK_TRY(vkCreateFence(device, &fenceInfo, nullptr, &fences[i]));
            VkSemaphoreCreateInfo semaphoreInfo{};
            semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            VK_TRY(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &acquired[i]));
        }
        if (!timed) return true;
        VkQueryPoolCreateInfo queryInfo{};
        queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
        queryInfo.queryCount = kStampsPerFrame * kFramesInFlight;
        VK_TRY(vkCreateQueryPool(device, &queryInfo, nullptr, &queries));
        return true;
    }

    bool createBurnerResources() {
        const VkDeviceSize resultBytes = VkDeviceSize{kFramesInFlight} * kMaxDispatches * kMaxGroups * sizeof(uint32_t);
        if (!vk.createBuffer(resultBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                          {VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                               VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                           VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT},
                          results)) {
            return false;
        }

        switch (burner) {
            case kFp32:
            case kFp16:
                groups = kAluGroups;
                iterations = kAluIterations;
                workPerDispatch = double{kAluGroups} * kGroupSize * kAluIterations * kAluFlopPerIteration;
                break;
            case kTexture:
                groups = kTextureGroups;
                iterations = kTextureIterations;
                workPerDispatch = double{kTextureGroups} * kGroupSize * kTextureIterations * kSamplesPerIteration;
                if (!createNoiseTexture()) return false;
                break;
            case kBandwidth:
                groups = kBandwidthGroups;
                iterations = kBandwidthIterations;
                workPerDispatch = 2.0 * static_cast<double>(kBandwidthBytes);
                if (!createBandwidthBuffers()) return false;
                break;
            case kBlend:
                workPerDispatch = double{kBlendLayers} * extent.width * extent.height;
                if (!createBlendTarget()) return false;
                break;
            default:
                break;
        }
        return true;
    }

    bool createStaging(VkDeviceSize bytes, uint64_t seed, Buffer& staging) {
        if (!vk.createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                          {VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT}, staging)) {
            return false;
        }
        fillRandom(staging.mapped, static_cast<size_t>(bytes), seed);
        return true;
    }

    bool createNoiseTexture() {
        const VkDeviceSize bytes = VkDeviceSize{kTextureSize} * kTextureSize * 4;
        Buffer staging;
        if (!createStaging(bytes, 0x7E47'0001, staging)) return false;
        if (!vk.createImage({kTextureSize, kTextureSize}, VK_FORMAT_R8G8B8A8_UNORM,
                         VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, noise)) {
            vk.destroy(staging);
            return false;
        }
        const bool ok = vk.runOnce([&](VkCommandBuffer cmd) {
            vk::transition(cmd, noise.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                       VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy region{};
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {kTextureSize, kTextureSize, 1};
            vkCmdCopyBufferToImage(cmd, staging.buffer, noise.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
            vk::transition(cmd, noise.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                       VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        });
        vk.destroy(staging);
        if (!ok) return false;

        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.maxLod = 0.0f;
        VK_TRY(vkCreateSampler(device, &samplerInfo, nullptr, &sampler));
        return true;
    }

    bool createBandwidthBuffers() {
        Buffer staging;
        if (!createStaging(kBandwidthBytes, 0xBA4D'0001, staging)) return false;
        const VkMemoryPropertyFlags local = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        const bool ok =
            vk.createBuffer(kBandwidthBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, {local},
                         source) &&
            vk.createBuffer(kBandwidthBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, {local}, destination) &&
            vk.runOnce([&](VkCommandBuffer cmd) {
                VkBufferCopy copy{0, 0, kBandwidthBytes};
                vkCmdCopyBuffer(cmd, staging.buffer, source.buffer, 1, &copy);
            });
        vk.destroy(staging);
        return ok;
    }

    bool createBlendTarget() {
        if (!vk.createImage(extent, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, blendTarget)) {
            return false;
        }
        VkAttachmentDescription color{};
        color.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        color.storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        if (!vk.renderPass(std::span(&color, 1), false, blendPass)) return false;
        VkFramebufferCreateInfo fb{};
        fb.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fb.renderPass = blendPass;
        fb.attachmentCount = 1;
        fb.pAttachments = &blendTarget.view;
        fb.width = extent.width;
        fb.height = extent.height;
        fb.layers = 1;
        VK_TRY(vkCreateFramebuffer(device, &fb, nullptr, &blendFramebuffer));
        return true;
    }

    bool createPipelines() {
        // One descriptor layout serves every compute burner; each uses the bindings it needs.
        std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
        const VkDescriptorType types[] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                          VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
        for (uint32_t i = 0; i < bindings.size(); ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = types[i];
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();
        VK_TRY(vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &setLayout));

        std::array<VkDescriptorPoolSize, 2> sizes{{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3},
                                                   {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}}};
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = static_cast<uint32_t>(sizes.size());
        poolInfo.pPoolSizes = sizes.data();
        VK_TRY(vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool));
        VkDescriptorSetAllocateInfo setInfo{};
        setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        setInfo.descriptorPool = descriptorPool;
        setInfo.descriptorSetCount = 1;
        setInfo.pSetLayouts = &setLayout;
        VK_TRY(vkAllocateDescriptorSets(device, &setInfo, &descriptorSet));
        writeDescriptors();

        VkPushConstantRange computePush{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ComputeParams)};
        VkPipelineLayoutCreateInfo computeLayoutInfo{};
        computeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        computeLayoutInfo.setLayoutCount = 1;
        computeLayoutInfo.pSetLayouts = &setLayout;
        computeLayoutInfo.pushConstantRangeCount = 1;
        computeLayoutInfo.pPushConstantRanges = &computePush;
        VK_TRY(vkCreatePipelineLayout(device, &computeLayoutInfo, nullptr, &computeLayout));

        VkPushConstantRange graphicsPush{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PreviewParams)};
        VkPipelineLayoutCreateInfo graphicsLayoutInfo{};
        graphicsLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        graphicsLayoutInfo.pushConstantRangeCount = 1;
        graphicsLayoutInfo.pPushConstantRanges = &graphicsPush;
        VK_TRY(vkCreatePipelineLayout(device, &graphicsLayoutInfo, nullptr, &graphicsLayout));

        switch (burner) {
            case kFp32: if (!createComputePipeline(kAluFp32CompSpirv)) return false; break;
            case kFp16: if (!createComputePipeline(kAluFp16CompSpirv)) return false; break;
            case kTexture: if (!createComputePipeline(kTextureCompSpirv)) return false; break;
            case kBandwidth: if (!createComputePipeline(kBandwidthCompSpirv)) return false; break;
            case kBlend:
                if (!vk.graphicsPipeline(blendPass, 1, graphicsLayout, kFullscreenVertSpirv, kBlendFragSpirv, vk::Blend::Additive,
                                         burnerPipeline)) {
                    return false;
                }
                break;
            default: break;
        }
        if (!scene) {
            return vk.graphicsPipeline(presentPass, 1, graphicsLayout, kFullscreenVertSpirv, kPreviewFragSpirv, vk::Blend::None,
                                       previewPipeline);
        }
        return sceneRenderer.init(vk, extent, sceneScale, presentPass, sceneKind, sceneQuality);
    }

    void writeDescriptors() {
        std::vector<VkWriteDescriptorSet> writes;
        VkDescriptorBufferInfo resultInfo{results.buffer, 0, VK_WHOLE_SIZE};
        VkDescriptorImageInfo imageInfo{sampler, noise.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo sourceInfo{source.buffer, 0, VK_WHOLE_SIZE};
        VkDescriptorBufferInfo destinationInfo{destination.buffer, 0, VK_WHOLE_SIZE};
        auto write = [&](uint32_t binding, VkDescriptorType type) {
            VkWriteDescriptorSet w{};
            w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w.dstSet = descriptorSet;
            w.dstBinding = binding;
            w.descriptorCount = 1;
            w.descriptorType = type;
            return w;
        };
        VkWriteDescriptorSet w0 = write(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        w0.pBufferInfo = &resultInfo;
        writes.push_back(w0);
        if (noise.view != VK_NULL_HANDLE) {
            VkWriteDescriptorSet w1 = write(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            w1.pImageInfo = &imageInfo;
            writes.push_back(w1);
        }
        if (source.buffer != VK_NULL_HANDLE) {
            VkWriteDescriptorSet w2 = write(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            w2.pBufferInfo = &sourceInfo;
            writes.push_back(w2);
            VkWriteDescriptorSet w3 = write(3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            w3.pBufferInfo = &destinationInfo;
            writes.push_back(w3);
        }
        vkUpdateDescriptorSets(device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    bool createComputePipeline(std::span<const uint32_t> code) {
        VkShaderModule module;
        if (!vk.shaderModule(code, module)) return false;
        VkComputePipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        info.stage.module = module;
        info.stage.pName = "main";
        info.layout = computeLayout;
        const VkResult result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &burnerPipeline);
        vkDestroyShaderModule(device, module, nullptr);
        VK_TRY(result);
        return true;
    }

    // ---- frame loop -------------------------------------------------------------

    bool frame() {
        const uint32_t slot = static_cast<uint32_t>(frameIndex % kFramesInFlight);
        if (submitted[slot]) {
            const VkResult waited = vkWaitForFences(device, 1, &fences[slot], VK_TRUE, UINT64_MAX);
            if (waited != VK_SUCCESS) return lost("vkWaitForFences", waited);
            collect(slot);
            submitted[slot] = false;
        }

        uint32_t image = 0;
        VkResult acquiredResult = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, acquired[slot], VK_NULL_HANDLE, &image);
        if (acquiredResult == VK_ERROR_OUT_OF_DATE_KHR) return recreateSwapchain();
        if (acquiredResult != VK_SUCCESS && acquiredResult != VK_SUBOPTIMAL_KHR) return lost("vkAcquireNextImageKHR", acquiredResult);

        const uint32_t count = burner >= 0 ? perFrame : 0;
        if (!record(slot, image, count)) {
            state.store(kStateFailed);
            return false;
        }

        VkResult reset = vkResetFences(device, 1, &fences[slot]);
        if (reset != VK_SUCCESS) return lost("vkResetFences", reset);
        // Only the visible pass waits for the swapchain image; burner work starts at once.
        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &acquired[slot];
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commands[slot];
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderDone[image];
        const VkResult submitted_ = vkQueueSubmit(queue, 1, &submit, fences[slot]);
        if (submitted_ != VK_SUCCESS) return lost("vkQueueSubmit", submitted_);
        submitted[slot] = true;
        dispatchesInFlight[slot] = count;

        VkPresentInfoKHR present{};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &renderDone[image];
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain;
        present.pImageIndices = &image;
        const VkResult presented = vkQueuePresentKHR(queue, &present);
        ++frameIndex;
        if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) return recreateSwapchain();
        if (presented != VK_SUCCESS) return lost("vkQueuePresentKHR", presented);
        return true;
    }

    bool lost(const char* where, VkResult result) {
        LOGE("%s: %d", where, static_cast<int>(result));
        state.store(result == VK_ERROR_DEVICE_LOST ? kStateDeviceLost : kStateFailed);
        return false;
    }

    bool recreateSwapchain() {
        vkDeviceWaitIdle(device);
        for (uint32_t s = 0; s < kFramesInFlight; ++s) {
            if (submitted[s]) {
                collect(s);
                submitted[s] = false;
            }
        }
        if (!createSwapchain()) {
            state.store(kStateFailed);
            return false;
        }
        return true;
    }

    bool record(uint32_t slot, uint32_t image, uint32_t count) {
        VkCommandBuffer cmd = commands[slot];
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        VK_TRY(vkBeginCommandBuffer(cmd, &begin));
        if (timed) {
            vkCmdResetQueryPool(cmd, queries, slot * kStampsPerFrame, kStampsPerFrame);
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, slot * kStampsPerFrame);
        }

        VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent.width), static_cast<float>(extent.height), 0.0f, 1.0f};
        VkRect2D scissor{{0, 0}, extent};

        if (count > 0 && burner == kBlend) {
            VkClearValue clear{};
            VkRenderPassBeginInfo pass{};
            pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            pass.renderPass = blendPass;
            pass.framebuffer = blendFramebuffer;
            pass.renderArea = scissor;
            pass.clearValueCount = 1;
            pass.pClearValues = &clear;
            vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, burnerPipeline);
            vkCmdSetViewport(cmd, 0, 1, &viewport);
            vkCmdSetScissor(cmd, 0, 1, &scissor);
            for (uint32_t d = 0; d < count; ++d) vkCmdDraw(cmd, 3, kBlendLayers, 0, 0);
            vkCmdEndRenderPass(cmd);
        } else if (count > 0) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, burnerPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computeLayout, 0, 1, &descriptorSet, 0, nullptr);
            for (uint32_t d = 0; d < count; ++d) {
                const ComputeParams params{iterations, (slot * kMaxDispatches + d) * kMaxGroups};
                vkCmdPushConstants(cmd, computeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
                vkCmdDispatch(cmd, groups, 1, 1);
            }
            // The host reads the digests after the fence: make the writes visible to it.
            VkMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0,
                                 nullptr, 0, nullptr);
        }

        if (timed) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, slot * kStampsPerFrame + 1);

        const float seconds = static_cast<float>(static_cast<double>(nowNanos() - startNanos) * 1e-9);
        const float load = burner >= 0 ? 1.0f : 0.0f;
        if (scene) sceneRenderer.record(cmd, seconds);

        VkRenderPassBeginInfo pass{};
        pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        pass.renderPass = presentPass;
        pass.framebuffer = framebuffers[image];
        pass.renderArea = scissor;
        vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        if (scene) {
            sceneRenderer.recordFinal(cmd, extent, seconds);
        } else {
            const PreviewParams params{static_cast<float>(extent.width), static_cast<float>(extent.height), seconds, load};
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, previewPipeline);
            vkCmdPushConstants(cmd, graphicsLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params), &params);
            vkCmdDraw(cmd, 3, 1, 0, 0);
        }
        vkCmdEndRenderPass(cmd);

        if (timed) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, slot * kStampsPerFrame + 2);
        VK_TRY(vkEndCommandBuffer(cmd));
        return true;
    }

    // Reads back a finished frame: its GPU time, its digests, and the next frame's size.
    void collect(uint32_t slot) {
        std::array<uint64_t, kStampsPerFrame> stamps{};
        int64_t burnerNanos = 0;
        int64_t visibleNanos = 0;
        const int64_t collectedAt = nowNanos();
        if (timed) {
            if (vkGetQueryPoolResults(device, queries, slot * kStampsPerFrame, kStampsPerFrame, sizeof(stamps),
                                      stamps.data(), sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
                auto nanos = [&](uint64_t from, uint64_t to) {
                    const uint64_t ticks = ((to & timestampMask) - (from & timestampMask)) & timestampMask;
                    return static_cast<int64_t>(static_cast<double>(ticks) * timestampPeriodNanos);
                };
                burnerNanos = nanos(stamps[0], stamps[1]);
                visibleNanos = nanos(stamps[1], stamps[2]);
            }
        } else if (lastCollectNanos > 0) {
            // No timestamps: with frames queued back to back, the time between
            // two finished frames is the GPU's time for one. The parts are not
            // told apart, so the whole frame counts as the burner's when one runs.
            const int64_t between = std::min<int64_t>(collectedAt - lastCollectNanos, 1'000'000'000);
            (dispatchesInFlight[slot] > 0 ? burnerNanos : visibleNanos) = between;
        }
        lastCollectNanos = collectedAt;
        const int64_t frameNanos = burnerNanos + visibleNanos;

        const uint32_t count = dispatchesInFlight[slot];
        if (count > 0 && kBurners[static_cast<size_t>(burner)].verified) verify(slot, count);

        frames.fetch_add(1, std::memory_order_relaxed);
        dispatches.fetch_add(count, std::memory_order_relaxed);
        work.fetch_add(static_cast<int64_t>(workPerDispatch * count), std::memory_order_relaxed);
        gpuNanos.fetch_add(frameNanos, std::memory_order_relaxed);
        burnerTotalNanos.fetch_add(burnerNanos, std::memory_order_relaxed);
        visibleTotalNanos.fetch_add(visibleNanos, std::memory_order_relaxed);
        lastFrameNanos.store(frameNanos, std::memory_order_relaxed);

        // Size the burner so burner plus visible pass fill the target: the
        // count that fits the time the visible pass leaves, at the cost one
        // dispatch just took. Half of each correction is applied, so one odd
        // frame cannot swing the load; at least one dispatch always runs.
        if (burner >= 0 && burnerNanos > 0 && count > 0) {
            const double perDispatch = static_cast<double>(burnerNanos) / count;
            const double room = static_cast<double>(targetFrameNanos - visibleNanos);
            const double ideal = std::clamp(room / perDispatch, 1.0, static_cast<double>(kMaxDispatches));
            perFrameExact = 0.5 * perFrameExact + 0.5 * ideal;
            perFrame = static_cast<uint32_t>(std::lround(perFrameExact));
        }
        dispatchesPerFrame.store(perFrame, std::memory_order_relaxed);
    }

    void verify(uint32_t slot, uint32_t count) {
        const auto* base = static_cast<const uint32_t*>(results.mapped);
        for (uint32_t d = 0; d < count; ++d) {
            const uint32_t* region = base + (size_t{slot} * kMaxDispatches + d) * kMaxGroups;
            if (golden.empty()) {
                golden.assign(region, region + groups);
                continue;
            }
            checks.fetch_add(1, std::memory_order_relaxed);
            if (std::memcmp(region, golden.data(), groups * sizeof(uint32_t)) != 0) {
                errors.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    void destroy() {
        if (device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device);
            sceneRenderer.destroy();
            if (burnerPipeline) vkDestroyPipeline(device, burnerPipeline, nullptr);
            if (previewPipeline) vkDestroyPipeline(device, previewPipeline, nullptr);
            if (computeLayout) vkDestroyPipelineLayout(device, computeLayout, nullptr);
            if (graphicsLayout) vkDestroyPipelineLayout(device, graphicsLayout, nullptr);
            if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            if (setLayout) vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
            if (blendFramebuffer) vkDestroyFramebuffer(device, blendFramebuffer, nullptr);
            if (blendPass) vkDestroyRenderPass(device, blendPass, nullptr);
            vk.destroy(blendTarget);
            if (sampler) vkDestroySampler(device, sampler, nullptr);
            vk.destroy(noise);
            vk.destroy(source);
            vk.destroy(destination);
            vk.destroy(results);
            if (queries) vkDestroyQueryPool(device, queries, nullptr);
            for (uint32_t i = 0; i < kFramesInFlight; ++i) {
                if (fences[i]) vkDestroyFence(device, fences[i], nullptr);
                if (acquired[i]) vkDestroySemaphore(device, acquired[i], nullptr);
            }
            if (commandPool) vkDestroyCommandPool(device, commandPool, nullptr);
            destroySwapchainViews();
            if (swapchain) vkDestroySwapchainKHR(device, swapchain, nullptr);
            if (presentPass) vkDestroyRenderPass(device, presentPass, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        if (window) ANativeWindow_release(window);
        device = VK_NULL_HANDLE;
        surface = VK_NULL_HANDLE;
        instance = VK_NULL_HANDLE;
        window = nullptr;
    }
};

GpuLoad::GpuLoad() = default;

GpuLoad::~GpuLoad() { stop(); }

GpuLoad::StartResult GpuLoad::start(ANativeWindow* window, int burner, int targetFrameMillis, bool scene,
                                    int sceneScalePercent, int sceneKind, int sceneQuality) {
    std::lock_guard lock(control_);
    if (renderer_) {
        ANativeWindow_release(window);
        return kAlreadyRunning;
    }
    if (burner < -1 || burner >= static_cast<int>(kBurners.size())) {
        ANativeWindow_release(window);
        return kInvalidBurner;
    }
    auto renderer = std::make_unique<Renderer>();
    renderer->window = window;
    renderer->burner = burner;
    renderer->targetFrameNanos = int64_t{std::clamp(targetFrameMillis, 5, 200)} * 1'000'000;
    renderer->scene = scene;
    renderer->sceneScale = static_cast<float>(std::clamp(sceneScalePercent, 10, 100)) / 100.0f;
    renderer->sceneKind = sceneKind == 1   ? SceneRenderer::Kind::Forest
                          : sceneKind == 2 ? SceneRenderer::Kind::White
                                           : SceneRenderer::Kind::Pool;
    renderer->sceneQuality = sceneQuality <= 0   ? SceneRenderer::Quality::Low
                             : sceneQuality >= 2 ? SceneRenderer::Quality::High
                                                 : SceneRenderer::Quality::Medium;
    if (pthread_create(&renderer->thread, nullptr, &Renderer::threadMain, renderer.get()) != 0) {
        renderer->destroy();
        return kSetupFailed;
    }
    renderer->threadStarted = true;
    {
        std::unique_lock wait(renderer->initMutex);
        renderer->initDone.wait(wait, [&] { return renderer->initResult >= 0; });
    }
    const bool ok = renderer->initResult == 0;
    renderer_ = std::move(renderer);
    if (!ok) {
        lastState_.store(kStateFailed);
        pthread_join(renderer_->thread, nullptr);
        renderer_->destroy();
        renderer_.reset();
        return kSetupFailed;
    }
    return kStarted;
}

void GpuLoad::stop() {
    std::lock_guard lock(control_);
    if (!renderer_) return;
    renderer_->stopRequested.store(true);
    if (renderer_->threadStarted) pthread_join(renderer_->thread, nullptr);
    const int finalState = renderer_->state.load();
    renderer_->destroy();
    renderer_.reset();
    lastState_.store(finalState == kStateRunning ? kStateIdle : finalState);
}

void GpuLoad::snapshot(int64_t* out) const {
    // The renderer pointer only changes under control_, which start() may hold
    // for a while; a snapshot taken then reports the previous state.
    std::unique_lock lock(control_, std::try_to_lock);
    std::fill(out, out + kSnapshotStride, 0);
    if (!lock.owns_lock() || !renderer_) {
        out[kFieldState] = lastState_.load();
        return;
    }
    const Renderer& r = *renderer_;
    out[kFieldState] = r.state.load();
    out[kFieldFrames] = r.frames.load(std::memory_order_relaxed);
    out[kFieldDispatches] = r.dispatches.load(std::memory_order_relaxed);
    out[kFieldWork] = r.work.load(std::memory_order_relaxed);
    out[kFieldGpuNanos] = r.gpuNanos.load(std::memory_order_relaxed);
    out[kFieldLastFrameNanos] = r.lastFrameNanos.load(std::memory_order_relaxed);
    out[kFieldDispatchesPerFrame] = r.dispatchesPerFrame.load(std::memory_order_relaxed);
    out[kFieldErrors] = r.errors.load(std::memory_order_relaxed);
    out[kFieldChecks] = r.checks.load(std::memory_order_relaxed);
    out[kFieldWidth] = r.width.load(std::memory_order_relaxed);
    out[kFieldHeight] = r.height.load(std::memory_order_relaxed);
    out[kFieldBurnerNanos] = r.burnerTotalNanos.load(std::memory_order_relaxed);
    out[kFieldVisibleNanos] = r.visibleTotalNanos.load(std::memory_order_relaxed);
    out[kFieldTimed] = r.timed ? 1 : 0;
}

std::string GpuLoad::describeDevice() {
    uint32_t loader = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS) return {};
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "stress";
    app.apiVersion = std::max<uint32_t>(loader, VK_API_VERSION_1_1);
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return {};
    std::string text;
    uint32_t count = 1;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    const VkResult enumerated = vkEnumeratePhysicalDevices(instance, &count, &physical);
    if ((enumerated == VK_SUCCESS || enumerated == VK_INCOMPLETE) && count > 0) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(physical, &p);
        if (p.apiVersion >= VK_API_VERSION_1_1) {
            char line[512];
            std::snprintf(line, sizeof(line), "%s|%u.%u.%u|%u|%u|%u", p.deviceName, VK_API_VERSION_MAJOR(p.apiVersion),
                          VK_API_VERSION_MINOR(p.apiVersion), VK_API_VERSION_PATCH(p.apiVersion), p.driverVersion,
                          p.vendorID, p.deviceID);
            text = line;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return text;
}

}  // namespace stress
