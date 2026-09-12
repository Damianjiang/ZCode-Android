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
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
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
                    inline(block.text, baseColor),
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
                        inline(block.text, baseColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = baseColor.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                is MdBlock.ListItem -> Text(
                    inline("${block.bullet} ${block.text}", baseColor),
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
                    inline(block.text, baseColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = baseColor,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val text: String, val level: Int) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val text: String, val bullet: String) : MdBlock
    data object Rule : MdBlock
}

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
            Regex("^#{1,6}\\s+").containsMatchIn(line) -> {
                flush()
                val level = line.indexOfFirst { it != '#' }
                out += MdBlock.Heading(line.substring(level).trim(), level.coerceAtMost(3))
            }
            line.trim().matches(Regex("([-*_])\\1{2,}")) -> { flush(); out += MdBlock.Rule }
            line.trimStart().startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    q.appendLine(lines[i].trimStart().removePrefix(">").trim()); i++
                }
                out += MdBlock.Quote(q.toString().trim())
                continue
            }
            Regex("^\\s*[-*•]\\s+").containsMatchIn(line) -> {
                flush()
                out += MdBlock.ListItem(line.trim().substringAfterFirst(" "), "•")
            }
            Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
                flush()
                val m = Regex("^\\s*(\\d+[.)])\\s+(.*)").find(line)
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
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = color.copy(alpha = 0.10f),
        fontSize = 13.sp,
    )
    val link = SpanStyle(color = Color(0xFF5B9BFF), textDecoration = TextDecoration.Underline)

    data class Tok(val start: Int, val end: Int, val style: SpanStyle, val text: String, val isCode: Boolean = false)

    val tokens = mutableListOf<Tok>()
    Regex("`([^`]+)`").findAll(src).forEach { tokens += Tok(it.range.first, it.range.last + 1, codeStyle, it.groupValues[1], isCode = true) }
    // 链接 [t](u)
    Regex("\\[([^\\]]+)]\\(([^)]+)\\)").findAll(src).forEach { m ->
        tokens += Tok(m.range.first, m.range.last + 1, link, m.groupValues[1])
    }
    // 粗斜体 / 粗体 / 斜体 / 删除线
    Regex("\\*\\*\\*([^*]+)\\*\\*\\*|___([^_]+)___").findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, bold.merge(italic), plain)
    }
    Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__").findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, bold, plain)
    }
    Regex("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)|(?<!_)_([^_\\s][^_]*)_(?!_)").findAll(src).forEach { m ->
        val plain = m.groupValues[1].ifEmpty { m.groupValues[2] }
        tokens += Tok(m.range.first, m.range.last + 1, italic, plain)
    }
    Regex("~~([^~]+)~~").findAll(src).forEach { m ->
        tokens += Tok(m.range.first, m.range.last + 1, strike, m.groupValues[1])
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
