package com.example.memegram

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri

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
        val db = DBhelper.getInstance(this)
        val cursor = db.readableDatabase.rawQuery("SELECT * FROM users ORDER BY created_at DESC LIMIT 1", null)

        if (cursor.moveToFirst()) {
            currentUserId = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val username = cursor.getString(cursor.getColumnIndexOrThrow("username"))
            val bio = cursor.getString(cursor.getColumnIndexOrThrow("bio"))
            val avatarPath = cursor.getString(cursor.getColumnIndexOrThrow("avatar_media_id"))

            etNickname.setText(username)
            etBio.setText(bio)

            if (!avatarPath.isNullOrEmpty()) {
                try {
                    val file = File(avatarPath)
                    if (file.exists()) {
                        ivAvatar.setImageURI(Uri.fromFile(file))
                    } else {
                        ivAvatar.setImageURI(avatarPath.toUri())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        cursor.close()
    }

    private fun saveUserData() {
        val newNick = etNickname.text.toString().trim()
        val newBio = etBio.text.toString().trim()

        if (newNick.isEmpty()) {
            Toast.makeText(this, "Nickname cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentUserId != null) {
            var savedImagePath: String? = null

            if (selectedImageUri != null) {
                savedImagePath = copyImageToInternalStorage(selectedImageUri!!)
            } else {
                //Skibob
            }

            val db = DBhelper.getInstance(this)

            var finalAvatarPath: String? = savedImagePath
            if (finalAvatarPath == null) {
                val cursor = db.readableDatabase.rawQuery("SELECT avatar_media_id FROM users WHERE id = ?", arrayOf(currentUserId))
                if (cursor.moveToFirst()) {
                    finalAvatarPath = cursor.getString(0)
                }
                cursor.close()
            }

            db.updateUserProfile(currentUserId!!, newNick, newBio, finalAvatarPath)
            Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Error: User not found", Toast.LENGTH_SHORT).show()
        }
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
