package com.example.memegram

import android.content.Intent
import android.graphics.Color
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

class AuthActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        applyWindowInsets(R.id.main)

        val linkToReg:   TextView = findViewById(R.id.textView3)
        val userNick:    EditText = findViewById(R.id.editTextText)
        val userInvite:  EditText = findViewById(R.id.editTextText2)
        val loginButton: Button   = findViewById(R.id.button)

        userNick.visibility   = android.view.View.GONE
        userInvite.visibility = android.view.View.GONE
        loginButton.text      = "Войти"

        linkToReg.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        SpannableString("MemeGram").apply {
            setSpan(ForegroundColorSpan(Color.RED),   0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), 4, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            findViewById<TextView>(R.id.textView2).text = this
        }

        loginButton.setOnClickListener {
            if (!KeyManager.hasKeyPair(this)) {
                Toast.makeText(this, "Устройство не зарегистрировано", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    val deviceId  = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

                    val initResp  = RetrofitClient.api.loginInit(LoginInitRequest(deviceId))
                    val challenge = initResp.challenge
                    val sigBase64 = Base64.encodeToString(
                        KeyManager.signChallenge(this@AuthActivity, challenge), Base64.NO_WRAP
                    )

                    val result = RetrofitClient.api.loginComplete(
                        LoginCompleteRequest(
                            device_id = deviceId,
                            challenge = challenge,
                            signature = sigBase64
                        )
                    )
                    SessionManager.save(this@AuthActivity, result)
                    startActivity(
                        Intent(this@AuthActivity, ChatsActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()

                } catch (e: HttpException) {
                    val msg = when (e.code()) {
                        401  -> "Неверная подпись — ключи не совпадают"
                        403  -> "Устройство заблокировано"
                        404  -> "Устройство не найдено на сервере"
                        else -> "Ошибка входа ${e.code()}"
                    }
                    Toast.makeText(this@AuthActivity, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@AuthActivity, "Нет соединения с сервером", Toast.LENGTH_SHORT).show()
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }
    }
}
