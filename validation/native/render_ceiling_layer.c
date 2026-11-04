// Vulkan layer for presentation-independent render measurements.
//
// AFV_RENDER_PROBE_OUT=<prefix> writes one JSON line per second to <prefix>.<pid>:
// call counts and blocking time per Vulkan entrypoint and thread, plus draw,
// barrier and render-pass counts.
//
// AFV_RENDER_PROBE_NULL_PRESENT=1 replaces every swapchain with three offscreen
// images. Rendering, queue submission, semaphores and a three-frame GPU throttle
// remain; nothing reaches a window. FPS then measures what the renderer can
// produce, independent of the window system. Nested X servers are the main use:
// a driver's foreign-server present path can dominate the frame time there.
//
// No Vulkan headers are required: handles and arguments are forwarded as opaque
// 64-bit integers (x86_64 SysV), and only the structures read here are declared.
#define _GNU_SOURCE
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

typedef uintptr_t U;
typedef U (*Fn)(U, U, U, U, U, U, U, U, U, U);
typedef void *(*GetProcAddr)(void *, const char *);

#define PARAMS U a, U b, U c, U d, U e, U f, U g, U h, U i, U j
#define ARGS a, b, c, d, e, f, g, h, i, j
#define EXPORT __attribute__((visibility("default")))

enum {
    STRUCT_SUBMIT_INFO = 4, STRUCT_MEMORY_ALLOCATE_INFO = 5, STRUCT_FENCE_CREATE_INFO = 8,
    STRUCT_IMAGE_CREATE_INFO = 14, STRUCT_LOADER_INSTANCE_CREATE_INFO = 47, STRUCT_LOADER_DEVICE_CREATE_INFO = 48,
    LAYER_LINK_INFO = 0, IMAGE_CREATE_MUTABLE_FORMAT = 8, IMAGE_TYPE_2D = 1, FENCE_SIGNALED = 1,
    STAGE_ALL_COMMANDS = 0x10000, NULL_IMAGES = 3, MAX_DEVICES = 8, MAX_THREADS = 24,
};

typedef struct { int sType; const void *pNext; } Header;
typedef struct InstanceLink { struct InstanceLink *next; GetProcAddr gipa; void *gpdpa; } InstanceLink;
typedef struct { int sType; const void *pNext; int function; InstanceLink *link; } LoaderInstanceInfo;
typedef struct DeviceLink { struct DeviceLink *next; GetProcAddr gipa; GetProcAddr gdpa; } DeviceLink;
typedef struct { int sType; const void *pNext; int function; DeviceLink *link; } LoaderDeviceInfo;
typedef struct { int sType; const void *pNext; uint32_t flags, queueCount; const void *queues; } DeviceInfo;
typedef struct { int sType; const void *pNext; uint32_t flags, family; } QueueInfo;
typedef struct {
    int sType; const void *pNext; uint32_t flags; U surface; uint32_t minImageCount; int format, colorSpace;
    uint32_t width, height, layers, usage; int sharing; uint32_t queueCount; const uint32_t *queues;
    int transform, alpha, presentMode; uint32_t clipped; U old;
} SwapchainInfo;
typedef struct {
    int sType; const void *pNext; uint32_t flags; int type, format; uint32_t width, height, depth, mips, layers;
    int samples, tiling; uint32_t usage; int sharing; uint32_t queueCount; const uint32_t *queues; int layout;
} ImageInfo;
typedef struct { uint64_t size, alignment; uint32_t bits; } MemoryRequirements;
typedef struct { int sType; const void *pNext; uint64_t size; uint32_t type; } MemoryInfo;
typedef struct { int sType; const void *pNext; uint32_t flags; } FenceInfo;
typedef struct {
    int sType; const void *pNext; uint32_t waitCount; const U *wait; const int *stages;
    uint32_t commandCount; const U *commands; uint32_t signalCount; const U *signal;
} SubmitInfo;
typedef struct {
    int sType; const void *pNext; uint32_t waitCount; const U *wait; uint32_t swapchainCount;
    const U *swapchains; const uint32_t *indices; int *results;
} PresentInfo;
typedef struct { int sType; const void *pNext; U swapchain; uint64_t timeout; U semaphore, fence; uint32_t mask; } AcquireInfo;

#define TIMED(X) X(QueueSubmit) X(QueueSubmit2) X(QueueSubmit2KHR) X(QueuePresentKHR) X(AcquireNextImageKHR) \
    X(AcquireNextImage2KHR) X(WaitSemaphores) X(WaitForFences) X(QueueWaitIdle) X(DeviceWaitIdle) \
    X(CreateGraphicsPipelines) X(CreateComputePipelines) X(MapMemory) X(AllocateMemory) X(CreateShaderModule)
