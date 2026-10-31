#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

int main(void) {
    void *lib = dlopen("libvulkan.so", RTLD_NOW);
    if (!lib) { printf("libvulkan dlopen failed: %s\n", dlerror()); return 2; }
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)dlsym(lib, "vkCreateInstance");
    PFN_vkEnumeratePhysicalDevices enumDev = (PFN_vkEnumeratePhysicalDevices)dlsym(lib, "vkEnumeratePhysicalDevices");
    PFN_vkEnumerateDeviceExtensionProperties enumExt = (PFN_vkEnumerateDeviceExtensionProperties)dlsym(lib, "vkEnumerateDeviceExtensionProperties");
    PFN_vkGetPhysicalDeviceProperties props = (PFN_vkGetPhysicalDeviceProperties)dlsym(lib, "vkGetPhysicalDeviceProperties");
    if (!create || !enumDev || !enumExt) { printf("missing vk entrypoints\n"); return 2; }
    VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    if (create(&ci, NULL, &inst) != VK_SUCCESS) { printf("vkCreateInstance failed\n"); return 3; }
    uint32_t n = 0;
    enumDev(inst, &n, NULL);
    VkPhysicalDevice devs[8];
    if (n > 8) n = 8;
    if (enumDev(inst, &n, devs) != VK_SUCCESS) { printf("enum devices failed\n"); return 3; }
    printf("devices=%u\n", n);
    for (uint32_t i = 0; i < n; ++i) {
        VkPhysicalDeviceProperties p;
        props(devs[i], &p);
        printf("device[%u]=%s api=%u.%u.%u\n", i, p.deviceName, VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion));
        uint32_t c = 0;
        enumExt(devs[i], NULL, &c, NULL);
        VkExtensionProperties *exts = (VkExtensionProperties *)calloc(c, sizeof(*exts));
        enumExt(devs[i], NULL, &c, exts);
        int found = 0, khr = 0;
        for (uint32_t j = 0; j < c; ++j) {
            if (strcmp(exts[j].extensionName, "VK_EXT_vertex_attribute_divisor") == 0) found = 1;
            if (strcmp(exts[j].extensionName, "VK_KHR_vertex_attribute_divisor") == 0) khr = 1;
        }
        printf("ext_count=%u VK_EXT_vertex_attribute_divisor=%d VK_KHR_vertex_attribute_divisor=%d\n", c, found, khr);
        for (uint32_t j = 0; j < c; ++j) if (strstr(exts[j].extensionName, "divisor")) printf("  %s\n", exts[j].extensionName);
        free(exts);
    }
    return 0;
}