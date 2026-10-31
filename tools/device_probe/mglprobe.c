#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef unsigned int EGLBoolean;
typedef int EGLint;
#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_DEFAULT_DISPLAY ((void *)0)

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "libMobileGL.so";
    void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!lib) { printf("dlopen %s failed: %s\n", path, dlerror()); return 2; }
    EGLDisplay (*getDisplay)(void *) = (EGLDisplay (*)(void *))dlsym(lib, "eglGetDisplay");
    EGLBoolean (*initialize)(EGLDisplay, EGLint *, EGLint *) = (EGLBoolean (*)(EGLDisplay, EGLint *, EGLint *))dlsym(lib, "eglInitialize");
    EGLint (*getError)(void) = (EGLint (*)(void))dlsym(lib, "eglGetError");
    const char *(*queryString)(EGLDisplay, EGLint) = (const char *(*)(EGLDisplay, EGLint))dlsym(lib, "eglQueryString");
    if (!getDisplay || !initialize) { printf("missing egl entrypoints\n"); return 2; }
    EGLDisplay d = getDisplay(EGL_DEFAULT_DISPLAY);
    printf("eglGetDisplay=%p err=0x%x\n", d, getError ? getError() : 0);
    if (d == EGL_NO_DISPLAY) return 3;
    EGLint major = 0, minor = 0;
    EGLBoolean ok = initialize(d, &major, &minor);
    printf("eglInitialize=%u version=%d.%d err=0x%x\n", ok, major, minor, getError ? getError() : 0);
    if (queryString) printf("EGL_VENDOR=%s\n", queryString(d, 0x3053));
    return ok ? 0 : 3;
}
