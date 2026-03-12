package com.example.memegram

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import java.io.File
import androidx.core.net.toUri
import android.view.View

class ChatsActivity : BaseActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)

        drawerLayout = findViewById(R.id.drawerLayout)
        applyWindowInsets(R.id.mainLayout)

        val navigationView = findViewById<NavigationView>(R.id.navigationView)

        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawerLayout.open()
        }

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_notifications -> {
                    drawerLayout.close()
                    startActivity(Intent(this, NotificationsActivity::class.java))
                }
                R.id.nav_privacy -> {
                    drawerLayout.close()
                    startActivity(Intent(this, PrivacyActivity::class.java))
                }
                R.id.nav_appearance -> {
                    drawerLayout.close()
                    startActivity(Intent(this, AppearanceActivity::class.java))
                }
                R.id.nav_language -> {
                    drawerLayout.close()
                    startActivity(Intent(this, LanguageActivity::class.java))
                }
                R.id.nav_devices -> {
                    Toast.makeText(this, "Devices", Toast.LENGTH_SHORT).show()
                }
            }
            drawerLayout.close()
            true
        }

        val headerView = navigationView.getHeaderView(0)

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) {
                try {
                    val db = DBhelper.getInstance(this@ChatsActivity)
                    val cursor = db.readableDatabase.rawQuery("SELECT * FROM users ORDER BY created_at DESC LIMIT 1", null)
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("username"))
                        val avatarPath = cursor.getString(cursor.getColumnIndexOrThrow("avatar_media_id"))

                        val tvName = headerView.findViewById<TextView>(R.id.tvProfileName)
                        val ivAvatar = headerView.findViewById<ImageView>(R.id.ivProfileAvatar)

                        tvName.text = name

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
                                ivAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                            }
                        } else {
                            ivAvatar.setImageResource(R.drawable.ic_launcher_foreground)
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })

        headerView.setOnClickListener {
            drawerLayout.close()
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
        }

        val recyclerView: RecyclerView = findViewById(R.id.rvChats)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dummyChats = listOf(
            ChatModel(id = 1, name = "Влад Neko", lastMessage = "Как дела?", avatarResId = R.drawable.ic_launcher_background),
            ChatModel(id = 2, name = "Денис", lastMessage = "Привет!", avatarResId = R.drawable.ic_launcher_background),
            ChatModel(id = 3, name = "DSBA233", lastMessage = "СОП!!!", avatarResId = R.drawable.ic_launcher_background),
            ChatModel(id = 4, name = "HACKERSHOP", lastMessage = "ПОПОЛНЕНИЕ МАГАЗИНА...", avatarResId = R.drawable.ic_launcher_background)
        )

        val adapter = ChatsAdapter(dummyChats)
        recyclerView.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase().trim()
                val filteredList = if (query.isEmpty()) {
                    dummyChats
                } else {
                    dummyChats.filter { chat ->
                        chat.name.lowercase().contains(query) ||
                                chat.lastMessage.lowercase().contains(query)
                    }
                }
                adapter.updateData(filteredList, query)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        findViewById<View>(R.id.btnAdd).setOnClickListener { view ->
            val inflater = layoutInflater
            val popupView = inflater.inflate(R.layout.layout_popup_menu, null)
            val popupWindow = android.widget.PopupWindow(
                popupView,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popupWindow.elevation = 20f
            popupWindow.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            popupView.findViewById<View>(R.id.menuCreateGroup).setOnClickListener {
                Toast.makeText(this, "Create Group", Toast.LENGTH_SHORT).show()
                popupWindow.dismiss()
            }

            popupView.findViewById<View>(R.id.menuAddContactQr).setOnClickListener {
                popupWindow.dismiss()
                val intent = Intent(this, ScanQrActivity::class.java)
                startActivity(intent)
            }

            popupWindow.showAsDropDown(view, 0, 10, android.view.Gravity.END)
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeToCurrentActivity()
    }

    override fun applyThemeToCurrentActivity() {
        super.applyThemeToCurrentActivity()

        val topBar = findViewById<View>(R.id.topBar)
        ThemeHelper.applyBackground(
            this,
            topBar,
            ThemeHelper.KEY_TOPBAR_COLOR,
            "NONE",
            ThemeHelper.DEFAULT_TOPBAR
        )
    }
}
