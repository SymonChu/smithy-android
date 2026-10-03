package dev.smithy.feature.apk

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smithy.engine.ApkMeta
import dev.smithy.engine.ApkProject
import dev.smithy.engine.ApkProjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 报告页的四种状态。失败态必须带 hint —— 依据 docs/04 的约定，错误要给出下一步。 */
sealed interface ReportState {
    data object Empty : ReportState
    data class Loading(val stage: String) : ReportState
    data class Ready(
        val projectId: String,
        val sourceName: String,
        val meta: ApkMeta,
        val entryCount: Int,
    ) : ReportState
    data class Failed(val message: String, val hint: String?) : ReportState
}

class ApkReportViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ReportState>(ReportState.Empty)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var opened: ApkProject? = null

    /**
     * 通过 SAF 拿到的 uri 打开 APK。
     *
     * 先落到 App 私有缓存再解析：content:// 的流只能顺序读一次，
     * 而解析要反复读（列条目 + 读 dex 头 + 校验签名），必须落成文件。
     */
    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.value = ReportState.Loading("读取文件…")
            try {
                val sourceName = queryDisplayName(uri)
                val local = copyToCache(uri, sourceName)

                _state.value = ReportState.Loading("解析清单 / 签名 / dex…")
                val project = ApkProjects.open(local)
                val entries = project.list().size

                closeCurrent()
                opened = project
                _state.value = ReportState.Ready(
                    projectId = project.id,
                    sourceName = sourceName,
                    meta = project.meta,
                    entryCount = entries,
                )
            } catch (t: Throwable) {
                _state.value = ReportState.Failed(
                    message = t.message ?: t::class.java.simpleName,
                    hint = hintFor(t),
                )
            }
        }
    }

    fun close() {
        viewModelScope.launch {
            closeCurrent()
            _state.value = ReportState.Empty
        }
    }

    override fun onCleared() {
        opened?.close(keepArtifacts = false)
        opened = null
        super.onCleared()
    }

    private suspend fun closeCurrent() {
        opened?.let { runCatching { it.close(keepArtifacts = false) } }
        opened = null
    }

    private suspend fun queryDisplayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "input.apk"
    }

    private suspend fun copyToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "opened").apply { mkdirs() }
        val out = File(dir, name)
        app.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalStateException("无法读取该文件（可能是云盘占位文件或权限不足）")
        out
    }

    /** 把异常翻译成"下一步该干什么"，而不是把堆栈丢给用户。 */
    private fun hintFor(t: Throwable): String? = when {
        t is NoSuchElementException -> "该 APK 缺少 AndroidManifest.xml，可能不是完整的安装包"
        t.message?.contains("不是文件") == true -> "请选择设备上已下载完成的 .apk 文件，而不是云盘在线文件"
        t.message?.contains("zip", ignoreCase = true) == true -> "文件不是合法的 zip/APK，建议确认扩展名与完整性"
        t is OutOfMemoryError -> "解析这个包时内存不足，先试小一点的包"
        else -> "若该包经过加固，资源表可能不是标准格式；可先只查看基本信息（M1 起会提供 rootfs 兜底路径）"
    }
}
