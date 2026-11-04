// Vulkan layer for presentation-independent render measurements.
//
// AFV_RENDER_PROBE_OUT=<prefix> writes one JSON line per second to <prefix>.<pid>:
// call counts and blocking time per Vulkan entrypoint and thread, plus draw,
// barrier and render-pass counts.
//
// AFV_RENDER_PROBE_GPU=1 also times render passes, blits and copies with GPU
// timestamps and adds a "gpu" object per second: "kind WxH cN dN load/store": [count, ms],
// plus "gpuCovered", the milliseconds those intervals cover. The remainder of GPU busy
// time is barriers and other unscoped work. AFV_RENDER_PROBE_TIMESTAMP_NS scales ticks
// for devices whose timestampPeriod is not 1 ns. Command buffers recorded once and replayed
// are not timed. AFV_RENDER_PROBE_TRACE=<path> with AFV_RENDER_PROBE_TRACE_DRAIN=<n> writes
// every scope of the n-th one-second drain to <path>.dev<i> as "begin end name", in ticks.
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
    MEMORY_DEVICE_LOCAL = 1, STAGE_ALL_COMMANDS = 0x10000, NULL_IMAGES = 3, MAX_DEVICES = 8, MAX_THREADS = 24,
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
    X(GetImageMemoryRequirements) X(BindImageMemory) X(CreateFence) X(ResetFences) X(GetDeviceQueue) \
    X(CreateQueryPool) X(ResetQueryPool) X(CmdWriteTimestamp) X(GetQueryPoolResults) X(CmdEndRendering)

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
    U queue, queryPool;
    uint32_t nextScope, collectedScope;
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
static uint32_t memoryTypeCount, memoryTypeFlags[32];
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

// GPU scopes: two timestamps per pass, in a ring the writer thread drains each second.
enum { SCOPE_RING = 1 << 17, SCOPE_MARGIN = 1 << 12, STAGE_BOTTOM_OF_PIPE = 0x2000, QUERY_TYPE_TIMESTAMP = 2, STRUCT_QUERY_POOL_CREATE_INFO = 11,
       QUERY_RESULT_64 = 1, QUERY_RESULT_AVAILABILITY = 4, MAX_SCOPE_KEYS = 256 };
typedef struct { int sType; const void *pNext; uint32_t flags; int type; uint32_t count, statistics; } QueryPoolInfo;
typedef struct { uint32_t pad; int x, y; uint32_t width, height, layers, viewMask, colorCount;
                 const char *colors, *depth, *stencil; } RenderingBody;
typedef struct { int sType; const void *pNext; RenderingBody body; } RenderingInfo;
enum { KIND_PASS, KIND_BLIT, KIND_COPY_IMAGE, KIND_COPY_BUFFER, KIND_BUFFER_TO_IMAGE, KIND_CLEAR_COLOR, KIND_CLEAR_DEPTH, KIND_RESOLVE };
static const char *const scopeKinds[] = {"pass", "blit", "copyImage", "copyBuffer", "bufferToImage", "clearColor", "clearDepth", "resolve"};
static int gpuProbe;
static double nanosPerTick = 1;
static uint64_t scopeKeys[MAX_DEVICES][SCOPE_RING];
static uint8_t scopeDone[MAX_DEVICES][SCOPE_RING];
static __thread uint32_t passScope;

static uint64_t scopeKey(int kind, uint32_t width, uint32_t height, uint32_t colors, uint32_t depth, uint32_t load, uint32_t store) {
    return (uint64_t)kind << 56 | (uint64_t)(width & 0xffff) << 40 | (uint64_t)(height & 0xffff) << 24
            | (colors & 0xff) << 16 | (depth & 0xff) << 8 | (load & 0xf) << 4 | (store & 0xf);
}
static uint32_t beginScope(Device *device, U command, uint64_t key) {
    uint32_t scope = __atomic_fetch_add(&device->nextScope, 1, __ATOMIC_RELAXED) % SCOPE_RING;
    scopeKeys[device - devices][scope] = key;
    device->CmdWriteTimestamp(command, STAGE_BOTTOM_OF_PIPE, device->queryPool, scope * 2, 0, 0, 0, 0, 0, 0);
    return scope;
}
static void endScope(Device *device, U command, uint32_t scope) {
    device->CmdWriteTimestamp(command, STAGE_BOTTOM_OF_PIPE, device->queryPool, scope * 2 + 1, 0, 0, 0, 0, 0, 0);
}
static U scopedBeginRendering(PARAMS) {
    const RenderingInfo *info = (const RenderingInfo *)b;
    const RenderingBody *pass = &info->body;
    uint32_t load = pass->colors ? *(const uint32_t *)(pass->colors + 44) : 9, store = pass->colors ? *(const uint32_t *)(pass->colors + 48) : 9;
    passScope = beginScope(deviceOf(a), a, scopeKey(KIND_PASS, pass->width, pass->height, pass->colorCount, pass->depth != NULL, load, store));
    return wrapCmdBeginRendering(ARGS);
}
static U scopedEndRendering(PARAMS) {
    Device *device = deviceOf(a);
    U result = device->CmdEndRendering(ARGS);
    endScope(device, a, passScope);
    return result;
}
#define SCOPED(entry, kind, width, height) static U scoped##entry(PARAMS) { \
    Device *device = deviceOf(a); \
    uint32_t scope = beginScope(device, a, scopeKey(kind, width, height, 0, 0, 0, 0)); \
    U result = wrap##entry(ARGS); \
    endScope(device, a, scope); \
    return result; \
}
// VkImageBlit: destination offsets[1] follow src subresource, src offsets and dst subresource.
static uint32_t blitAxis(U regions, int axis) { return regions ? *(const uint32_t *)(regions + 68 + axis * 4) : 0; }
SCOPED(CmdBlitImage, KIND_BLIT, blitAxis(g, 0), blitAxis(g, 1))
SCOPED(CmdCopyImage, KIND_COPY_IMAGE, 0, 0)
SCOPED(CmdCopyBuffer, KIND_COPY_BUFFER, 0, 0)
SCOPED(CmdCopyBufferToImage, KIND_BUFFER_TO_IMAGE, 0, 0)
SCOPED(CmdClearColorImage, KIND_CLEAR_COLOR, 0, 0)
SCOPED(CmdClearDepthStencilImage, KIND_CLEAR_DEPTH, 0, 0)
SCOPED(CmdResolveImage, KIND_RESOLVE, 0, 0)

