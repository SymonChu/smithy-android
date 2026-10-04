package dev.smithy.feature.apk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import dev.smithy.engine.IconPlan
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 按 [IconPlan] 画图。
 *
 * **为什么画图在 UI 层而不在引擎里**：要用 Android 的 `Bitmap`，而 `javax.imageio`
 * 在 Android 上不存在 —— 引擎是纯 JVM、不 import `android.*`。所以引擎负责
 * 「要画哪些图、多大、内容画在哪个范围」（那是包结构的知识），这里只做缩放与居中。
 *
 * 规则（安全区比例、传统图标铺满）由引擎给，**不在两边各写一份** ——
 * 换图标最容易出的问题（变形、被启动器裁掉）就出在尺寸上，规则只能有一处定义。
 */
object IconReplacer {

    /** 把用户选的图画成 [plan] 要求的每一张，key 与 [IconPlan.renders] 对应。 */
    fun render(sourceFile: File, plan: IconPlan): Map<String, ByteArray> {
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalArgumentException("这张图解不开，换一张 PNG 或 JPG 试试")

        // 先居中裁成正方形：图标必须是方的，直接把长方形拉伸会变形
        val square = centerCrop(decoded)
        return try {
            plan.renders.associate { r ->
                r.key to encodeOnCanvas(square, r.canvasSize, r.contentSize)
            }
        } finally {
            if (square !== decoded) square.recycle()
            decoded.recycle()
        }
    }

    /**
     * 把图缩到 contentSize 见方，居中画到 canvasSize 见方的**透明**画布上，输出 PNG。
     *
     * 画布留透明而不是填色：adaptive 的图层带 alpha，涂黑会被当成前景内容显示出来。
     */
    private fun encodeOnCanvas(src: Bitmap, canvasSize: Int, contentSize: Int): ByteArray {
        val canvas = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(src, contentSize, contentSize, true)
        try {
            val offset = (canvasSize - contentSize) / 2f
            Canvas(canvas).drawBitmap(scaled, offset, offset, null)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
        return ByteArrayOutputStream().use { out ->
            canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
            canvas.recycle()
            out.toByteArray()
        }
    }

    /** 居中裁成正方形；本来就是方的就原样返回（不浪费一次拷贝）。 */
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }
}
