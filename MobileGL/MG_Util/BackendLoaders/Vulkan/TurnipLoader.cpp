// MobileGL - MobileGL/MG_Util/BackendLoaders/Vulkan/TurnipLoader.cpp
// Copyright (c) 2025-2026 MobileGL-Dev
// Licensed under the GNU Lesser General Public License v3.0:
//   https://www.gnu.org/licenses/gpl-3.0.txt
//   https://www.gnu.org/licenses/lgpl-3.0.txt
// SPDX-License-Identifier: LGPL-3.0-only
// End of Source File Header

#include "TurnipLoader.h"

#if defined(__ANDROID__)

#include <android/api-level.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#if defined(MOBILEGL_HAS_ADRENOTOOLS)
#include <adrenotools/driver.h>
#endif

namespace MobileGL::MG_Util::BackendLoader {
    namespace {
        // libadrenotools is statically linked into libMobileGL on arm64-v8a (see CMakeLists.txt).
        // It must not be loaded from the plugin's nativeLibraryDir with dlopen(): that
        // directory is not part of the app linker namespace's search path.
        constexpr const char* kTurnipEnabledKey = "MOBILEGL_TURNIP_ENABLED";
        constexpr const char* kTurnipPathKey = "MOBILEGL_TURNIP_PATH";
        constexpr const char* kTurnipHookDirKey = "MOBILEGL_TURNIP_HOOK_DIR";
        constexpr const char* kStagingDirKey = "MOBILEGL_TURNIP_STAGING_DIR";
        constexpr const char* kRuntimeConfPath = "/sdcard/MobileGL/runtime.conf";
        constexpr const char* kBundledTurnipName = "libvulkan_freedreno.so";

