// Fake libEGL.so.1 for a same-hardware OpenGL throughput reference.
//
// A nested X server cannot host the vendor's GL driver, so GL comparisons there
// silently fall back to software. Loaded through LD_LIBRARY_PATH in place of
// libEGL.so.1, this keeps the application's window but renders on the GPU's EGL
// device into a pbuffer. eglSwapBuffers becomes a three-frame GPU throttle, which
// matches render_ceiling_layer.c: both measure rendering with presentation removed.
//
// Environment: AFV_GL_REFERENCE_VISUAL (X visual id), AFV_GL_REFERENCE_SIZE (WxH).
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef uintptr_t U;
#define EXPORT __attribute__((visibility("default")))
#define SYSTEM_EGL "libEGL.so.1.1.0"

enum {
    EGL_NATIVE_VISUAL_ID = 0x302E, EGL_SURFACE_TYPE = 0x3033, EGL_NONE = 0x3038, EGL_HEIGHT = 0x3056, EGL_WIDTH = 0x3057,
    EGL_WINDOW_BIT = 4, EGL_PLATFORM_DEVICE = 0x313F, GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117, GL_SYNC_FLUSH_COMMANDS_BIT = 1,
    FRAMES_IN_FLIGHT = 3,
};

static void *systemEgl;
static void *(*systemProcAddress)(const char *);
static void *deviceDisplay;

static void *systemSymbol(const char *name) {
    if (!systemEgl) systemEgl = dlopen(SYSTEM_EGL, RTLD_NOW | RTLD_LOCAL);
    if (!systemEgl) { fprintf(stderr, "AFV GL reference: cannot load %s\n", SYSTEM_EGL); abort(); }
    return dlsym(systemEgl, name);
}

static void *display(void) {
    if (deviceDisplay) return deviceDisplay;
    systemProcAddress = (void *(*)(const char *))systemSymbol("eglGetProcAddress");
    int (*queryDevices)(int, void **, int *) = (int (*)(int, void **, int *))systemProcAddress("eglQueryDevicesEXT");
    void *(*platformDisplay)(int, void *, const int *) = (void *(*)(int, void *, const int *))systemProcAddress("eglGetPlatformDisplayEXT");
    void *devices[16];
    int count = 0;
    if (!queryDevices || !platformDisplay || !queryDevices(16, devices, &count) || count == 0) {
        fprintf(stderr, "AFV GL reference: the selected EGL vendor exposes no device\n");
        abort();
    }
    return deviceDisplay = platformDisplay(EGL_PLATFORM_DEVICE, devices[0], NULL);
}

EXPORT void *eglGetDisplay(void *nativeDisplay) { return display(); }
EXPORT void *eglGetPlatformDisplay(int platform, void *nativeDisplay, const void *attributes) { return display(); }
static void *getPlatformDisplayExt(int platform, void *nativeDisplay, const void *attributes) { return display(); }

// GLFW filters configs by window support and an X visual, neither of which a device display has.
EXPORT unsigned eglGetConfigAttrib(void *dpy, void *config, int attribute, int *value) {
    static unsigned (*query)(void *, void *, int, int *);
    if (!query) query = (unsigned (*)(void *, void *, int, int *))systemSymbol("eglGetConfigAttrib");
    if (attribute == EGL_NATIVE_VISUAL_ID) { *value = (int)strtol(getenv("AFV_GL_REFERENCE_VISUAL"), NULL, 0); return 1; }
    unsigned result = query(dpy, config, attribute, value);
    if (attribute == EGL_SURFACE_TYPE) *value |= EGL_WINDOW_BIT;
    return result;
}