typedef struct { uint64_t begin, end, key; } Interval;
static int compareIntervals(const void *left, const void *right) {
    uint64_t a = ((const Interval *)left)->begin, b = ((const Interval *)right)->begin;
    return a < b ? -1 : a > b;
}
static Interval intervals[SCOPE_RING];
/** Write one device's completed scopes. Unfinished scopes stay queued for the next pass. */
static void writeGpuScopes(Device *device) {
    static uint64_t keys[MAX_SCOPE_KEYS], counts[MAX_SCOPE_KEYS];
    static double totals[MAX_SCOPE_KEYS];
    int keyCount = 0, intervalCount = 0, deviceIndex = (int)(device - devices);
    // Zink can drop a recorded command buffer, so its scopes never complete: scan the whole
    // window, and retire unfinished ones once the ring has moved on. Scopes newer than the
    // margin may not be submitted yet. Each slot is reset here, because a reset recorded in
    // the command stream costs more GPU time than the passes being measured.
    uint32_t limit = __atomic_load_n(&device->nextScope, __ATOMIC_RELAXED) - SCOPE_MARGIN;
    while ((int32_t)(limit - device->collectedScope) > (int32_t)(SCOPE_RING - 2 * SCOPE_MARGIN)) device->collectedScope++;
    uint32_t contiguous = device->collectedScope;
    int pending = 0;
    for (uint32_t index = device->collectedScope; (int32_t)(limit - index) > 0 && intervalCount < SCOPE_RING; index++) {
        uint32_t scope = index % SCOPE_RING;
        if (scopeDone[deviceIndex][scope] == 1) { if (!pending) contiguous = index + 1; continue; }
        uint64_t data[4];
        int status = (int)device->GetQueryPoolResults((U)device->device, device->queryPool, scope * 2, 2, sizeof data, (U)data,
                                                      16, QUERY_RESULT_64 | QUERY_RESULT_AVAILABILITY, 0, 0);
        if (status != 0 || !data[1] || !data[3]) { pending = 1; continue; }
        scopeDone[deviceIndex][scope] = 1;
        if (!pending) contiguous = index + 1;
        device->ResetQueryPool((U)device->device, device->queryPool, scope * 2, 2, 0, 0, 0, 0, 0, 0);
        if (data[2] < data[0]) continue;
        double millis = (data[2] - data[0]) * nanosPerTick / 1e6;
        uint64_t key = scopeKeys[deviceIndex][scope];
        int slot = 0;
        while (slot < keyCount && keys[slot] != key) slot++;
        if (slot == keyCount) {
            if (keyCount == MAX_SCOPE_KEYS) continue;
            keys[keyCount] = key; counts[keyCount] = 0; totals[keyCount] = 0; keyCount++;
        }
        counts[slot]++;
        totals[slot] += millis;
        intervals[intervalCount++] = (Interval){data[0], data[2], key};
    }
    for (uint32_t index = device->collectedScope; index != contiguous; index++) scopeDone[deviceIndex][index % SCOPE_RING] = 0;
    device->collectedScope = contiguous;
    qsort(intervals, intervalCount, sizeof intervals[0], compareIntervals);
    static int traceDrains[MAX_DEVICES];
    const char *tracePath = getenv("AFV_RENDER_PROBE_TRACE");
    if (tracePath && intervalCount > 0 && ++traceDrains[deviceIndex] == atoi(getenv("AFV_RENDER_PROBE_TRACE_DRAIN"))) {
        char path[4096];
        snprintf(path, sizeof path, "%s.dev%d", tracePath, deviceIndex);
        FILE *trace = fopen(path, "w");
        for (int k = 0; trace && k < intervalCount; k++) {
            uint64_t key = intervals[k].key;
            fprintf(trace, "%lu %lu %s %ux%u c%u d%u %u/%u\n", intervals[k].begin, intervals[k].end, scopeKinds[key >> 56], (unsigned)(key >> 40 & 0xffff),
                    (unsigned)(key >> 24 & 0xffff), (unsigned)(key >> 16 & 0xff), (unsigned)(key >> 8 & 0xff), (unsigned)(key >> 4 & 0xf), (unsigned)(key & 0xf));
        }
        if (trace) fclose(trace);
    }
    uint64_t covered = 0, reach = 0;
    for (int k = 0; k < intervalCount; k++) {
        uint64_t begin = intervals[k].begin > reach ? intervals[k].begin : reach;
        if (intervals[k].end > begin) { covered += intervals[k].end - begin; reach = intervals[k].end; }
    }
    fprintf(output, ",\"gpu%d\":{", deviceIndex);
    for (int k = 0; k < keyCount; k++) {
        uint64_t key = keys[k];
        fprintf(output, "%s\"%s %ux%u c%u d%u %u/%u\":[%lu,%.3f]", k ? "," : "", scopeKinds[key >> 56], (unsigned)(key >> 40 & 0xffff),
                (unsigned)(key >> 24 & 0xffff), (unsigned)(key >> 16 & 0xff), (unsigned)(key >> 8 & 0xff),
                (unsigned)(key >> 4 & 0xf), (unsigned)(key & 0xf), counts[k], totals[k]);
    }
    fprintf(output, "},\"gpuCovered%d\":%.3f", deviceIndex, covered * nanosPerTick / 1e6);
}

