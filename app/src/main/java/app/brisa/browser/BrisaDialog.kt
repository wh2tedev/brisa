package app.brisa.browser

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's dialog: an [AlertDialog] with a narrower width, smaller corners and a smaller title and body. */
@Composable
fun BrisaDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val titleStyle = MaterialTheme.typography.titleLarge
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val styledTitle: (@Composable () -> Unit)? =
        if (title != null) {
            { ProvideTextStyle(titleStyle) { title() } }
        } else {
            null
        }
    val styledText: (@Composable () -> Unit)? =
        if (text != null) {
            { ProvideTextStyle(bodyStyle) { text() } }
        } else {
            null
        }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = styledTitle,
        text = styledText,
        modifier = Modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(24.dp),
    )
}
