package dev.smithy.design

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 把文案里的 `**这样**` 渲染成加粗。
 *
 * ## 为什么要有它
 *
 * 引擎那边给出的结论是**纯字符串**（`ApkHealth`、验证结论、工具返回的说明），它们同时被
 * 三个地方用：界面文字、给模型看的上下文、导出的 Markdown 报告。Markdown 里 `**…**` 是加粗，
 * 而 Compose 的 `Text` 不认识 Markdown —— 不处理的话屏幕上会原样出现两个星号。
 *
 * 三选一（去掉星号 / 让引擎只产出纯文本 / 在渲染层解析）里选了第三种：
 * 那些结论里**加粗是有信息量的**（「**改包名**会让注入失效」的重点在「改包名」），
 * 去掉它会变弱；让引擎只产纯文本则要在每处调用点手动强调，一定会漏。
 *
 * 解析规则故意极简：只认成对的 `**`，不处理嵌套、不处理其它 Markdown 语法。
 * 一份比它更聪明的实现，只会在「某处文案少了一个星号」时把整段文字染成加粗，
 * 而那种错很难看出来。
 */
fun smithyEmphasis(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val open = text.indexOf("**", index)
        if (open < 0) {
            append(text.substring(index))
            break
        }
        val close = text.indexOf("**", open + 2)
        if (close < 0) {
            // 只有一个孤立的 `**` —— 当普通文字处理，别吞掉后面的内容
            append(text.substring(index))
            break
        }
        append(text.substring(index, open))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(text.substring(open + 2, close))
        }
        index = close + 2
    }
}

/** 去掉强调标记（给不能渲染富文本的地方用，例如日志、单行摘要）。 */
fun String.withoutEmphasis(): String = replace("**", "")
