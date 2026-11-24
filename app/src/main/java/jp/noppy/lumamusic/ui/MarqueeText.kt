package jp.noppy.lumamusic.ui

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

private val TitleLineBreaks = Regex("[\r\n\u0085\u2028\u2029]+")

/** The title keeps its full text for accessibility and never moves neighboring controls. */
@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val line = remember(text) { text.replace(TitleLineBreaks, " ") }
    val marquee = if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        Modifier.basicMarquee(
            iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 1500,
            spacing = MarqueeSpacing(40.dp), velocity = 28.dp,
        )
    } else Modifier
    key(line, style) {
        Text(
            line, Modifier.clipToBounds().then(marquee), style = style,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, onTextLayout = onTextLayout,
        )
    }
}
