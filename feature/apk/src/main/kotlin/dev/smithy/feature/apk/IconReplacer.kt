package dev.smithy.feature.apk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import dev.smithy.engine.ApkProject
import dev.smithy.engine.IconTargets
import dev.smithy.engine.PatchRecord
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 换图标。
 *
 * ── 分工 ──
 *
 * **哪些条目是图标**由引擎解析（`ApkProject.iconTargets()`，顺着清单的 android:icon 引用走）。
 * 这里只管**画**：解码、按密度缩放、写回。
 *
 * 之前这里自己按 `ic_launcher` 这个名字猜，结果是「图标叫别的名字就换不了」——
 * 而 `ic_launcher` 只是 Android Studio 模板的默认名。名字是开发者随便起的，
 * 唯一可靠的依据是清单里的引用。
 *
 * ── 为什么缩放不在引擎里 ──
 *
 * 图片解码缩放要用 Android 的 `Bitmap`（`javax.imageio` 在 Android 上不存在），
 * 而引擎是纯 JVM、不 import `android.*`。所以引擎只负责「往某条目写字节」。
 */
object IconReplacer {

    /** 传统图标（mipmap-<密度>）的边长，单位 px。 */
    private val DENSITY_SIZES = linkedMapOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192,
        // 这两个不是"某个密度"，而是"不按密度缩放"的目录。出现时按 xhdpi 处理，
        // 至少保证替换后不是空白图
        "nodpi" to 96,
        "anydpi" to 96,
    )

    /** adaptive icon 的画布：108dp 见方，按密度换算成 px。 */
    private val ADAPTIVE_CANVAS = linkedMapOf(
        "mdpi" to 108,
        "hdpi" to 162,
        "xhdpi" to 216,
        "xxhdpi" to 324,
        "xxxhdpi" to 432,
    )

    /**
     * 安全区比例：72/108。
     *
     * adaptive icon 的硬约束 —— 108dp 画布里只有中间 72dp 保证可见，
     * 启动器还会按图标做视差与裁切。前景内容超出这个范围就会被切掉，
     * 那正是「变形」的主要来源。背景层相反：它本来就该铺满整块画布。
     */
    private const val SAFE_RATIO = 72f / 108f

    /**
     * 把用户选的图按 [targets] 里的每个图层写回。
     *
     * 三层都换（前景 / 背景 / 传统）而不是只挑一层：很多包同时带 adaptive 和传统 PNG
     * （给老系统用的）。只换其中一层的话，在不同版本 Android 上会看到两套图标。
     */
    suspend fun replace(
        project: ApkProject,
        sourceFile: File,
        targets: IconTargets,
    ): List<PatchRecord> {
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalArgumentException("这张图解不开，换一张 PNG 或 JPG 试试")

        // 图标必须是方的：居中裁一次，后面所有密度都从这张方的缩放
        val square = centerCrop(decoded)
        val out = mutableListOf<PatchRecord>()
        try {
            // 前景：内容缩进安全区再居中（透明画布，留白必须真空）
            for ((density, path) in targets.foreground) {
                val canvas = ADAPTIVE_CANVAS[density] ?: continue
                val inner = (canvas * SAFE_RATIO).toInt()
                out += project.writeEntry(path, renderOnCanvas(square, canvas, inner).inputStream())
            }
            // 背景：铺满整块画布
            for ((density, path) in targets.background) {
                val canvas = ADAPTIVE_CANVAS[density] ?: continue
                out += project.writeEntry(path, renderOnCanvas(square, canvas, canvas).inputStream())
            }
            // 传统图标：直接按密度缩放
            for ((density, path) in targets.legacy) {
                val size = DENSITY_SIZES[density] ?: continue
                out += project.writeEntry(path, renderPlain(square, size).inputStream())
            }
        } finally {
            if (square !== decoded) square.recycle()
            decoded.recycle()
        }
        return out
    }

    /** 居中裁成正方形；本来就是方的就原样返回（省一次拷贝）。 */
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    /**
     * 缩放到 [inner] 见方，居中画到 [canvas] 见方的**透明**画布上。
     *
     * 用透明画布而不是直接缩放：adaptive icon 的图层是带 alpha 的，
     * 前景的留白必须真的透明，涂成黑色会被当成前景内容显示出来。
     */
    private fun renderOnCanvas(src: Bitmap, canvas: Int, inner: Int): ByteArray {
        val target = Bitmap.createBitmap(canvas, canvas, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(src, inner, inner, true)
        try {
            val offset = (canvas - inner) / 2f
            Canvas(target).drawBitmap(scaled, offset, offset, null)
            return compress(target)
        } finally {
            if (scaled !== src) scaled.recycle()
            target.recycle()
        }
    }

    private fun renderPlain(src: Bitmap, size: Int): ByteArray {
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        try {
            return compress(scaled)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    private fun compress(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    }
}
