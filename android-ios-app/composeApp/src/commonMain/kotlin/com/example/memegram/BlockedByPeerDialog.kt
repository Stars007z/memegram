package com.example.memegram

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.memegram.localization.LocalStrings


@Composable
fun BlockedByPeerDialog(contactsViewModel: ContactsViewModel) {
    val blockedErr by contactsViewModel.blockedByPeerError.collectAsState()
    if (blockedErr != null) {
        val s = LocalStrings.current
        AlertDialog(
            onDismissRequest = { contactsViewModel.clearBlockedByPeerError() },
            title = { Text(s.cannotMessageTitle) },
            text = { Text(s.cannotMessageBlockedByPeer) },
            confirmButton = {
                TextButton(onClick = { contactsViewModel.clearBlockedByPeerError() }) {
                    Text(s.ok)
                }
            }
        )
    }
}
