#include <jni.h>
#include <stdint.h>
#include <string.h>

/* All entrypoints use the GL Linux ABI. Function addresses come from the same
 * EGL provider as the real Zink context; no libGL dispatch table is replaced. */
typedef unsigned int U;
typedef int I;
typedef unsigned char B;
typedef int64_t L;
#define DRAW 0x8CA9u
#define READ 0x8CA8u
#define DRAW_BINDING 0x8CA6u
#define READ_BINDING 0x8CAAu
#define DRAW_BUFFER 0x0C01u
#define READ_BUFFER 0x0C02u
#define DRAW_BUFFER0 0x8825u
#define COLOR0 0x8CE0u
static _Thread_local U alias;
static _Thread_local I default_draw, default_read;
static _Thread_local I max_draw_buffers;
static _Thread_local jlong binds, named_blits, queries;

#define PROC(R, N, A) static R (*real_##N) A
PROC(void, BindFramebuffer, (U,U));
PROC(void, DeleteFramebuffers, (I,const U*));
PROC(void, GetIntegerv, (U,I*));
PROC(void, GetBooleanv, (U,B*));
PROC(void, GetFloatv, (U,float*));
PROC(void, GetDoublev, (U,double*));
PROC(void, GetInteger64v, (U,L*));
PROC(void, ReadBuffer, (U));
PROC(void, DrawBuffer, (U));
PROC(void, DrawBuffers, (I,const U*));
PROC(void, NamedFramebufferReadBuffer, (U,U));
PROC(void, NamedFramebufferDrawBuffer, (U,U));
PROC(void, NamedFramebufferDrawBuffers, (U,I,const U*));
PROC(void, BlitNamedFramebuffer, (U,U,I,I,I,I,I,I,I,I,U,U));
PROC(void, ClearNamedFramebufferiv, (U,U,I,const I*));
PROC(void, ClearNamedFramebufferuiv, (U,U,I,const U*));
PROC(void, ClearNamedFramebufferfv, (U,U,I,const float*));
PROC(void, ClearNamedFramebufferfi, (U,U,I,float,I));
PROC(void, GetFramebufferAttachmentParameteriv, (U,U,U,I*));
PROC(void, GetFramebufferParameteriv, (U,U,I*));
PROC(void, InvalidateFramebuffer, (U,I,const U*));
PROC(void, InvalidateSubFramebuffer, (U,I,const U*,I,I,I,I));
PROC(void, FramebufferTexture1D, (U,U,U,U,I));
PROC(void, FramebufferTexture2D, (U,U,U,U,I));
PROC(void, FramebufferTexture3D, (U,U,U,U,I,I));
PROC(void, FramebufferTextureLayer, (U,U,U,I,I));
PROC(void, FramebufferTexture, (U,U,U,I));
PROC(void, FramebufferRenderbuffer, (U,U,U,U));
PROC(void, FramebufferParameteri, (U,U,I));

static U binding(U target) {
    I value;
    real_GetIntegerv(target == READ ? READ_BINDING : DRAW_BINDING, &value);
    return (U)value;
}
static U selector(I value) { return value == 0 ? 0 : COLOR0; }
/* Bound default-framebuffer metadata/mutation must retain the real EGL
 * framebuffer's validation and object identity. Mutations of FBO 0 still fail. */
static U default_scope(U target) {
    if (!alias) return 0;
    U current = binding(target);
    if (current != alias) return 0;
    real_BindFramebuffer(target == READ ? READ : DRAW, 0);
    return current;
}
static void restore(U target, U current) {
    if (current) real_BindFramebuffer(target == READ ? READ : DRAW, current);
}
static int virtual_parameter(U pname, I *value) {
    if (!alias) return 0;
    if (pname == DRAW_BINDING || pname == READ_BINDING) {
        real_GetIntegerv(pname, value);
        if ((U)*value == alias) { *value = 0; queries++; }
        return 1;
    } else if (pname == READ_BUFFER && binding(READ) == alias) {
        *value = default_read;
    } else if ((pname == DRAW_BUFFER || (pname >= DRAW_BUFFER0 && pname < DRAW_BUFFER0 + (U)max_draw_buffers)) && binding(DRAW) == alias) {
        *value = pname == DRAW_BUFFER || pname == DRAW_BUFFER0 ? default_draw : 0;
    } else return 0;
    queries++;
    return 1;
}
static void hook_BindFramebuffer(U target, U framebuffer) {
    if (alias && framebuffer == 0) { framebuffer = alias; binds++; }
    real_BindFramebuffer(target, framebuffer);
}
static void hook_DeleteFramebuffers(I count, const U *framebuffers) {
    real_DeleteFramebuffers(count,framebuffers);
    if (alias) {
        /* Deleting a bound explicit FBO implicitly binds logical default 0. */
        if (binding(DRAW) == 0) real_BindFramebuffer(DRAW,alias);
        if (binding(READ) == 0) real_BindFramebuffer(READ,alias);
    }
}
#define GETTER(N,T) static void hook_##N(U pname, T *data) { \
    I value; if (virtual_parameter(pname,&value)) *data = (T)value; \
    else real_##N(pname,data); }