typedef struct { uint32_t next; U images[NULL_IMAGES], fences[NULL_IMAGES]; } NullSwapchain;

/** A real swapchain image lives in device memory. A host-visible one would add a PCIe copy. */
static uint32_t deviceLocalType(uint32_t bits) {
    for (uint32_t type = 0; type < memoryTypeCount; type++)
        if (bits & (1u << type) && memoryTypeFlags[type] & MEMORY_DEVICE_LOCAL) return type;
    return (uint32_t)__builtin_ctz(bits);
}

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
        MemoryInfo memory = {STRUCT_MEMORY_ALLOCATE_INFO, 0, requirements.size, deviceLocalType(requirements.bits)};
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
        fprintf(output, "}");
        if (gpuProbe) for (int k = 0; k < deviceCount; k++) if (devices[k].queryPool) writeGpuScopes(&devices[k]);
        fprintf(output, "}\n");
        fflush(output);
    }
    return unused;
}

__attribute__((constructor)) static void start(void) {
    nullPresent = getenv("AFV_RENDER_PROBE_NULL_PRESENT") != NULL;
    gpuProbe = getenv("AFV_RENDER_PROBE_GPU") != NULL;
    if (getenv("AFV_RENDER_PROBE_TIMESTAMP_NS")) nanosPerTick = atof(getenv("AFV_RENDER_PROBE_TIMESTAMP_NS"));
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
    void (*memoryProperties)(void *, void *) = (void (*)(void *, void *))next(instanceHandle, "vkGetPhysicalDeviceMemoryProperties");
    if (memoryProperties) {
        uint32_t properties[1 + 2 * 32 + 1 + 4 * 16 * 2] = {0};
        memoryProperties(physicalDevice, properties);
        memoryTypeCount = properties[0] > 32 ? 32 : properties[0];
        for (uint32_t k = 0; k < memoryTypeCount; k++) memoryTypeFlags[k] = properties[1 + 2 * k];
    }

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
    if (gpuProbe) {
        QueryPoolInfo pool = {STRUCT_QUERY_POOL_CREATE_INFO, 0, 0, QUERY_TYPE_TIMESTAMP, SCOPE_RING * 2, 0};
        device->CreateQueryPool((U)*result, (U)&pool, 0, (U)&device->queryPool, 0, 0, 0, 0, 0, 0);
        device->ResetQueryPool((U)*result, device->queryPool, 0, SCOPE_RING * 2, 0, 0, 0, 0, 0, 0);
    }
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

static void *interceptScopes(const char *name) {
    if (!strcmp(name, "vkCmdBeginRendering")) return (void *)scopedBeginRendering;
    if (!strcmp(name, "vkCmdEndRendering")) return (void *)scopedEndRendering;
#define SCOPE_MATCH(entry) if (!strcmp(name, "vk" #entry)) return (void *)scoped##entry;
    SCOPE_MATCH(CmdBlitImage) SCOPE_MATCH(CmdCopyImage) SCOPE_MATCH(CmdCopyBuffer) SCOPE_MATCH(CmdCopyBufferToImage)
    SCOPE_MATCH(CmdClearColorImage) SCOPE_MATCH(CmdClearDepthStencilImage) SCOPE_MATCH(CmdResolveImage)
#undef SCOPE_MATCH
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
    void *scoped = gpuProbe && device->queryPool ? interceptScopes(name) : NULL;
    if (scoped) return scoped;
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
