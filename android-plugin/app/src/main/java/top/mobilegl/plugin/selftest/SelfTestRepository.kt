// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.selftest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.mobilegl.plugin.NativeEnv
import top.mobilegl.plugin.PostActivity
import top.mobilegl.plugin.config.PluginConfig
import java.io.File

data class CheckItem(
    val name: String,
    val status: String,
    val detail: String,
)

data class BackendReport(
    val backend: String,
    val available: Boolean,
    val verdict: String,
    val renderer: String,
    val checks: List<CheckItem>,
)

data class SelfTestResult(
    val ok: Boolean,
    val error: String?,
    val backends: List<BackendReport>,
    val rawJson: String,
    val finishedAt: Long,
)

/**
 * Runs MobileGL's existing Driver POST (libMobileGL.so + JNI) and parses the JSON report the
 * native side already produces. Kept as a single source of truth shared with PostActivity.
 */
class SelfTestRepository(private val context: Context) {
    private val dir = File(context.filesDir, "plugin")
    private val lastReport = File(dir, "last-post.json")

    @Volatile
    private var cached: SelfTestResult? = null

    fun cached(): SelfTestResult? = cached ?: loadLast()

    suspend fun run(config: PluginConfig, libraryPath: String? = null): SelfTestResult = withContext(Dispatchers.IO) {
        NativeEnv.apply(config)
        val raw = try {
            if (libraryPath.isNullOrBlank()) {
                PostActivity.runDriverPostJson()
            } else {
                PostActivity.runDriverPostJsonFrom(libraryPath)
            }
        } catch (t: Throwable) {
            return@withContext SelfTestResult(
                ok = false,
                error = t.message ?: t.toString(),
                backends = emptyList(),
                rawJson = "",
                finishedAt = System.currentTimeMillis(),
            )
        }
        val parsed = parse(raw)
        val result = parsed.copy(finishedAt = System.currentTimeMillis())
        cached = result
        save(raw)
        result
    }

    fun rawShareText(): String = cached()?.rawJson ?: ""

    private fun loadLast(): SelfTestResult? {
        if (!lastReport.isFile) return null
        return runCatching { parse(lastReport.readText()).copy(finishedAt = lastReport.lastModified()) }.getOrNull()
    }

    private fun save(raw: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "last-post.json.tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(lastReport)) {
                lastReport.writeText(raw)
                tmp.delete()
            }
        }
    }

    private fun parse(raw: String): SelfTestResult {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return SelfTestResult(false, "自检返回的不是合法 JSON", emptyList(), raw, System.currentTimeMillis())
        }
        val error = if (root.has("error")) root.optString("error") else ""
        if (error.isNotEmpty()) {
            return SelfTestResult(false, error, emptyList(), raw, System.currentTimeMillis())
        }

        val reports = mutableListOf<BackendReport>()
        val array = root.optJSONArray("backends")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { reports.add(parseBackend(it, "backend")) }
            }
        } else {
            val names = linkedMapOf("gles" to "DirectGLES", "vulkan" to "DirectVulkan")
            names.forEach { (key, title) ->
                root.optJSONObject(key)?.let { reports.add(parseBackend(it, title)) }
            }
        }
        if (reports.isEmpty()) {
            return SelfTestResult(false, "自检报告中没有后端段落", emptyList(), raw, System.currentTimeMillis())
        }
        return SelfTestResult(true, null, reports, raw, System.currentTimeMillis())
    }

    private fun parseBackend(json: JSONObject, nameHint: String): BackendReport {
        val name = json.optString("backend").ifEmpty {
            json.optString("name").ifEmpty { nameHint }
        }
        val checks = mutableListOf<CheckItem>()
        json.optJSONArray("checks")?.let { array ->
            for (i in 0 until array.length()) {
                val check = array.optJSONObject(i) ?: continue
                checks.add(
                    CheckItem(
                        name = check.optString("name", "check"),
                        status = check.optString("status", "INFO"),
                        detail = check.optString("detail", check.optString("message", "")),
                    )
                )
            }
        }
        return BackendReport(
            backend = name,
            available = json.optBoolean("available", true),
            verdict = json.optString("verdict", "UNKNOWN"),
            renderer = json.optString("renderer", ""),
            checks = checks,
        )
    }
}