GETTER(GetIntegerv,I)
GETTER(GetFloatv,float)
GETTER(GetDoublev,double)
GETTER(GetInteger64v,L)
static void hook_GetBooleanv(U pname, B *data) {
    I value;
    if (virtual_parameter(pname,&value)) *data = value != 0;
    else real_GetBooleanv(pname,data);
}

/* Validate default read/draw selectors on real FBO 0 without consuming GL
 * errors, then mirror the resulting selector onto the imported color image. */
static void hook_ReadBuffer(U mode) {
    U old = default_scope(READ);
    real_ReadBuffer(mode);
    if (old) {
        real_GetIntegerv(READ_BUFFER,&default_read);
        restore(READ,old);
        real_ReadBuffer(selector(default_read));
    }
}
static void hook_DrawBuffer(U mode) {
    U old = default_scope(DRAW);
    real_DrawBuffer(mode);
    if (old) {
        real_GetIntegerv(DRAW_BUFFER,&default_draw);
        restore(DRAW,old);
        real_DrawBuffer(selector(default_draw));
    }
}
static void hook_DrawBuffers(I count, const U *buffers) {
    U old = default_scope(DRAW);
    real_DrawBuffers(count,buffers);
    if (old) {
        real_GetIntegerv(DRAW_BUFFER,&default_draw);
        restore(DRAW,old);
        real_DrawBuffer(selector(default_draw));
    }
}
static void hook_NamedFramebufferReadBuffer(U framebuffer,U mode) {
    real_NamedFramebufferReadBuffer(framebuffer,mode);
    if (alias && framebuffer == 0) {
        U current = binding(READ);
        real_BindFramebuffer(READ,0);
        real_GetIntegerv(READ_BUFFER,&default_read);
        real_BindFramebuffer(READ,current);
        real_NamedFramebufferReadBuffer(alias,selector(default_read));
    }
}
static void hook_NamedFramebufferDrawBuffer(U framebuffer,U mode) {
    real_NamedFramebufferDrawBuffer(framebuffer,mode);
    if (alias && framebuffer == 0) {
        U current = binding(DRAW);
        real_BindFramebuffer(DRAW,0);
        real_GetIntegerv(DRAW_BUFFER,&default_draw);
        real_BindFramebuffer(DRAW,current);
        real_NamedFramebufferDrawBuffer(alias,selector(default_draw));
    }
}
static void hook_NamedFramebufferDrawBuffers(U framebuffer,I count,const U *buffers) {
    real_NamedFramebufferDrawBuffers(framebuffer,count,buffers);
    if (alias && framebuffer == 0) {
        U current = binding(DRAW);
        real_BindFramebuffer(DRAW,0);
        real_GetIntegerv(DRAW_BUFFER,&default_draw);
        real_BindFramebuffer(DRAW,current);
        real_NamedFramebufferDrawBuffer(alias,selector(default_draw));
    }
}
static void hook_BlitNamedFramebuffer(U source,U destination,I sx0,I sy0,I sx1,I sy1,I dx0,I dy0,I dx1,I dy1,U mask,U filter) {
    if (alias) {
        if (source == 0) source = alias;
        if (destination == 0) { destination = alias; named_blits++; }
    }
    real_BlitNamedFramebuffer(source,destination,sx0,sy0,sx1,sy1,dx0,dy0,dx1,dy1,mask,filter);
}
#define CLEAR(N,T) static void hook_##N(U framebuffer,U buffer,I drawbuffer,const T *value) { \
    real_##N(alias && framebuffer == 0 ? alias : framebuffer,buffer,drawbuffer,value); }
CLEAR(ClearNamedFramebufferiv,I)
CLEAR(ClearNamedFramebufferuiv,U)
CLEAR(ClearNamedFramebufferfv,float)
static void hook_ClearNamedFramebufferfi(U framebuffer,U buffer,I drawbuffer,float depth,I stencil) {
    real_ClearNamedFramebufferfi(alias && framebuffer == 0 ? alias : framebuffer,buffer,drawbuffer,depth,stencil);
}

#define SCOPED(N,PARAMS,ARGS) static void hook_##N PARAMS { \
    U previous = default_scope(target); real_##N ARGS; restore(target,previous); }
