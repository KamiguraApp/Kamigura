package li.mof.kamigura.reader.internal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookmarkButton(page: Int, state: ReaderBookmarkState, onClick: () -> Unit) {
    val saved = page in state.pages
    val description = when {
        state.loading -> "Checking bookmarks"
        state.savingPage != null -> "Saving bookmark for page ${state.savingPage + 1}"
        !state.loaded -> "Check bookmark status for page ${page + 1}"
        saved -> "Remove bookmark from page ${page + 1}"
        else -> "Bookmark page ${page + 1}"
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState()
    ) {
        Column(
            modifier = Modifier.width(48.dp).heightIn(min = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = onClick,
                enabled = !state.busy,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = description
                    stateDescription = when {
                        !state.loaded -> "Bookmark status unavailable"
                        saved -> "Bookmarked"
                        else -> "Not bookmarked"
                    }
                }
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = when {
                            !state.loaded -> Icons.Filled.Refresh
                            saved -> Icons.Filled.Bookmark
                            else -> Icons.Outlined.BookmarkBorder
                        },
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
            Text(
                text = "p. ${page + 1}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
