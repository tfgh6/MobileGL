// SPDX-License-Identifier: LGPL-3.0-only
// Runtime renderer dispatcher: reads /sdcard/MobileGL/runtime.conf and forwards
// every EGL/GL entry point either to SimpleFPEWrapper (and its MobileGL backend)
// or directly to a user-selected libMobileGL.so. Imported libraries are mapped
// through memfd so they do not need to live in the APK's nativeLibraryDir.
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "proxy_symbols.h"

#define TAG "RendererProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void proxy_missing(void) {
    LOGE("renderer symbol was not found in the selected backend");
    abort();
}

static void trim(char *s) {
    if (!s) return;
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r' || s[n - 1] == ' ' || s[n - 1] == '\t')) s[--n] = 0;
    char *p = s;
    while (*p == ' ' || *p == '\t') p++;
    if (p != s) memmove(s, p, strlen(p) + 1);
}

static int truthy(const char *value) {
    if (!value || !value[0]) return 0;
    return strcmp(value, "0") != 0 && strcasecmp(value, "false") != 0;
}

static void read_config(char *mode, size_t mode_len, char *so, size_t so_len) {
    mode[0] = 0;
    so[0] = 0;
    // Environment is the fallback used when the plugin-managed file is not
    // readable from the game process (launcher without storage permission).
    const char *env_mode = getenv("RENDERER_MODE");
    if (env_mode && env_mode[0]) snprintf(mode, mode_len, "%s", env_mode);
    const char *env_enabled = getenv("SFPEW_ENABLED");
    if (env_enabled && env_enabled[0]) snprintf(mode, mode_len, "%s", truthy(env_enabled) ? "sfpew" : "direct");
    const char *env_so = getenv("RENDERER_SO");
    if (env_so && env_so[0]) snprintf(so, so_len, "%s", env_so);
    const char *env = getenv("MOBILEGL_CONFIG_FILE");
    const char *path = (env && env[0]) ? env : "/sdcard/MobileGL/runtime.conf";
    FILE *file = fopen(path, "r");
    if (!file) {
        LOGE("cannot read %s, using env/defaults", path);
        if (!mode[0]) snprintf(mode, mode_len, "direct");
        return;
    }
    char line[1024];
    while (fgets(line, sizeof(line), file)) {
        trim(line);
        if (!line[0] || line[0] == '#') continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        trim(line);
        char *value = eq + 1;
        trim(value);
        if (strcmp(line, "RENDERER_MODE") == 0) {
            snprintf(mode, mode_len, "%s", value);
        } else if (strcmp(line, "RENDERER_SO") == 0) {
            snprintf(so, so_len, "%s", value);
        } else if (strcmp(line, "SFPEW_ENABLED") == 0) {
            snprintf(mode, mode_len, "%s", truthy(value) ? "sfpew" : "direct");
        }
    }
    fclose(file);
    if (!mode[0]) snprintf(mode, mode_len, "direct");
}

// Load a .so that lives outside the app's native library directory: copy it into
// an anonymous memfd and dlopen /proc/self/fd/N. This mirrors libadrenotools and
// keeps working where direct dlopen of /sdcard is denied (noexec / W^X).
static void *memfd_load(const char *path) {
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) return NULL;
    int fd = (int)syscall(__NR_memfd_create, "renderer", 0x0001 /* MFD_CLOEXEC */);
    if (fd < 0) {
        close(in);
        return NULL;
    }
    char buffer[64 * 1024];
    ssize_t n;
    while ((n = read(in, buffer, sizeof(buffer))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t written = write(fd, buffer + off, (size_t)(n - off));
            if (written <= 0) {
                close(in);
                close(fd);
                return NULL;
            }
            off += written;
        }
    }
    close(in);
    if (n < 0) {
        close(fd);
        return NULL;
    }
    char proc[64];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
    void *handle = dlopen(proc, RTLD_NOW | RTLD_LOCAL);
    if (!handle) LOGE("memfd dlopen failed for %s: %s", path, dlerror());
    // The mapped library keeps its own reference; closing the fd is fine.
    close(fd);
    return handle;
}

static void *open_target(void) {
    char mode[16];
    char so[512];
    read_config(mode, sizeof(mode), so, sizeof(so));
    const char *env_so = getenv("MOBILEGL_RENDERER_SO");
    if (env_so && env_so[0]) snprintf(so, sizeof(so), "%s", env_so);

    if (strcmp(mode, "sfpew") == 0) {
        if (so[0]) setenv("SFPEW_EGL", so, 1);
        void *handle = dlopen("libSfpew_real.so", RTLD_NOW | RTLD_LOCAL);
        if (handle) {
            LOGI("SFPEW wrapper enabled, backend=%s", so[0] ? so : "libMobileGL.so");
            return handle;
        }
        LOGE("libSfpew_real.so not loadable: %s; falling back to direct", dlerror());
    }
    if (so[0]) {
        void *handle = (strchr(so, '/') ? memfd_load(so) : dlopen(so, RTLD_NOW | RTLD_LOCAL));
        if (handle) {
            LOGI("direct renderer: %s", so);
            return handle;
        }
        LOGE("cannot load direct renderer %s: %s", so, dlerror());
    }
    void *fallback = dlopen("libMobileGL.so", RTLD_NOW | RTLD_LOCAL);
    if (!fallback) LOGE("libMobileGL.so not loadable: %s", dlerror());
    return fallback;
}

__attribute__((constructor)) static void init_renderer_proxy(void) {
    void *target = open_target();
    if (!target) {
        LOGE("no renderer backend available");
    }
    // In SFPEW mode some Desktop-GL entry points are not wrapped by SFPEW.
    // Resolve those directly from MobileGL instead of aborting mid-game.
    void *direct = dlopen("libMobileGL.so", RTLD_NOW | RTLD_LOCAL);
    for (unsigned i = 0; i < g_proxy_symbol_count; ++i) {
        void *fn = target ? dlsym(target, g_proxy_symbols[i].name) : NULL;
        if (!fn && direct) fn = dlsym(direct, g_proxy_symbols[i].name);
        *g_proxy_symbols[i].slot = fn ? fn : (void *)proxy_missing;
    }
    LOGI("renderer proxy ready (%u symbols, %s)", g_proxy_symbol_count, getenv("RENDERER_MODE") ? "env" : "config");
}