#define COUNTED(X) X(CmdDraw) X(CmdDrawIndexed) X(CmdDrawIndirect) X(CmdDrawIndexedIndirect) X(CmdDrawIndirectCount) \
    X(CmdDrawIndexedIndirectCount) X(CmdDrawMultiEXT) X(CmdDrawMultiIndexedEXT) X(CmdDispatch) X(CmdDispatchIndirect) \
    X(CmdBeginRendering) X(CmdPipelineBarrier) X(CmdPipelineBarrier2) X(CmdBindPipeline) X(CmdBindDescriptorSets) \
    X(CmdBindDescriptorBuffersEXT) X(CmdPushDescriptorSetKHR) X(CmdPushDescriptorSetWithTemplateKHR) X(CmdCopyBuffer) \
    X(CmdCopyImage) X(CmdBlitImage) X(CmdCopyBufferToImage) X(CmdCopyImageToBuffer) X(CmdClearColorImage) \
    X(CmdClearDepthStencilImage) X(CmdClearAttachments) X(CmdResolveImage) X(BeginCommandBuffer) X(CmdBindShadersEXT) \
    X(UpdateDescriptorSetWithTemplate)
#define INTERNAL(X) X(CreateSwapchainKHR) X(GetSwapchainImagesKHR) X(DestroySwapchainKHR) X(CreateImage) \
    X(GetImageMemoryRequirements) X(BindImageMemory) X(CreateFence) X(ResetFences) X(GetDeviceQueue)

#define ENUM(name) TIMED_##name,
enum { TIMED(ENUM) TIMED_TOTAL };
#undef ENUM
#define ENUM(name) COUNT_##name,
enum { COUNTED(ENUM) COUNT_TOTAL };
#undef ENUM
#define NAME(name) #name,
static const char *const timedNames[] = {TIMED(NAME)};
static const char *const countNames[] = {COUNTED(NAME)};
#undef NAME

typedef struct {
    void *dispatchKey, *device;
    GetProcAddr gdpa;
    U queue;
#define MEMBER(name) Fn name;
    TIMED(MEMBER) COUNTED(MEMBER) INTERNAL(MEMBER)
#undef MEMBER
} Device;

static Device devices[MAX_DEVICES];
static int deviceCount;
static GetProcAddr instanceProcAddr;
static void *instanceHandle;

static uint64_t timedCalls[TIMED_TOTAL][MAX_THREADS], timedNanos[TIMED_TOTAL][MAX_THREADS], counters[COUNT_TOTAL];
static char threadNames[MAX_THREADS][20];
static int threadCount;
static __thread int threadSlot = -1;
static pthread_mutex_t tableLock = PTHREAD_MUTEX_INITIALIZER;
// Null presentation submits from threads the application did not synchronize with.
static pthread_mutex_t queueLock = PTHREAD_MUTEX_INITIALIZER;
static int nullPresent;
static FILE *output;

static uint64_t monotonic(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000000000ull + time.tv_nsec;
}

static int currentThread(void) {
    if (threadSlot >= 0) return threadSlot;
    pthread_mutex_lock(&tableLock);
    char name[16] = "unknown";
    pthread_getname_np(pthread_self(), name, sizeof name);
    int slot = -1;
    for (int k = 0; k < threadCount; k++) if (!strcmp(threadNames[k], name)) slot = k;
    if (slot < 0 && threadCount < MAX_THREADS) { slot = threadCount++; strcpy(threadNames[slot], name); }
    threadSlot = slot < 0 ? MAX_THREADS - 1 : slot;
    pthread_mutex_unlock(&tableLock);
    return threadSlot;
}

static inline Device *deviceOf(U dispatchable) {
    void *key = *(void **)dispatchable;
    for (int k = 0; k < deviceCount; k++) if (devices[k].dispatchKey == key) return &devices[k];
    return &devices[0];
}

static inline void record(int entry, uint64_t started) {
    int slot = currentThread();
    __atomic_fetch_add(&timedCalls[entry][slot], 1, __ATOMIC_RELAXED);
    __atomic_fetch_add(&timedNanos[entry][slot], monotonic() - started, __ATOMIC_RELAXED);
}

static int isQueueEntry(int entry) {
    return entry == TIMED_QueueSubmit || entry == TIMED_QueueSubmit2 || entry == TIMED_QueueSubmit2KHR || entry == TIMED_QueueWaitIdle;
}

