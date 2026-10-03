package dev.smithy.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 重活进程（:worker）的宿主。
 *
 * 为什么单独开进程：解包大包、汇编 smali、回编资源是内存密集型操作，
 * 与 UI 同进程会互相拖累（大包直接 OOM 把界面一起带走）。
 * 独立进程 + 独立堆上限后，最坏情况只损失这次操作，UI 活着并能给出明确报错。
 *
 * M0 阶段只有占位；M1 起通过 Binder/AIDL 暴露 rebuild / sign / install。
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
