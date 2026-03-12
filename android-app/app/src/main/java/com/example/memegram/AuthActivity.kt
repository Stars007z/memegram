package com.example.memegram

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class AuthActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        applyWindowInsets(R.id.main)

        val linkToReg: TextView = findViewById(R.id.textView3)
        linkToReg.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        val userNick: EditText = findViewById(R.id.editTextText)
        val userInvite: EditText = findViewById(R.id.editTextText2)
        val regButton: Button = findViewById(R.id.button)

        regButton.setOnClickListener {
            val nick = userNick.text.toString().trim()
            // val invite = userInvite.text.toString().trim()

            if (nick == "") {
                Toast.makeText(this, "Никнейм должен содержать хоть что-то", Toast.LENGTH_SHORT).show()
            } else {
                DBhelper.getInstance(context = this).addUser(username = nick)
                userNick.text.clear()
                userInvite.text.clear()
            }
        }

        val textView2 = findViewById<TextView>(R.id.textView2)
        val text = "MemeGram"
        val spannable = SpannableString(text)
        spannable.setSpan(
            ForegroundColorSpan(Color.RED),
            0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(Color.WHITE),
            4, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        textView2.text = spannable
    }
}