#define WRAP(name) static U wrap##name(PARAMS) { \
    Device *device = deviceOf(a); \
    uint64_t started = monotonic(); \
    int locked = nullPresent && isQueueEntry(TIMED_##name); \
    if (locked) pthread_mutex_lock(&queueLock); \
    U result = device->name(ARGS); \
    if (locked) pthread_mutex_unlock(&queueLock); \
    record(TIMED_##name, started); \
    return result; \
}
TIMED(WRAP)
#undef WRAP
#define WRAP(name) static U wrap##name(PARAMS) { \
    __atomic_fetch_add(&counters[COUNT_##name], 1, __ATOMIC_RELAXED); \
    return deviceOf(a)->name(ARGS); \
}
COUNTED(WRAP)
#undef WRAP

typedef struct { uint32_t next; U images[NULL_IMAGES], fences[NULL_IMAGES]; } NullSwapchain;

static int createNullSwapchain(U deviceHandle, const SwapchainInfo *info, NullSwapchain **result) {
    Device *device = deviceOf(deviceHandle);
    NullSwapchain *swapchain = calloc(1, sizeof *swapchain);
    if (!swapchain) return -1;
    for (int k = 0; k < NULL_IMAGES; k++) {
        ImageInfo image = {STRUCT_IMAGE_CREATE_INFO, 0, IMAGE_CREATE_MUTABLE_FORMAT, IMAGE_TYPE_2D, info->format,
                           info->width, info->height, 1, 1, 1, 1, 0, info->usage, 0, 0, 0, 0};
        int status = (int)device->CreateImage(deviceHandle, (U)&image, 0, (U)&swapchain->images[k], 0, 0, 0, 0, 0, 0);
        if (status) return status;
        MemoryRequirements requirements;
        device->GetImageMemoryRequirements(deviceHandle, swapchain->images[k], (U)&requirements, 0, 0, 0, 0, 0, 0, 0);
        MemoryInfo memory = {STRUCT_MEMORY_ALLOCATE_INFO, 0, requirements.size, (uint32_t)__builtin_ctz(requirements.bits)};
        U allocation = 0;
        status = (int)device->AllocateMemory(deviceHandle, (U)&memory, 0, (U)&allocation, 0, 0, 0, 0, 0, 0);
        if (status) return status;
        device->BindImageMemory(deviceHandle, swapchain->images[k], allocation, 0, 0, 0, 0, 0, 0, 0);
        FenceInfo fence = {STRUCT_FENCE_CREATE_INFO, 0, FENCE_SIGNALED};
        device->CreateFence(deviceHandle, (U)&fence, 0, (U)&swapchain->fences[k], 0, 0, 0, 0, 0, 0);
    }
    *result = swapchain;
    return 0;
}

static U nullAcquire(U deviceHandle, NullSwapchain *swapchain, U semaphore, U fence, uint32_t *index) {
    Device *device = deviceOf(deviceHandle);
    uint32_t image = swapchain->next;
    swapchain->next = (image + 1) % NULL_IMAGES;
    U frameFence = swapchain->fences[image];
    device->WaitForFences(deviceHandle, 1, (U)&frameFence, 1, ~0ull, 0, 0, 0, 0, 0);
    if (semaphore || fence) {
        SubmitInfo submit = {STRUCT_SUBMIT_INFO, 0, 0, 0, 0, 0, 0, semaphore ? 1u : 0u, semaphore ? &semaphore : 0};
        pthread_mutex_lock(&queueLock);
        int status = (int)device->QueueSubmit(device->queue, 1, (U)&submit, fence, 0, 0, 0, 0, 0, 0);
        pthread_mutex_unlock(&queueLock);
        if (status) return status;
    }
    *index = image;
    return 0;
}

static U nullQueuePresent(U queue, const PresentInfo *info) {
    Device *device = deviceOf(queue);
    int stages[64];
    for (uint32_t k = 0; k < info->waitCount && k < 64; k++) stages[k] = STAGE_ALL_COMMANDS;
    for (uint32_t k = 0; k < info->swapchainCount; k++) {
        NullSwapchain *swapchain = (NullSwapchain *)info->swapchains[k];
        U frameFence = swapchain->fences[info->indices[k]];
        device->ResetFences((U)device->device, 1, (U)&frameFence, 0, 0, 0, 0, 0, 0, 0);
        SubmitInfo submit = {STRUCT_SUBMIT_INFO, 0, k == 0 ? info->waitCount : 0, info->wait, stages, 0, 0, 0, 0};
        pthread_mutex_lock(&queueLock);
        int status = (int)device->QueueSubmit(queue, 1, (U)&submit, frameFence, 0, 0, 0, 0, 0, 0);
        pthread_mutex_unlock(&queueLock);
        if (info->results) info->results[k] = status;
    }
    return 0;
}

static U presentEntry(PARAMS) {
    if (!nullPresent) return wrapQueuePresentKHR(ARGS);
    uint64_t started = monotonic();
    U result = nullQueuePresent(a, (const PresentInfo *)b);
    record(TIMED_QueuePresentKHR, started);
    return result;
}
static U acquireEntry(PARAMS) {
    if (!nullPresent) return wrapAcquireNextImageKHR(ARGS);
    return nullAcquire(a, (NullSwapchain *)b, d, e, (uint32_t *)f);
}
static U acquire2Entry(PARAMS) {
    if (!nullPresent) return wrapAcquireNextImage2KHR(ARGS);
    const AcquireInfo *info = (const AcquireInfo *)b;
    return nullAcquire(a, (NullSwapchain *)info->swapchain, info->semaphore, info->fence, (uint32_t *)c);
}
static U createSwapchainEntry(PARAMS) {
    const SwapchainInfo *info = (const SwapchainInfo *)b;
    fprintf(output, "{\"swapchain\":{\"width\":%u,\"height\":%u,\"format\":%d,\"presentMode\":%d,\"null\":%s}}\n",
            info->width, info->height, info->format, info->presentMode, nullPresent ? "true" : "false");
    fflush(output);
    if (nullPresent) return (U)(long)createNullSwapchain(a, info, (NullSwapchain **)d);
    return deviceOf(a)->CreateSwapchainKHR(ARGS);
}
static U swapchainImagesEntry(PARAMS) {
    if (!nullPresent) return deviceOf(a)->GetSwapchainImagesKHR(ARGS);
    const NullSwapchain *swapchain = (const NullSwapchain *)b;
    uint32_t *count = (uint32_t *)c;
    U *images = (U *)d;
    if (!images) { *count = NULL_IMAGES; return 0; }
    if (*count > NULL_IMAGES) *count = NULL_IMAGES;
    for (uint32_t k = 0; k < *count; k++) images[k] = swapchain->images[k];
    return 0;
}
static U destroySwapchainEntry(PARAMS) {
    return nullPresent ? 0 : deviceOf(a)->DestroySwapchainKHR(ARGS);
}

static void *writer(void *unused) {
    static uint64_t lastCalls[TIMED_TOTAL][MAX_THREADS], lastNanos[TIMED_TOTAL][MAX_THREADS], lastCounters[COUNT_TOTAL];
    pthread_setname_np(pthread_self(), "afv-render-probe");
    for (;;) {
        sleep(1);
        fprintf(output, "{\"t\":%.3f,\"timed\":{", monotonic() / 1e9);
        const char *separator = "";
        for (int entry = 0; entry < TIMED_TOTAL; entry++) for (int slot = 0; slot < threadCount; slot++) {
            uint64_t calls = timedCalls[entry][slot], nanos = timedNanos[entry][slot];
            if (calls != lastCalls[entry][slot]) {
                fprintf(output, "%s\"%s@%s\":[%lu,%.3f]", separator, timedNames[entry], threadNames[slot],
                        calls - lastCalls[entry][slot], (nanos - lastNanos[entry][slot]) / 1e6);
                separator = ",";
            }
            lastCalls[entry][slot] = calls;
            lastNanos[entry][slot] = nanos;
        }
        fprintf(output, "},\"count\":{");
        separator = "";
        for (int entry = 0; entry < COUNT_TOTAL; entry++) {
            uint64_t value = counters[entry];
            if (value != lastCounters[entry]) {
                fprintf(output, "%s\"%s\":%lu", separator, countNames[entry], value - lastCounters[entry]);
                separator = ",";
            }
            lastCounters[entry] = value;
        }
        fprintf(output, "}}\n");
        fflush(output);
    }
    return unused;
}

__attribute__((constructor)) static void start(void) {
    nullPresent = getenv("AFV_RENDER_PROBE_NULL_PRESENT") != NULL;
    const char *prefix = getenv("AFV_RENDER_PROBE_OUT");
    char path[4096];
    snprintf(path, sizeof path, "%s.%d", prefix ? prefix : "/dev/null", (int)getpid());
    output = prefix ? fopen(path, "w") : NULL;
    if (!output) output = fopen("/dev/null", "w");
    pthread_t thread;
    pthread_create(&thread, NULL, writer, NULL);
    pthread_detach(thread);
}

static int createInstance(const Header *info, const void *allocator, void **instance) {
    LoaderInstanceInfo *link = (LoaderInstanceInfo *)info->pNext;
    while (link && !(link->sType == STRUCT_LOADER_INSTANCE_CREATE_INFO && link->function == LAYER_LINK_INFO))
        link = (LoaderInstanceInfo *)link->pNext;
    if (!link) return -3;
    GetProcAddr next = link->link->gipa;
    link->link = link->link->next;
    int (*create)(const Header *, const void *, void **) = (int (*)(const Header *, const void *, void **))next(NULL, "vkCreateInstance");
    int status = create(info, allocator, instance);
    if (status == 0) { instanceProcAddr = next; instanceHandle = *instance; }
    return status;
}

static int createDevice(void *physicalDevice, const Header *info, const void *allocator, void **result) {
    LoaderDeviceInfo *link = (LoaderDeviceInfo *)info->pNext;
    while (link && !(link->sType == STRUCT_LOADER_DEVICE_CREATE_INFO && link->function == LAYER_LINK_INFO))
        link = (LoaderDeviceInfo *)link->pNext;
    if (!link) return -3;
    GetProcAddr next = link->link->gipa, nextDevice = link->link->gdpa;
    link->link = link->link->next;
    int (*create)(void *, const Header *, const void *, void **) =
            (int (*)(void *, const Header *, const void *, void **))next(instanceHandle, "vkCreateDevice");
    int status = create(physicalDevice, info, allocator, result);
    if (status != 0) return status;

    pthread_mutex_lock(&tableLock);
    Device *device = &devices[deviceCount < MAX_DEVICES ? deviceCount++ : MAX_DEVICES - 1];
    memset(device, 0, sizeof *device);
    device->device = *result;
    device->dispatchKey = **(void ***)result;
    device->gdpa = nextDevice;
#define RESOLVE(name) device->name = (Fn)nextDevice(*result, "vk" #name);
    TIMED(RESOLVE) COUNTED(RESOLVE) INTERNAL(RESOLVE)
#undef RESOLVE
    const DeviceInfo *deviceInfo = (const DeviceInfo *)info;
    uint32_t family = ((const QueueInfo *)deviceInfo->queues)->family;
    device->GetDeviceQueue((U)*result, family, 0, (U)&device->queue, 0, 0, 0, 0, 0, 0);
    pthread_mutex_unlock(&tableLock);
    return 0;
}

EXPORT void *afvGetDeviceProcAddr(void *deviceHandle, const char *name);

static void *interceptPresentation(const char *name) {
    if (!strcmp(name, "vkQueuePresentKHR")) return (void *)presentEntry;
    if (!strcmp(name, "vkAcquireNextImageKHR")) return (void *)acquireEntry;
    if (!strcmp(name, "vkAcquireNextImage2KHR")) return (void *)acquire2Entry;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (void *)createSwapchainEntry;
    if (!strcmp(name, "vkGetSwapchainImagesKHR")) return (void *)swapchainImagesEntry;
    if (!strcmp(name, "vkDestroySwapchainKHR")) return (void *)destroySwapchainEntry;
    return NULL;
}

static void *intercept(const Device *device, const char *name) {
#define MATCH(entry) if (!strcmp(name, "vk" #entry)) return device->entry ? (void *)wrap##entry : NULL;
    TIMED(MATCH) COUNTED(MATCH)
#undef MATCH
    return NULL;
}

EXPORT void *afvGetDeviceProcAddr(void *deviceHandle, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)afvGetDeviceProcAddr;
    void *key = *(void **)deviceHandle;
    Device *device = NULL;
    for (int k = 0; k < deviceCount; k++) if (devices[k].dispatchKey == key) device = &devices[k];
    if (!device) return NULL;
    void *presentation = interceptPresentation(name);
    if (presentation) return presentation;
    void *wrapped = intercept(device, name);
    return wrapped ? wrapped : device->gdpa(deviceHandle, name);
}

EXPORT void *afvGetInstanceProcAddr(void *instance, const char *name) {
    if (!strcmp(name, "vkCreateInstance")) return (void *)createInstance;
    if (!strcmp(name, "vkCreateDevice")) return (void *)createDevice;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (void *)afvGetInstanceProcAddr;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (void *)afvGetDeviceProcAddr;
    return instanceProcAddr ? instanceProcAddr(instance, name) : NULL;
}
