// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.driver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turnip (Mesa Freedreno Vulkan) driver support for Adreno devices.
 *
 * The APK bundles libvulkan_freedreno.so in jniLibs. An external Turnip .so can
 * also be imported to /sdcard/MobileGL/turnip/ so it survives app updates.
 */
class TurnipRepository(private val context: Context) {
    val bundledFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libvulkan_freedreno.so")

    val externalRoot: File = File("/sdcard/MobileGL/turnip")

    fun isBundledAvailable(): Boolean = bundledFile.isFile

    fun externalFiles(): List<File> =
        (externalRoot.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".so") }

    suspend fun import(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "turnip-" + System.currentTimeMillis() + ".so")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: error("无法读取所选文件")
            if (temp.length() < 1024 * 1024L) error("文件过小，不是 Turnip 驱动库")
            if (!isArm64Elf(temp)) error("不是 arm64 ELF（需要 AArch64 64 位小端 .so）")
            externalRoot.mkdirs()
            val name = sanitize(uri.lastPathSegment?.substringAfterLast('/') ?: "libvulkan_freedreno.so")
                .let { if (it.endsWith(".so")) it else it + ".so" }
            val target = File(externalRoot, name)
            temp.copyTo(target, overwrite = true)
            temp.delete()
            target
        }
    }

    fun delete(file: File): Boolean {
        val canonicalRoot = externalRoot.canonicalPath + File.separator
        return file.canonicalPath.startsWith(canonicalRoot) && file.delete()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun isArm64Elf(file: File): Boolean {
        val header = ByteArray(20)
        val read = file.inputStream().use { it.read(header) }
        if (read < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return false
        val is64 = header[4].toInt() == 2
        val le = header[5].toInt() == 1
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return is64 && le && machine == 0xB7
    }
}
