package app.zemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：标题 / 列表 / 引用 / 分隔线 / 代码块 / 行内代码 / 粗斜体 /
 * 删除线 / 链接。纯 Compose 实现，专为 AI 回复流式增长设计（逐行解析，无全局状态）。
 *
 * 性能约定（改这个文件前请先读）：
 * 1. 所有正则必须是顶层 `val`。旧实现把它们写在 `parseBlocks` 的逐行循环里，
 *    每行构造 5 个 Regex，`buildAnnotatedString` 每次调用又构造 5 个；而
 *    `Pattern.compile` 没有缓存 —— 一条 200 行的回复等于上千次正则编译，
 *    这是流式输出时渲染慢的主要来源之一。
 * 2. 行内样式解析（[inline]）必须跟 blocks 一起 `remember`，否则父级每次重组
 *    都会把所有可见段落重新解析一遍。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    // 与 blocks 同生命周期：Code / Rule 不需要行内解析，对应槽位为 null
    val inlines = remember(blocks, baseColor) {
        blocks.map { block -> block.inlineSrc?.let { inline(it, baseColor) } }
    }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Code -> Surface(
                    color = codeBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = baseColor,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                is MdBlock.Heading -> Text(
                    inlines[index]!!,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = baseColor,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        inlines[index]!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = baseColor.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                is MdBlock.ListItem -> Text(
                    inlines[index]!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = baseColor,
                    modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
                )
                is MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(baseColor.copy(alpha = 0.2f)),
                )
                is MdBlock.Paragraph -> Text(
                    inlines[index]!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = baseColor,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    /** 需要做行内样式解析的原文；Code / Rule 为 null */
    val inlineSrc: String?

    data class Paragraph(val text: String) : MdBlock {
        override val inlineSrc get() = text
    }

    data class Heading(val text: String, val level: Int) : MdBlock {
        override val inlineSrc get() = text
    }

    data class Code(val code: String) : MdBlock {
        override val inlineSrc get() = null
    }

    data class Quote(val text: String) : MdBlock {
        override val inlineSrc get() = text
    }

    data class ListItem(val text: String, val bullet: String) : MdBlock {
        override val inlineSrc get() = "$bullet $text"
    }

    data object Rule : MdBlock {
        override val inlineSrc get() = null
    }
}

// ────────────────────────── 预编译正则（见文件头性能约定 1） ──────────────────────────
private val RE_HEADING = Regex("^#{1,6}\\s+")
private val RE_RULE = Regex("([-*_])\\1{2,}")
private val RE_BULLET = Regex("^\\s*[-*•]\\s+")
private val RE_ORDERED = Regex("^\\s*\\d+[.)]\\s+")
private val RE_ORDERED_FULL = Regex("^\\s*(\\d+[.)])\\s+(.*)")
private val RE_INLINE_CODE = Regex("`([^`]+)`")
private val RE_LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val RE_BOLD_ITALIC = Regex("\\*\\*\\*([^*]+)\\*\\*\\*|___([^_]+)___")
private val RE_BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
private val RE_ITALIC = Regex("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)|(?<!_)_([^_\\s][^_]*)_(?!_)")
private val RE_STRIKE = Regex("~~([^~]+)~~")

// 与主题无关的样式常量，避免每次解析重新分配
private val STYLE_BOLD = SpanStyle(fontWeight = FontWeight.SemiBold)
private val STYLE_ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STYLE_STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val STYLE_LINK = SpanStyle(color = Color(0xFF5B9BFF), textDecoration = TextDecoration.Underline)

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) {
            out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            line.trim().startsWith("|") && i + 1 < lines.size && lines[i + 1].contains("---") -> {
                // 简易表格 → 逐行渲染为列表
                flush()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                    if (!lines[i].contains("---")) out += MdBlock.ListItem(cells.joinToString("  "), "•")
                    i++
                }
            }
            RE_HEADING.containsMatchIn(line) -> {
                flush()
                val level = line.indexOfFirst { it != '#' }
                out += MdBlock.Heading(line.substring(level).trim(), level.coerceAtMost(3))
            }
            RE_RULE.matches(line.trim()) -> { flush(); out += MdBlock.Rule }
            line.trimStart().startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    q.appendLine(lines[i].trimStart().removePrefix(">").trim()); i++
                }
                out += MdBlock.Quote(q.toString().trim())
                continue
            }
            RE_BULLET.containsMatchIn(line) -> {
                flush()
                out += MdBlock.ListItem(line.trim().substringAfterFirst(" "), "•")
            }
            RE_ORDERED.containsMatchIn(line) -> {
                flush()
                val m = RE_ORDERED_FULL.find(line)
                if (m != null) out += MdBlock.ListItem(m.groupValues[2], m.groupValues[1] + ".")
                else { para.appendLine(line) }
            }
            line.isBlank() -> flush()
            else -> para.appendLine(line)
        }
        i++
    }
    flush()
    return out
}

private fun String.substringAfterFirst(delim: String): String =
    indexOf(delim).takeIf { it >= 0 }?.let { substring(it + delim.length).trim() } ?: this

/** 行内 markdown → AnnotatedString（粗体 / 斜体 / 行内代码 / 删除线 / 链接） */
private fun inline(src: String, color: Color): AnnotatedString = buildAnnotatedString(src, color)

private fun buildAnnotatedString(src: String, color: Color): AnnotatedString {
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = color.copy(alpha = 0.10f),
        fontSize = 13.sp,
    )

    data class Tok(val start: Int, val end: Int, val style: SpanStyle, val text: String, val isCode: Boolean = false)

    val tokens = mutableListOf<Tok>()
    RE_INLINE_CODE.findAll(src).forEach { tokens += Tok(it.range.first, it.range.last + 1, codeStyle, it.groupValues[1], isCode = true) }
    // 链接 [t](u)
    RE_LINK.findAll(src).forEach { m ->
        tokens += Tok(m.range.first, m.range.last + 1, STYLE_LINK, m.groupValues[1])
    }
    // 粗斜体 / 粗体 / 斜体 / 删除线
    RE_BOLD_ITALIC.findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, STYLE_BOLD.merge(STYLE_ITALIC), plain)
    }
    RE_BOLD.findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, STYLE_BOLD, plain)
    }
    RE_ITALIC.findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, STYLE_ITALIC, plain)
    }
    RE_STRIKE.findAll(src).forEach { m ->
        tokens += Tok(m.range.first, m.range.last + 1, STYLE_STRIKE, m.groupValues[1])
    }

    return AnnotatedString.Builder().apply {
        var pos = 0
        val sorted = tokens.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        var i = 0
        while (i < sorted.size) {
            val t = sorted[i]
            if (t.start < pos) { i++; continue }
            append(src.substring(pos, t.start))
            if (t.isCode) {
                append(src.substring(t.start + 1, t.end - 1))
            } else {
                pushStyle(t.style)
                append(t.text)
                pop()
            }
            pos = t.end
            while (i + 1 < sorted.size && sorted[i + 1].start < pos) i++
            i++
        }
        append(src.substring(pos))
    }.toAnnotatedString()
}