SCOPED(GetFramebufferAttachmentParameteriv,(U target,U attachment,U pname,I *data),(target,attachment,pname,data))
SCOPED(GetFramebufferParameteriv,(U target,U pname,I *data),(target,pname,data))
SCOPED(FramebufferTexture1D,(U target,U attachment,U textarget,U texture,I level),(target,attachment,textarget,texture,level))
SCOPED(FramebufferTexture2D,(U target,U attachment,U textarget,U texture,I level),(target,attachment,textarget,texture,level))
SCOPED(FramebufferTexture3D,(U target,U attachment,U textarget,U texture,I level,I layer),(target,attachment,textarget,texture,level,layer))
SCOPED(FramebufferTextureLayer,(U target,U attachment,U texture,I level,I layer),(target,attachment,texture,level,layer))
SCOPED(FramebufferTexture,(U target,U attachment,U texture,I level),(target,attachment,texture,level))
SCOPED(FramebufferRenderbuffer,(U target,U attachment,U rbtarget,U renderbuffer),(target,attachment,rbtarget,renderbuffer))
SCOPED(FramebufferParameteri,(U target,U pname,I value),(target,pname,value))
/* Discarding the real default attachment preserves validation. Retaining the
 * shared image is legal: invalidation does not require clearing its contents. */
SCOPED(InvalidateFramebuffer,(U target,I count,const U *attachments),(target,count,attachments))
SCOPED(InvalidateSubFramebuffer,(U target,I count,const U *attachments,I x,I y,I w,I h),(target,count,attachments,x,y,w,h))

JNIEXPORT jlong JNICALL Java_dev_allforvulkan_bridge_DirectDefaultFramebuffer_resolve
  (JNIEnv *env,jclass type,jlong name,jlong address) {
    (void)env; (void)type;
    const char *symbol = (const char *)(uintptr_t)name;
#define MAP(N) if (!strcmp(symbol,"gl" #N) || !strcmp(symbol,"gl" #N "EXT") || !strcmp(symbol,"gl" #N "ARB")) { \
    if (!real_##N) real_##N = (__typeof__(real_##N))(uintptr_t)address; \
    return (jlong)(uintptr_t)&hook_##N; }
    MAP(BindFramebuffer)
    MAP(DeleteFramebuffers)
    MAP(GetIntegerv) MAP(GetBooleanv) MAP(GetFloatv) MAP(GetDoublev) MAP(GetInteger64v)
    MAP(ReadBuffer) MAP(DrawBuffer) MAP(DrawBuffers)
    MAP(NamedFramebufferReadBuffer) MAP(NamedFramebufferDrawBuffer) MAP(NamedFramebufferDrawBuffers)
    MAP(BlitNamedFramebuffer)
    MAP(ClearNamedFramebufferiv) MAP(ClearNamedFramebufferuiv) MAP(ClearNamedFramebufferfv) MAP(ClearNamedFramebufferfi)
    MAP(GetFramebufferAttachmentParameteriv) MAP(GetFramebufferParameteriv)
    MAP(FramebufferTexture1D) MAP(FramebufferTexture2D) MAP(FramebufferTexture3D) MAP(FramebufferTextureLayer)
    MAP(FramebufferTexture) MAP(FramebufferRenderbuffer) MAP(FramebufferParameteri)
    MAP(InvalidateFramebuffer) MAP(InvalidateSubFramebuffer)
#undef MAP
    return address;
}
JNIEXPORT void JNICALL Java_dev_allforvulkan_bridge_DirectDefaultFramebuffer_redirect
  (JNIEnv *env,jclass type,jint framebuffer) {
    (void)type;
    if (!real_BindFramebuffer || !real_GetIntegerv || !real_NamedFramebufferReadBuffer || !real_NamedFramebufferDrawBuffer) {
        (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Incomplete direct-output GL entrypoint table");
        return;
    }
    U draw = binding(DRAW), read = binding(READ), previous = alias;
    real_GetIntegerv(0x8824u,&max_draw_buffers);
    if (previous && draw == previous) draw = 0;
    if (previous && read == previous) read = 0;
    /* FBO 0 remains the authoritative source for default framebuffer state. */
    real_BindFramebuffer(DRAW,0); real_GetIntegerv(DRAW_BUFFER,&default_draw);
    real_BindFramebuffer(READ,0); real_GetIntegerv(READ_BUFFER,&default_read);
    alias = (U)framebuffer;
    if (alias) {
        real_NamedFramebufferDrawBuffer(alias,selector(default_draw));
        real_NamedFramebufferReadBuffer(alias,selector(default_read));
    }
    real_BindFramebuffer(DRAW,alias && draw == 0 ? alias : draw);
    real_BindFramebuffer(READ,alias && read == 0 ? alias : read);
}
JNIEXPORT jlongArray JNICALL Java_dev_allforvulkan_bridge_DirectDefaultFramebuffer_counters
  (JNIEnv *env,jclass type) {
    (void)type;
    jlong values[] = {binds,named_blits,queries};
    jlongArray result = (*env)->NewLongArray(env,3);
    if (result) (*env)->SetLongArrayRegion(env,result,0,3,values);
    return result;
}
