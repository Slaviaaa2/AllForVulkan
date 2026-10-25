/* Driver-independent ABI/dispatch regression tests for EXT DSA entrypoints. */
#include <assert.h>
#include <stdio.h>
#include "../../main/c/direct_framebuffer.c"

static U draw_binding = 3, read_binding = 4;
static int driver_gets;
static U draw_modes[16], read_modes[16];
static I pending_error, core_queries, ext_queries, ext_draws, ext_reads;
static void bind_framebuffer(U target,U framebuffer) {
    assert(framebuffer < 16);
    if (target == DRAW || target == 0x8D40u) draw_binding = framebuffer;
    if (target == READ || target == 0x8D40u) read_binding = framebuffer;
}
static void get_integer(U pname,I *value) {
    driver_gets++;
    switch (pname) {
    case DRAW_BINDING: *value = (I)draw_binding; break;
    case READ_BINDING: *value = (I)read_binding; break;
    case DRAW_BUFFER: *value = (I)draw_modes[draw_binding]; break;
    case READ_BUFFER: *value = (I)read_modes[read_binding]; break;
    default: assert(0);
    }
}
static void named_draw(U framebuffer,U mode) {
    assert(framebuffer < 16);
    if (mode == 0xDEADu) { pending_error = 0x500; return; }
    draw_modes[framebuffer] = mode;
}
static void named_read(U framebuffer,U mode) {
    assert(framebuffer < 16);
    if (mode == 0xDEADu) { pending_error = 0x500; return; }
    read_modes[framebuffer] = mode;
}
static void ext_draw(U framebuffer,U mode) { ext_draws++; named_draw(framebuffer,mode); }
static void ext_read(U framebuffer,U mode) { ext_reads++; named_read(framebuffer,mode); }
static void ext_draws_list(U framebuffer,I count,const U *modes) {
    ext_draws++; assert(count == 0 || count == 1);
    named_draw(framebuffer,count ? modes[0] : 0);
}
static void core_query(U target,U pname,I *value) {
    core_queries++; assert(target == DRAW); assert(pname == 0x9310u);
    *value = 55;
}
static void ext_query(U framebuffer,U pname,I *value) {
    ext_queries++; assert(framebuffer < 16); assert(pname == DRAW_BUFFER);
    *value = (I)draw_modes[framebuffer];
}
static jlong resolve_symbol(const char *symbol,uintptr_t address) {
    return Java_dev_allforvulkan_bridge_DirectDefaultFramebuffer_resolve(NULL,NULL,
            (jlong)(uintptr_t)symbol,(jlong)address);
}
int main(void) {
    real_BindFramebuffer = bind_framebuffer;
    real_GetIntegerv = get_integer;
    real_NamedFramebufferDrawBuffer = named_draw;
    real_NamedFramebufferReadBuffer = named_read;
    draw_modes[0] = read_modes[0] = 0x404u; /* FRONT */
    draw_modes[3] = read_modes[4] = COLOR0;
    alias = 9;
    /* Resolve EXT first: it must neither install nor call the core target query. */
    jlong ext = resolve_symbol("glGetFramebufferParameterivEXT",(uintptr_t)ext_query);
    assert(ext == (jlong)(uintptr_t)ext_query);
    assert(real_GetFramebufferParameteriv == NULL);
    jlong core = resolve_symbol("glGetFramebufferParameteriv",(uintptr_t)core_query);
    I value = -1;
    ((void (*)(U,U,I*))(uintptr_t)ext)(0,DRAW_BUFFER,&value);
    assert(value == 0x404 && ext_queries == 1 && core_queries == 0);
    ((void (*)(U,U,I*))(uintptr_t)core)(DRAW,0x9310u,&value);
    assert(value == 55 && core_queries == 1);
    resolve_symbol("glFramebufferDrawBufferEXT",(uintptr_t)ext_draw);
    resolve_symbol("glFramebufferReadBufferEXT",(uintptr_t)ext_read);
    resolve_symbol("glFramebufferDrawBuffersEXT",(uintptr_t)ext_draws_list);
    hook_FramebufferDrawBufferEXT(0,0);
    hook_FramebufferReadBufferEXT(0,0);
    assert(draw_modes[0] == 0 && draw_modes[9] == 0);
    assert(read_modes[0] == 0 && read_modes[9] == 0);
    assert(draw_binding == 3 && read_binding == 4);
    U front_left = 0x400u;
    hook_FramebufferDrawBuffersEXT(0,1,&front_left);
    hook_FramebufferReadBufferEXT(0,front_left);
    assert(draw_modes[0] == front_left && draw_modes[9] == COLOR0);
    assert(read_modes[0] == front_left && read_modes[9] == COLOR0);
    hook_FramebufferDrawBufferEXT(3,0);
    hook_FramebufferReadBufferEXT(4,0);
    assert(draw_modes[0] == front_left && read_modes[0] == front_left);
    hook_FramebufferDrawBufferEXT(0,0xDEADu);
    assert(pending_error == 0x500 && draw_modes[0] == front_left && draw_modes[9] == COLOR0);
    assert(draw_binding == 3 && read_binding == 4 && ext_draws == 4 && ext_reads == 3);
    resolve_symbol("glBindFramebuffer",(uintptr_t)bind_framebuffer);
    resolve_symbol("glGetIntegerv",(uintptr_t)get_integer);
    alias = 9;
    draw_binding = 3;
    read_binding = 4;
    draw_valid = read_valid = 0;
    hook_BindFramebuffer(DRAW,0);
    driver_gets = 0;
    I got = -1;
    hook_GetIntegerv(DRAW_BINDING,&got);
    assert(got == 0 && driver_gets == 0);
    hook_GetIntegerv(READ_BINDING,&got);
    assert(got == 4 && driver_gets == 1);
    driver_gets = 0;
    hook_GetIntegerv(READ_BINDING,&got);
    assert(got == 4 && driver_gets == 0);
    puts("AFV_DIRECT_EXT_DISPATCH_PASS: distinct ABI, default selectors, explicit FBO isolation, bindings and driver errors");
    return 0;
}
