package dev.smithy.feature.files

import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 包内文本条目的编辑器。
 *
 * 用 sora-editor 的 `CodeEditor` —— 它是传统 `View`，所以要 `AndroidView` 包一层。
 *
 * **用完必须 `release()`**：那个组件自带后台渲染线程，不释放会一直留着。
 * 所以这里用 `DisposableEffect` 的 `onDispose` 兜住 —— 离开界面（切标签、返回、
 * 甚至因为重组被回收）都会走到，比指望调用方记得调更可靠。
 *
 * 不引 `language-textmate`：它要求 API 33 以下开 core library desugaring，
 * 而这里编辑的是配置与脚本，等宽字体 + 纯文本就够。
 *
 * 内容存在编辑器里而不是 Compose 状态里：sora-editor 自己的文本模型才是权威，
 * 每敲一下键就同步到 `State` 会让整棵 Composable 重组，卡且没必要。
 * 保存时再从编辑器读一次。
 */
@Composable
internal fun TextEditorScreen(
    path: String,
    text: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { EditorHolder() }

    DisposableEffect(Unit) {
        onDispose { holder.editor?.release() }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { holder.editor?.text?.toString()?.let(onSave) }) {
                    Text("保存")
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    setText(text)
                    typefaceText = Typeface.MONOSPACE
                    holder.editor = this
                }
            },
        )
    }
}

/**
 * 装编辑器实例的盒子。
 *
 * 用普通类 + `remember` 而不是 `mutableStateOf<CodeEditor?>`：后者会让编辑器实例
 * 变成一个被监听的状态，赋值那一刻就触发重组 —— 而我们只是想在别处拿到引用，
 * 不需要界面因为「引用有了」而重画。
 */
private class EditorHolder {
    var editor: CodeEditor? = null
}