EXPORT void *eglCreateWindowSurface(void *dpy, void *config, U window, const int *attributes) {
    static void *(*create)(void *, void *, const int *);
    if (!create) create = (void *(*)(void *, void *, const int *))systemSymbol("eglCreatePbufferSurface");
    int width = 0, height = 0;
    if (sscanf(getenv("AFV_GL_REFERENCE_SIZE"), "%dx%d", &width, &height) != 2) abort();
    int pbuffer[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    return create(dpy, config, pbuffer);
}
EXPORT void *eglCreatePlatformWindowSurface(void *dpy, void *config, void *window, const void *attributes) {
    return eglCreateWindowSurface(dpy, config, 0, NULL);
}
static void *createPlatformWindowSurfaceExt(void *dpy, void *config, void *window, const void *attributes) {
    return eglCreateWindowSurface(dpy, config, 0, NULL);
}

EXPORT unsigned eglSwapBuffers(void *dpy, void *surface) {
    static void *(*fenceSync)(unsigned, unsigned);
    static int (*clientWaitSync)(void *, unsigned, uint64_t);
    static void (*deleteSync)(void *);
    static void (*flush)(void);
    static void *frames[FRAMES_IN_FLIGHT];
    static unsigned frame;
    if (!fenceSync) {
        fenceSync = (void *(*)(unsigned, unsigned))systemProcAddress("glFenceSync");
        clientWaitSync = (int (*)(void *, unsigned, uint64_t))systemProcAddress("glClientWaitSync");
        deleteSync = (void (*)(void *))systemProcAddress("glDeleteSync");
        flush = (void (*)(void))systemProcAddress("glFlush");
    }
    void *oldest = frames[frame % FRAMES_IN_FLIGHT];
    frames[frame++ % FRAMES_IN_FLIGHT] = fenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    flush();
    if (oldest) { clientWaitSync(oldest, GL_SYNC_FLUSH_COMMANDS_BIT, ~0ull); deleteSync(oldest); }
    return 1;
}

#define FORWARDED(X) \
    X(eglBindAPI) X(eglBindTexImage) X(eglChooseConfig) X(eglClientWaitSync) X(eglCopyBuffers) \
    X(eglCreateContext) X(eglCreateImage) X(eglCreatePbufferFromClientBuffer) X(eglCreatePbufferSurface) \
    X(eglCreatePixmapSurface) X(eglCreatePlatformPixmapSurface) X(eglCreateSync) X(eglDestroyContext) \
    X(eglDestroyImage) X(eglDestroySurface) X(eglDestroySync) X(eglGetConfigs) X(eglGetCurrentContext) \
    X(eglGetCurrentDisplay) X(eglGetCurrentSurface) X(eglGetError) X(eglGetSyncAttrib) X(eglInitialize) \
    X(eglMakeCurrent) X(eglQueryAPI) X(eglQueryContext) X(eglQueryString) X(eglQuerySurface) \
    X(eglReleaseTexImage) X(eglReleaseThread) X(eglSurfaceAttrib) X(eglSwapInterval) X(eglTerminate) \
    X(eglWaitClient) X(eglWaitGL) X(eglWaitNative) X(eglWaitSync)

#define FORWARD(name) EXPORT U name(U a, U b, U c, U d, U e, U f, U g, U h) { \
    static U (*target)(U, U, U, U, U, U, U, U); \
    if (!target) target = (U (*)(U, U, U, U, U, U, U, U))systemSymbol(#name); \
    return target(a, b, c, d, e, f, g, h); \
}
FORWARDED(FORWARD)

EXPORT void *eglGetProcAddress(const char *name) {
    if (!systemProcAddress) systemProcAddress = (void *(*)(const char *))systemSymbol("eglGetProcAddress");
    if (!strcmp(name, "eglGetPlatformDisplayEXT")) return (void *)getPlatformDisplayExt;
    if (!strcmp(name, "eglGetPlatformDisplay")) return (void *)eglGetPlatformDisplay;
    if (!strcmp(name, "eglCreatePlatformWindowSurfaceEXT") || !strcmp(name, "eglCreatePlatformWindowSurface"))
        return (void *)createPlatformWindowSurfaceExt;
    return systemProcAddress(name);
}
