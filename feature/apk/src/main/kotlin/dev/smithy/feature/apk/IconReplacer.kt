package dev.smithy.feature.apk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.smithy.engine.ApkEntry
import dev.smithy.engine.ApkProject
import dev.smithy.engine.PatchRecord
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 换图标。
 *
 * ── 为什么缩放这一步不在引擎里 ──
 *
 * 图片解码与缩放要用 Android 的 `Bitmap`（`javax.imageio` 在 Android 上不存在），
 * 而引擎是纯 JVM、不 import `android.*`。所以分工是：引擎只管「往某个条目写字节」，
 * 解码与缩放留在 UI 层。
 *
 * ── 目前只处理传统 PNG 图标 ──
 *
 * 也就是 `res/mipmap-<密度>/ic_launcher.png` 这一套（M2 的验收对象是「不含 adaptive icon 的老包」）。
 * 含 adaptive icon 的包（`mipmap-anydpi-v26/ic_launcher.xml` + 前景/背景层）不在本轮范围：
 * 前景层有安全区要求（108dp 画布里只有中间 72dp 是可见的，系统还会按视差裁切），
 * 直接把整张图塞进前景会在启动器上被裁掉一圈 —— 那正是验收要避免的「变形」。
 * 要做对得单独设计前景/背景的生成规则，留到下一步。
 */
object IconReplacer {

    /** 密度 → 启动图标的标准边长（px）。系统按这些尺寸取用。 */
    private val DENSITY_SIZES = linkedMapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192,
    )

    /**
     * 从包条目里找出传统图标条目：密度 → 条目路径。
     *
     * 只看 `res/mipmap-*dpi*` 下的 PNG/JPG，`.xml` 一律跳过（那是 adaptive icon 的声明文件）。
     */
    fun findIconEntries(entries: List<ApkEntry>, iconBase: String = "ic_launcher"): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (e in entries) {
            val path = e.path
            if (e.isDirectory) continue
            if (!path.startsWith("res/")) continue
            if (!path.contains("/$iconBase.")) continue
            if (path.endsWith(".xml")) continue

            val density = DENSITY_SIZES.keys.firstOrNull { path.contains("-$it") } ?: continue
            out[density] = path
        }
        return out
    }

    /**
     * 解码用户选的图，按每个密度缩放成 PNG 写回。
     *
     * **先居中裁剪成正方形再缩放**：图标必须是方的，直接把长方形拉伸成方的就是验收里
     * 要避免的「变形」。
     */
    suspend fun replace(
        project: ApkProject,
        sourceFile: File,
        entries: Map<String, String>,
    ): List<PatchRecord> {
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalArgumentException("这张图解不开，换一张 PNG 或 JPG 试试")

        val square = centerCrop(decoded)
        val out = mutableListOf<PatchRecord>()
        try {
            for ((density, path) in entries) {
                val size = DENSITY_SIZES[density] ?: continue
                val scaled = Bitmap.createScaledBitmap(square, size, size, true)
                try {
                    val bytes = ByteArrayOutputStream().use { bos ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        bos.toByteArray()
                    }
                    out += project.writeEntry(path, bytes.inputStream())
                } finally {
                    if (scaled !== square) scaled.recycle()
                }
            }
        } finally {
            if (square !== decoded) square.recycle()
            decoded.recycle()
        }
        return out
    }

    /** 居中裁成正方形；本来就是方的就原样返回。 */
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }
}