        String Trim(const String& value) {
            SizeT begin = 0;
            SizeT end = value.size();
            while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) {
                ++begin;
            }
            while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
                --end;
            }
            return value.substr(begin, end - begin);
        }

        Bool IsTruthy(const String& value) {
            if (value.empty() || value == "0") {
                return false;
            }
            String lowered = value;
            std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            return lowered != "false";
        }

        // Mirrors MobileGL::MG_ConfigLoader::LoadRuntimeConfigFile so the plugin UI's
        // /sdcard/MobileGL/runtime.conf is honored even without libRendererProxy setenv().
        Bool ReadRuntimeConfEntry(const char* wantedKey, String& outValue) {
            const char* configuredPath = std::getenv("MOBILEGL_CONFIG_FILE");
            const String path = (configuredPath != nullptr && configuredPath[0] != '\0')
                                    ? String(configuredPath)
                                    : String(kRuntimeConfPath);
            std::ifstream stream(path);
            if (!stream.is_open()) {
                return false;
            }

            Bool found = false;
            String line;
            while (std::getline(stream, line)) {
                const SizeT comment = line.find('#');
                if (comment != String::npos) {
                    line.resize(comment);
                }
                const String trimmed = Trim(line);
                if (trimmed.empty()) {
                    continue;
                }
                const SizeT equals = trimmed.find('=');
                if (equals == String::npos) {
                    continue;
                }
                if (Trim(trimmed.substr(0, equals)) != wantedKey) {
                    continue;
                }
                outValue = Trim(trimmed.substr(equals + 1));
                found = true;
            }
            return found;
        }

        // Environment wins when present; the plugin-managed runtime.conf is the fallback.
        Bool ResolveSetting(const char* key, String& outValue, const char*& outSource) {
            const char* environmentValue = std::getenv(key);
            if (environmentValue != nullptr && environmentValue[0] != '\0') {
                outValue = environmentValue;
                outSource = "environment";
                return true;
            }
            if (ReadRuntimeConfEntry(key, outValue)) {
                outSource = "runtime.conf";
                return true;
            }
            outValue.clear();
            outSource = "unset";
            return false;
        }

        Bool StartsWith(const String& value, const char* prefix) {
            const SizeT prefixLength = std::strlen(prefix);
            return value.size() >= prefixLength && value.compare(0, prefixLength, prefix) == 0;
        }

        String JoinPath(const String& directory, const String& name) {
            if (directory.empty()) {
                return name;
            }
            if (directory.back() == '/') {
                return directory + name;
            }
            return directory + "/" + name;
        }

        String DirName(const String& path) {
            const SizeT slash = path.find_last_of('/');
            if (slash == String::npos) {
                return {};
            }
            if (slash == 0) {
                return "/";
            }
            return path.substr(0, slash);
        }

        String BaseName(const String& path) {
            const SizeT slash = path.find_last_of('/');
            return slash == String::npos ? path : path.substr(slash + 1);
        }

        Bool IsRegularFile(const String& path) {
            struct stat status {};
            return stat(path.c_str(), &status) == 0 && S_ISREG(status.st_mode);
        }

        // libadrenotools loads both hook libraries through hookLibDir.
        Bool HasHookLibraries(const String& directory) {
            if (directory.empty()) {
                return false;
            }
            return IsRegularFile(JoinPath(directory, "libhook_impl.so")) &&
                   IsRegularFile(JoinPath(directory, "libmain_hook.so"));
        }

        Bool IsExternalStoragePath(const String& path) {
            return StartsWith(path, "/sdcard") || StartsWith(path, "/storage") || StartsWith(path, "/mnt/");
        }

        // libMobileGL.so is loaded from the plugin's nativeLibraryDir, which also holds
        // libadrenotools.so, libhook_impl.so, libmain_hook.so and the bundled Turnip .so.
        String GetSelfLibraryDirectory() {
            Dl_info info {};
            if (dladdr(reinterpret_cast<void*>(&EnsureVulkanLoaderInitialized), &info) == 0 ||
                info.dli_fname == nullptr) {
                return {};
            }
            const String libraryPath(info.dli_fname);
            // A launcher that loads an imported renderer through a memfd reports a synthetic
            // path here (/proc/self/fd/N or /memfd:...), which is not a usable hookLibDir.
            if (StartsWith(libraryPath, "/proc/") || StartsWith(libraryPath, "/dev/") ||
                StartsWith(libraryPath, "/memfd:")) {
                return {};
            }
            const String directory = DirName(libraryPath);
            // Only trust the path when the adrenotools hook libraries actually sit beside it.
            return HasHookLibraries(directory) ? directory : String {};
        }

        Bool EnsureDirectory(const String& directory) {
            if (directory.empty()) {
                return false;
            }
            struct stat status {};
            if (stat(directory.c_str(), &status) == 0) {
                return S_ISDIR(status.st_mode);
            }
            const SizeT slash = directory.find_last_of('/');
            if (slash != String::npos && slash > 0) {
                EnsureDirectory(directory.substr(0, slash));
            }
            return mkdir(directory.c_str(), 0700) == 0;
        }

        Bool IsDirectoryWritable(const String& directory) {
            const String probe = JoinPath(directory, ".mobilegl-write-probe");
            FILE* file = std::fopen(probe.c_str(), "wb");
            if (file == nullptr) {
                return false;
            }
            std::fclose(file);
            std::remove(probe.c_str());
            return true;
        }

        String GetProcessPackageName() {
            std::ifstream stream("/proc/self/cmdline", std::ios::binary);
            if (!stream.is_open()) {
                return {};
            }
            String name;
            std::getline(stream, name, '\0');
            const SizeT colon = name.find(':');
            if (colon != String::npos) {
                name.resize(colon);
            }
            if (name.empty()) {
                return {};
            }
            for (const char character : name) {
                if (!(std::isalnum(static_cast<unsigned char>(character)) || character == '.' || character == '_')) {
                    return {};
                }
            }
            return name;
        }

        // Returns an app-private, writable directory. Android's linker refuses to dlopen
        // libraries from /sdcard, and libadrenotools also needs a writable tmpLibDir on
        // API < 29 (older devices), so both cases use this staging directory.
        String FindWritablePrivateDirectory() {
            std::vector<String> candidates;
            if (const char* overrideDirectory = std::getenv(kStagingDirKey);
                overrideDirectory != nullptr && overrideDirectory[0] != '\0') {
                candidates.emplace_back(overrideDirectory);
            }
            const String packageName = GetProcessPackageName();
            if (!packageName.empty()) {
                candidates.push_back("/data/user/0/" + packageName + "/cache/mobilegl-turnip");
                candidates.push_back("/data/data/" + packageName + "/cache/mobilegl-turnip");
            }
            if (const char* temporaryDirectory = std::getenv("TMPDIR");
                temporaryDirectory != nullptr && temporaryDirectory[0] != '\0') {
                candidates.push_back(JoinPath(temporaryDirectory, "mobilegl-turnip"));
            }
            for (const String& candidate : candidates) {
                if (!EnsureDirectory(candidate)) {
                    continue;
                }
                if (IsDirectoryWritable(candidate)) {
                    return candidate;
                }
            }
            return {};
        }

        Bool IsUpToDate(const String& source, const String& target) {
            struct stat sourceStatus {};
            struct stat targetStatus {};
            if (stat(source.c_str(), &sourceStatus) != 0 || stat(target.c_str(), &targetStatus) != 0) {
                return false;
            }
            return sourceStatus.st_size == targetStatus.st_size && targetStatus.st_mtime >= sourceStatus.st_mtime;
        }

        Bool CopyFile(const String& source, const String& target) {
            std::ifstream input(source, std::ios::binary);
            if (!input.is_open()) {
                return false;
            }
            std::ofstream output(target, std::ios::binary | std::ios::trunc);
            if (!output.is_open()) {
                return false;
            }
            output << input.rdbuf();
            output.flush();
            return output.good();
        }

        Bool StageExternalDriver(const String& sourcePath, String& outDirectory, String& outName) {
            const String stagingDirectory = FindWritablePrivateDirectory();
            if (stagingDirectory.empty()) {
                MGLOG_W("Turnip: external driver %s needs a private staging directory but none is writable",
                        sourcePath.c_str());
                return false;
            }
            outName = BaseName(sourcePath);
            const String target = JoinPath(stagingDirectory, outName);
            if (!IsUpToDate(sourcePath, target)) {
                MGLOG_I("Turnip: staging external driver %s -> %s", sourcePath.c_str(), target.c_str());
                if (!CopyFile(sourcePath, target)) {
                    MGLOG_W("Turnip: failed to copy external driver %s to %s", sourcePath.c_str(), target.c_str());
                    return false;
                }
            }
            outDirectory = stagingDirectory;
            return true;
        }

        Bool ResolveTurnipDriverPath(const String& nativeLibraryDirectory, String& outPath, const char*& outSource) {
            String configuredPath;
            const char* pathSource = "unset";
            if (ResolveSetting(kTurnipPathKey, configuredPath, pathSource) && !configuredPath.empty()) {
                String candidate = configuredPath;
                if (candidate.front() != '/') {
                    candidate = JoinPath(nativeLibraryDirectory, candidate);
                }
                if (!IsRegularFile(candidate)) {
                    MGLOG_W("Turnip: MOBILEGL_TURNIP_PATH=%s (from %s) does not exist or is not a file",
                            candidate.c_str(), pathSource);
                    return false;
                }
                outPath = candidate;
                outSource = pathSource;
                return true;
            }

            const String bundledPath = JoinPath(nativeLibraryDirectory, kBundledTurnipName);
            if (!IsRegularFile(bundledPath)) {
                MGLOG_W("Turnip: MOBILEGL_TURNIP_PATH is not set and the bundled driver %s is missing",
                        bundledPath.c_str());
                return false;
            }
            outPath = bundledPath;
            outSource = "bundled";
            return true;
        }

        Bool TryOpenTurnipDriver(VulkanLoaderBootstrapResult& result) {
            String enabledValue;
            const char* enabledSource = "unset";
            const Bool enabledFound = ResolveSetting(kTurnipEnabledKey, enabledValue, enabledSource);
            if (!enabledFound || !IsTruthy(enabledValue)) {
                MGLOG_I("Turnip: not enabled (%s=%s, source=%s); using the system Vulkan loader",
                        kTurnipEnabledKey, enabledFound ? enabledValue.c_str() : "<unset>", enabledSource);
                return false;
            }
            result.TurnipRequested = true;
            MGLOG_I("Turnip: enabled through %s (%s=%s)", enabledSource, kTurnipEnabledKey, enabledValue.c_str());

            // libadrenotools relies on a linker-namespace bypass that only exists on API >= 28
            // (linkernsbypass_load_status() is false below that); older devices keep the system loader.
            const Int deviceApiLevel = android_get_device_api_level();
            if (deviceApiLevel < 28) {
                MGLOG_W("Turnip: libadrenotools requires API >= 28 (device API is %d); using system Vulkan",
                        deviceApiLevel);
                return false;
            }
#if !defined(MOBILEGL_HAS_ADRENOTOOLS)
            MGLOG_W("Turnip: libadrenotools is not built for this ABI; using system Vulkan");
            return false;
#endif

            const String selfLibraryDirectory = GetSelfLibraryDirectory();

            String driverPath;
            const char* driverSource = "unset";
            if (!ResolveTurnipDriverPath(selfLibraryDirectory, driverPath, driverSource)) {
                return false;
            }

            String driverDirectory = DirName(driverPath);
            String driverName = BaseName(driverPath);
            if (driverDirectory.empty() || driverName.empty()) {
                MGLOG_W("Turnip: invalid driver path %s", driverPath.c_str());
                return false;
            }

            if (IsExternalStoragePath(driverDirectory)) {
                String stagedDirectory;
                String stagedName;
                if (!StageExternalDriver(driverPath, stagedDirectory, stagedName)) {
                    MGLOG_W("Turnip: external driver %s cannot be staged; falling back to system Vulkan",
                            driverPath.c_str());
                    return false;
                }
                driverDirectory = stagedDirectory;
                driverName = stagedName;
                driverPath = JoinPath(stagedDirectory, stagedName);
            }
            result.TurnipDriverPath = driverPath;

            // hookLibDir must be the plugin nativeLibraryDir. dladdr() is not reliable when the
            // launcher maps libMobileGL.so from a memfd, so an explicit override and the bundled
            // driver's directory are accepted as fallbacks before giving up.
            String hookLibraryDirectory;
            const char* hookSource = "unset";
            String configuredHookDirectory;
            const char* configuredHookSource = "unset";
            const Bool hookDirectoryConfigured = ResolveSetting(kTurnipHookDirKey, configuredHookDirectory, configuredHookSource) &&
                                                 !configuredHookDirectory.empty();
            if (hookDirectoryConfigured && HasHookLibraries(configuredHookDirectory)) {
                hookLibraryDirectory = configuredHookDirectory;
                hookSource = configuredHookSource;
            } else if (HasHookLibraries(selfLibraryDirectory)) {
                hookLibraryDirectory = selfLibraryDirectory;
                hookSource = "libMobileGL.so directory";
            } else if (HasHookLibraries(driverDirectory)) {
                hookLibraryDirectory = driverDirectory;
                hookSource = "bundled driver directory";
            } else {
                MGLOG_W("Turnip: libhook_impl.so/libmain_hook.so not found in the nativeLibraryDir "
                        "(set %s to override); falling back to system Vulkan",
                        kTurnipHookDirKey);
                return false;
            }
            result.HookLibraryDir = hookLibraryDirectory;
            MGLOG_I("Turnip: hookLibDir=%s (from %s)", hookLibraryDirectory.c_str(), hookSource);

            // libadrenotools is statically linked on arm64-v8a; opening the .so from the
            // plugin's nativeLibraryDir is not portable across app linker namespaces.
            MGLOG_I("Turnip: opening %s through statically linked libadrenotools", driverPath.c_str());

            String tmpLibraryDirectory;
            if (deviceApiLevel < 29) {
                tmpLibraryDirectory = FindWritablePrivateDirectory();
                if (tmpLibraryDirectory.empty()) {
                    MGLOG_W("Turnip: API %d requires a writable tmpLibDir but none is available", deviceApiLevel);
                    return false;
                }
            }

            String customDriverDirectory = driverDirectory;
            if (customDriverDirectory.back() != '/') {
                customDriverDirectory.push_back('/');
            }

#if defined(MOBILEGL_HAS_ADRENOTOOLS)
            void* vulkanHandle = adrenotools_open_libvulkan(
                RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                tmpLibraryDirectory.empty() ? nullptr : tmpLibraryDirectory.c_str(), hookLibraryDirectory.c_str(),
                customDriverDirectory.c_str(), driverName.c_str(), nullptr, nullptr);
#else
            void* vulkanHandle = nullptr;
#endif
            if (vulkanHandle == nullptr) {
                MGLOG_W("Turnip: adrenotools_open_libvulkan failed (driver=%s, source=%s, hookLibDir=%s); "
                        "falling back to system Vulkan",
                        driverPath.c_str(), driverSource, hookLibraryDirectory.c_str());
                return false;
            }

            const auto getInstanceProcAddr =
                reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vulkanHandle, "vkGetInstanceProcAddr"));
            if (getInstanceProcAddr == nullptr) {
                MGLOG_W("Turnip: hooked libvulkan does not export vkGetInstanceProcAddr; falling back");
                return false;
            }

            volkInitializeCustom(getInstanceProcAddr);
            result.Initialized = true;
            result.UsingTurnip = true;
            result.VolkResult = VK_SUCCESS;
            MGLOG_I("Turnip: using driver %s (%s) through libadrenotools (hookLibDir=%s)", driverPath.c_str(),
                    driverSource, hookLibraryDirectory.c_str());
            return true;
        }
    } // namespace

    const VulkanLoaderBootstrapResult& EnsureVulkanLoaderInitialized() {
        static VulkanLoaderBootstrapResult result;
        static Bool attempted = false;
        if (attempted) {
            return result;
        }
        attempted = true;

        if (TryOpenTurnipDriver(result)) {
            return result;
        }
        if (result.TurnipRequested) {
            MGLOG_W("Turnip: falling back to the system libvulkan loader");
        }

        result.VolkResult = volkInitialize();
        result.Initialized = result.VolkResult == VK_SUCCESS;
        if (!result.Initialized) {
            MGLOG_E("volkInitialize() failed: %d; the native Vulkan loader is unavailable",
                    static_cast<Int>(result.VolkResult));
        } else {
            MGLOG_I("Vulkan loader: using the system libvulkan loader (volk)");
        }
        return result;
    }
} // namespace MobileGL::MG_Util::BackendLoader

#endif // defined(__ANDROID__)
