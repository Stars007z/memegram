package com.example.memegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.memegram.localization.AppStrings
import com.example.memegram.utils.sdp
import com.example.memegram.utils.ssp

@Composable
fun ModelDownloadRow(
    state: ModelDownloadState,
    modelSize: Long,
    accent: Color,
    strings: AppStrings,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    title: String = strings.translationModel,
    description: String = strings.translationModelDescription,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.sdp, vertical = 12.sdp)
    ) {
        Text(
            text = title,
            fontSize = 14.ssp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = description,
            fontSize = 12.ssp,
            color = Color.Gray,
        )

        Spacer(Modifier.height(8.sdp))

        when (state) {
            ModelDownloadState.Idle -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = strings.modelNotDownloaded,
                        fontSize = 12.ssp,
                        color = Color.Gray,
                    )
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                    ) { Text(strings.downloadModel) }
                }
            }

            is ModelDownloadState.Downloading -> {
                val fraction = state.fraction.coerceIn(0f, 1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = strings.downloadingModel,
                        fontSize = 12.ssp,
                    )
                    val pct = if (state.totalBytes > 0) "${(fraction * 100).toInt()}%"
                              else formatModelBytes(state.bytesDownloaded)
                    Text(
                        text = pct,
                        fontSize = 12.ssp,
                        color = Color.Gray,
                    )
                }
                Spacer(Modifier.height(6.sdp))
                if (state.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                }
                Spacer(Modifier.height(6.sdp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onCancel) { Text(strings.cancelDownload) }
                }
            }

            ModelDownloadState.Ready -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.modelReady,
                            fontSize = 12.ssp,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                        if (modelSize > 0) {
                            Text(
                                text = formatModelBytes(modelSize),
                                fontSize = 11.ssp,
                                color = Color.Gray,
                            )
                        }
                    }
                    OutlinedButton(onClick = onDelete) {
                        Text(strings.deleteModel)
                    }
                }
            }

            is ModelDownloadState.Failed -> {
                Text(
                    text = "${strings.modelDownloadFailed}: ${state.message}",
                    fontSize = 12.ssp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.sdp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                    ) { Text(strings.downloadModel) }
                }
            }
        }
    }
}

private fun formatModelBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).toInt() / 100.0} GB"
}
