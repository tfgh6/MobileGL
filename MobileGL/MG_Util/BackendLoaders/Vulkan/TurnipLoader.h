// MobileGL - MobileGL/MG_Util/BackendLoaders/Vulkan/TurnipLoader.h
// Copyright (c) 2025-2026 MobileGL-Dev
// Licensed under the GNU Lesser General Public License v3.0:
//   https://www.gnu.org/licenses/gpl-3.0.txt
//   https://www.gnu.org/licenses/lgpl-3.0.txt
// SPDX-License-Identifier: LGPL-3.0-only
// End of Source File Header

#pragma once
#include <Includes.h>

#if defined(__ANDROID__)
namespace MobileGL::MG_Util::BackendLoader {
    struct VulkanLoaderBootstrapResult {
        // True when volk has at least one working loader (Turnip or system libvulkan).
        Bool Initialized = false;
        // True when MOBILEGL_TURNIP_ENABLED was requested (independently of success).
        Bool TurnipRequested = false;
        // True when the Vulkan entry points were taken from a Turnip driver opened through
        // libadrenotools instead of the system libvulkan loader.
        Bool UsingTurnip = false;
        String TurnipDriverPath;
        String HookLibraryDir;
        VkResult VolkResult = VK_ERROR_INITIALIZATION_FAILED;
    };

    // Initializes volk exactly once for the DirectVulkan backend.
    //
    // Preference order on Android:
    //   1. Turnip loaded through libadrenotools when MOBILEGL_TURNIP_ENABLED is truthy.
    //      MOBILEGL_TURNIP_ENABLED / MOBILEGL_TURNIP_PATH are read from the process
    //      environment first and fall back to the plugin-managed runtime.conf file, so
    //      the plugin UI is authoritative even when libRendererProxy did not setenv()
    //      the values before libMobileGL.so was loaded.
    //   2. The system libvulkan loader through volkInitialize().
    //
    // The hook library directory is always the directory that contains libMobileGL.so
    // (the plugin's nativeLibraryDir); libadrenotools is loaded from the same directory.
    // External drivers outside app-private storage (e.g. /sdcard) are staged into the
    // process-private cache directory before libadrenotools sees them. Every failure is
    // logged and falls back to the system loader; this function never throws.
    const VulkanLoaderBootstrapResult& EnsureVulkanLoaderInitialized();
} // namespace MobileGL::MG_Util::BackendLoader
#endif
