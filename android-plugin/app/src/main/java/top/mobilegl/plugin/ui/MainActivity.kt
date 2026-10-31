// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import top.mobilegl.plugin.NativeEnv
import top.mobilegl.plugin.config.ConfigStore

/**
 * Configuration UI of the MobileGL renderer plugin. The renderer itself is loaded by the
 * Minecraft launcher; this activity only edits MobileGL's configuration and runs the existing
 * Driver POST for diagnostics.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        // Apply before libMobileGL.so can be loaded by the self-test screen.
        NativeEnv.apply(store.load())
        setContent { MobileGlPluginApp() }
    }
}
