package com.example.memegram

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import retrofit2.HttpException

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets(R.id.main)

        lifecycleScope.launch {
            if (tryAutoLogin()) return@launch
            setupRegisterForm()
        }
    }

    private suspend fun tryAutoLogin(): Boolean {
        if (!KeyManager.hasKeyPair(this) || !SessionManager.isLoggedIn(this)) return false
        if (!SessionManager.isTokenExpired(this)) { goToChats(); return true }

        return try {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val initResp = RetrofitClient.api.loginInit(LoginInitRequest(deviceId))
            val challenge = initResp.challenge
            val sigBase64 = Base64.encodeToString(
                KeyManager.signChallenge(this, challenge), Base64.NO_WRAP
            )
            val result = RetrofitClient.api.loginComplete(
                LoginCompleteRequest(device_id = deviceId, challenge = challenge, signature = sigBase64)
            )
            SessionManager.save(this, result)
            goToChats()
            true
        } catch (e: HttpException) {
            SessionManager.clear(this)
            false
        } catch (e: Exception) {
            goToChats()
            true
        }
    }

    private fun setupRegisterForm() {
        val linkToLogIn: TextView = findViewById(R.id.textView3)
        val userNick:    EditText = findViewById(R.id.editTextText)
        val userInvite:  EditText = findViewById(R.id.editTextText2)
        val regButton:   Button   = findViewById(R.id.button)

        linkToLogIn.setOnClickListener {
            startActivity(Intent(this, AuthActivity::class.java))
        }

        SpannableString("MemeGram").apply {
            setSpan(ForegroundColorSpan(Color.RED),   0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), 4, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            findViewById<TextView>(R.id.textView2).text = this
        }

        regButton.setOnClickListener {
            val nick   = userNick.text.toString().trim()
            val invite = userInvite.text.toString().trim()

            if (nick.isEmpty()) {
                Toast.makeText(this, "Никнейм должен содержать хоть что-то", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (invite.isEmpty()) {
                Toast.makeText(this, "Введи инвайт-код", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            regButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    val deviceId  = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    val pubKeyB64 = KeyManager.getPublicKeyBase64(this@MainActivity)
                    val credData  = Base64.encodeToString(deviceId.toByteArray(), Base64.NO_WRAP)

                    val result = RetrofitClient.api.register(
                        RegisterRequest(
                            username         = nick,
                            invite_code      = invite,
                            device_id        = deviceId,
                            device_name      = "${Build.MANUFACTURER} ${Build.MODEL}",
                            identity_key_pub = pubKeyB64,
                            init_key_pub     = pubKeyB64,
                            credential_data  = credData
                        )
                    )
                    SessionManager.save(this@MainActivity, result)
                    userNick.text.clear()
                    userInvite.text.clear()
                    goToChats()

                } catch (e: HttpException) {
                    val msg = when (e.code()) {
                        422  -> "Неверный или просроченный инвайт-код"
                        409  -> "Устройство уже зарегистрировано"
                        503  -> "Сервер недоступен, попробуй позже"
                        else -> "Ошибка ${e.code()}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.javaClass.simpleName + ": " + e.message, Toast.LENGTH_LONG).show()
                } finally {
                    regButton.isEnabled = true
                }
            }
        }
    }

    private fun goToChats() {
        startActivity(Intent(this, ChatsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
