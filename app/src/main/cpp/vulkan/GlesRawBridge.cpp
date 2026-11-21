#include "vulkan/GlesRawBridge.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <GLES2/gl2ext.h>
#include <poll.h>

#include <array>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

#include "imaging/FrameLimits.h"

namespace rawrcam::vulkan {
namespace {
void require(bool ok, const char* step) {
    if (!ok) throw std::runtime_error(step);
}
void vkOk(VkResult r, const char* step) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(step) + " VkResult=" + std::to_string(r));
}
struct Current {
    EGLDisplay display, previous = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    EGLSurface draw = eglGetCurrentSurface(EGL_DRAW), read = eglGetCurrentSurface(EGL_READ);
    explicit Current(EGLDisplay d, EGLSurface s, EGLContext c) : display(d) {
        require(eglMakeCurrent(d, s, s, c), "bridge eglMakeCurrent");
    }
    ~Current() { eglMakeCurrent(context == EGL_NO_CONTEXT ? display : previous, draw, read, context); }
};
struct Source {
    EGLDisplay display;
    PFNEGLDESTROYIMAGEKHRPROC destroy;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0, framebuffer = 0;
    ~Source() {
        glDeleteFramebuffers(1, &framebuffer);
        glDeleteTextures(1, &texture);
        if (image) destroy(display, image);
    }
};
struct Twin {
    VkDevice device{};
    VkBuffer buffer{};
    VkDeviceMemory memory{};
    AHardwareBuffer* blob{};
    GLuint pack{};
    uint32_t bytes{};
    ~Twin() {
        if (buffer) vkDestroyBuffer(device, buffer, nullptr);
        if (memory) vkFreeMemory(device, memory, nullptr);
        if (blob) AHardwareBuffer_release(blob);
    }
};
}  // namespace
struct GlesRawBridge::Impl {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC client{};
    PFNEGLCREATEIMAGEKHRPROC create{};
    PFNEGLDESTROYIMAGEKHRPROC destroy{};
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target{};
    PFNGLBUFFERSTORAGEEXTERNALEXTPROC storage{};
    std::array<std::unique_ptr<Twin>, imaging::kRealtimeFramesInFlight> slots{};
    ~Impl() {
        // Destroying the private context releases all GL pack-buffer references.
        if (context) eglDestroyContext(display, context);
        if (surface) eglDestroySurface(display, surface);
    }
    void init() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        require(display && eglInitialize(display, nullptr, nullptr), "bridge EGL display");
        const EGLint attrs[]{EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_NONE};
        EGLConfig cfg{};
        EGLint n{};
        require(eglChooseConfig(display, attrs, &cfg, 1, &n) && n, "bridge EGL config");
        const EGLint sa[]{EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE}, ca[]{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        surface = eglCreatePbufferSurface(display, cfg, sa);
        context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, ca);
        require(surface && context, "bridge ES3 context");
        Current current(display, surface, context);
        client = reinterpret_cast<decltype(client)>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        create = reinterpret_cast<decltype(create)>(eglGetProcAddress("eglCreateImageKHR"));
        destroy = reinterpret_cast<decltype(destroy)>(eglGetProcAddress("eglDestroyImageKHR"));
        target = reinterpret_cast<decltype(target)>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        storage = reinterpret_cast<decltype(storage)>(eglGetProcAddress("glBufferStorageExternalEXT"));
        GLint count{};
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        bool external = false;
        for (GLint i = 0; i < count; ++i)
            external |= std::strcmp(reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i)),
                                    "GL_EXT_external_buffer") == 0;
        require(external && client && create && destroy && target && storage, "bridge external buffer extensions");
    }
    std::unique_ptr<Twin> allocate(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props,
                                   uint32_t bytes) {
        auto t = std::make_unique<Twin>();
        t->device = device;
        t->bytes = bytes;
        AHardwareBuffer_Desc bd{};
        bd.width = bytes;
        bd.height = bd.layers = 1;
        bd.format = AHARDWAREBUFFER_FORMAT_BLOB;
        bd.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
        require(AHardwareBuffer_allocate(&bd, &t->blob) == 0, "bridge BLOB allocation");
        VkAndroidHardwareBufferPropertiesANDROID hp{};
        hp.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        vkOk(props(device, t->blob, &hp), "bridge BLOB properties");
        VkExternalMemoryBufferCreateInfo ext{};
        ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.pNext = &ext;
        bi.size = bytes;
        bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        vkOk(vkCreateBuffer(device, &bi, nullptr, &t->buffer), "bridge vkCreateBuffer");
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, t->buffer, &req);
        uint32_t bits = req.memoryTypeBits & hp.memoryTypeBits;
        require(bits && req.size <= hp.allocationSize, "bridge incompatible BLOB memory requirements");
        VkImportAndroidHardwareBufferInfoANDROID imp{};
        imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        imp.buffer = t->blob;
        VkMemoryDedicatedAllocateInfo ded{};
        ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        ded.pNext = &imp;
        ded.buffer = t->buffer;
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.pNext = &ded;
        ai.allocationSize = hp.allocationSize;
        ai.memoryTypeIndex = __builtin_ctz(bits);
        vkOk(vkAllocateMemory(device, &ai, nullptr, &t->memory), "bridge vkAllocateMemory");
        vkOk(vkBindBufferMemory(device, t->buffer, t->memory, 0), "bridge vkBindBufferMemory");
        auto cb = client(t->blob);
        require(cb, "bridge BLOB client buffer");
        glGenBuffers(1, &t->pack);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, t->pack);
        storage(GL_PIXEL_PACK_BUFFER, 0, bytes, cb, 0);
        if (glGetError() != GL_NO_ERROR) {
            glDeleteBuffers(1, &t->pack);
            throw std::runtime_error("bridge pack storage");
        }
        return t;
    }
};
GlesRawBridge::GlesRawBridge() = default;
GlesRawBridge::~GlesRawBridge() = default;
void GlesRawBridge::reset() noexcept { impl_.reset(); }
ImportedRaw GlesRawBridge::pack(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props, uint32_t slot,
                                AHardwareBuffer* camera, int fd, uint32_t w, uint32_t h) {
    ImportedRaw raw{};
    AHardwareBuffer_describe(camera, &raw.desc);
    const uint64_t bytes = uint64_t(w) * h * 2;
    require(w && h && raw.desc.width == w && raw.desc.height == h && raw.desc.layers == 1 && raw.desc.format == 0x20 &&
                bytes <= UINT32_MAX && props,
            "bridge RAW16 geometry");
    if (fd >= 0) {
        pollfd p{fd, POLLIN, 0};
        int ready;
        do {
            ready = poll(&p, 1, 1000);
        } while (ready < 0 && errno == EINTR);
        require(ready == 1 && p.revents == POLLIN, "bridge acquire fence");
    }
    if (!impl_) {
        auto next = std::make_unique<Impl>();
        next->init();
        impl_ = std::move(next);
    }
    auto& e = *impl_;
    Current current(e.display, e.surface, e.context);
    auto& twin = e.slots.at(slot);
    if (!twin || twin->bytes != bytes) {
        auto next = e.allocate(device, props, bytes);
        if (twin) glDeleteBuffers(1, &twin->pack);
        twin = std::move(next);
    }
    // Keep the source import frame-scoped so reader pool churn cannot pin stale camera allocations.
    Source src{e.display, e.destroy};
    const EGLint attrs[]{EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    auto cb = e.client(camera);
    require(cb, "bridge RAW client buffer");
    src.image = e.create(e.display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
    require(src.image, "bridge RAW EGLImage");
    glGenTextures(1, &src.texture);
    glBindTexture(GL_TEXTURE_2D, src.texture);
    e.target(GL_TEXTURE_2D, src.image);
    glGenFramebuffers(1, &src.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, src.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, src.texture, 0);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "bridge RAW framebuffer");
    GLint fmt{}, type{};
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &fmt);
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &type);
    require(fmt == GL_RED_INTEGER && type == GL_UNSIGNED_SHORT && glGetError() == GL_NO_ERROR,
            "bridge RAW read format");
    glBindBuffer(GL_PIXEL_PACK_BUFFER, twin->pack);
    glPixelStorei(GL_PACK_ALIGNMENT, 2);
    glReadPixels(0, 0, w, h, GL_RED_INTEGER, GL_UNSIGNED_SHORT, nullptr);
    glFinish();
    require(glGetError() == GL_NO_ERROR, "bridge RAW pack");
    raw.ahb = camera;
    raw.importBuffer = twin->buffer;
    raw.importBufferAvailable = true;
    raw.gpuUnpack = true;
    raw.rowStrideBytes = w * 2;
    return raw;
}
}  // namespace rawrcam::vulkan
