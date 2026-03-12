package com.example.memegram

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.content.Intent

class PrivacyActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        ThemeHelper.applyStatusBarColor(this)
        applyWindowInsets(R.id.mainLayout)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<android.view.View>(R.id.btnBlackList).setOnClickListener {
            startActivity(Intent(this, BlackListActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnAutoDeleteMessages).setOnClickListener {
            showAutoDeleteDialog("Auto-delete messages after:") { selectedOption ->
                Toast.makeText(this, "Messages will auto-delete in $selectedOption", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.view.View>(R.id.btnAutoDeleteAccount).setOnClickListener {
            showAutoDeleteDialog("If away for:") { selectedOption ->
                Toast.makeText(this, "Account will auto-delete after $selectedOption inactivity", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.view.View>(R.id.btnDeleteAccount).setOnClickListener {
            showDeleteAccountConfirmation()
        }
    }

    private fun showAutoDeleteDialog(title: String, onSelected: (String) -> Unit) {
        val options = arrayOf("1 month", "3 months", "6 months", "1 year")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                onSelected(options[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAccountConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account?")
            .setMessage("This action cannot be undone. All your data and messages will be permanently lost.")
            .setPositiveButton("Delete") { _, _ ->
                Toast.makeText(this, "Account Deleted!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
