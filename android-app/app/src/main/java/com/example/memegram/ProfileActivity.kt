package com.example.memegram

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri
import androidx.core.content.edit

class ProfileActivity : BaseActivity() {

    private lateinit var ivAvatar: ImageView
    private lateinit var etNickname: EditText
    private lateinit var etBio: EditText
    private var selectedImageUri: Uri? = null
    private var currentUserId: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            ivAvatar.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        ThemeHelper.applyStatusBarColor(this)
        applyWindowInsets(R.id.mainLayout)

        ivAvatar = findViewById(R.id.ivProfileAvatar)
        etNickname = findViewById(R.id.etNickname)
        etBio = findViewById(R.id.etBio)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        ivAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        loadUserData()

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            saveUserData()
        }
    }

    private fun loadUserData() {
        val prefs = getSharedPreferences("profile", Context.MODE_PRIVATE)
        etNickname.setText(prefs.getString("username", ""))
        etBio.setText(prefs.getString("bio", ""))
    }

    private fun saveUserData() {
        val newNick = etNickname.text.toString().trim()
        val newBio = etBio.text.toString().trim()
        if (newNick.isEmpty()) {
            Toast.makeText(this, "Nickname cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("profile", Context.MODE_PRIVATE)
        prefs.edit {
            putString("username", newNick)
                .putString("bio", newBio)
        }
        Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun copyImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = "avatar_${System.currentTimeMillis()}.jpg"
            val file = File(filesDir, fileName)
            val outputStream = FileOutputStream(file)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
