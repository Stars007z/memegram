package com.example.memegram

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Модели данных
sealed class NotifItem {
    data class Category(val id: Int, val name: String, var isExpanded: Boolean = false) : NotifItem()
    data class Chat(val parentId: Int, val name: String, var isMuted: Boolean = false) : NotifItem()
    object CallsSettings : NotifItem()
}

class NotificationsActivity : BaseActivity() {

    private lateinit var rvNotifications: RecyclerView
    private lateinit var adapter: NotificationsAdapter

    private val allData = mutableListOf<NotifItem>()
    private val displayData = mutableListOf<NotifItem>()

    // Настройки
    private var currentVibrate = "Medium"
    private var currentRingtoneUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    private var currentRingtoneName = "Default"

    // Акцентный цвет приложения из ThemeHelper
    private var accentColor: Int = Color.BLUE

    private val ringtonePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                currentRingtoneUri = uri
                val ringtone = RingtoneManager.getRingtone(this, uri)
                currentRingtoneName = ringtone.getTitle(this) ?: "Unknown Ringtone"
            } else {
                currentRingtoneName = "Silent"
            }
            updateCallsBlock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        applyWindowInsets(R.id.mainLayout)

        // Получаем основной цвет из настроек внешнего вида
        accentColor = ThemeHelper.getColor(this, ThemeHelper.KEY_MY_BUBBLE_COLOR, ThemeHelper.DEFAULT_MY_BUBBLE)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        rvNotifications = findViewById(R.id.rvNotifications)

        initData()
        setupRecyclerView()
    }

    private fun initData() {
        val privateChats = listOf(
            NotifItem.Chat(1, "Влад Neko", false),
            NotifItem.Chat(1, "Денис", true),
            NotifItem.Chat(1, "Ivan Kopylov", false)
        )
        val groups = listOf(NotifItem.Chat(2, "Kotlin Devs", false))
        val channels = listOf(NotifItem.Chat(3, "Meme Channel", false))

        allData.add(NotifItem.Category(1, "Private Chats"))
        allData.addAll(privateChats)

        allData.add(NotifItem.Category(2, "Groups"))
        allData.addAll(groups)

        allData.add(NotifItem.Category(3, "Channels"))
        allData.addAll(channels)

        allData.add(NotifItem.CallsSettings)

        updateDisplayData()
    }

    private fun updateDisplayData() {
        displayData.clear()
        var currentExpandedCategoryId = -1

        for (item in allData) {
            when (item) {
                is NotifItem.Category -> {
                    displayData.add(item)
                    if (item.isExpanded) currentExpandedCategoryId = item.id
                }
                is NotifItem.Chat -> {
                    if (item.parentId == currentExpandedCategoryId) displayData.add(item)
                }
                is NotifItem.CallsSettings -> displayData.add(item)
            }
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun updateCallsBlock() {
        val callsIndex = displayData.indexOf(NotifItem.CallsSettings)
        if (callsIndex != -1) adapter.notifyItemChanged(callsIndex)
    }

    private fun setupRecyclerView() {
        adapter = NotificationsAdapter()
        rvNotifications.layoutManager = LinearLayoutManager(this)
        rvNotifications.adapter = adapter
    }

    private fun showMuteDialog(chat: NotifItem.Chat) {
        val options = arrayOf("Unmute", "Mute forever", "Mute for...")
        AlertDialog.Builder(this)
            .setTitle("Mute ${chat.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { chat.isMuted = false; updateDisplayData() }
                    1 -> { chat.isMuted = true; updateDisplayData() }
                    2 -> showMuteForDialog(chat)
                }
            }.show()
    }

    private fun showMuteForDialog(chat: NotifItem.Chat) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Hours"
        }
        AlertDialog.Builder(this)
            .setTitle("Mute for how many hours?")
            .setView(input)
            .setPositiveButton("Mute") { _, _ ->
                val hours = input.text.toString().toIntOrNull()
                if (hours != null && hours > 0) {
                    chat.isMuted = true
                    updateDisplayData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVibrateDialog() {
        val options = arrayOf("Off", "Short", "Medium", "Strong")
        AlertDialog.Builder(this)
            .setTitle("Vibrate for Calls")
            .setItems(options) { _, which ->
                currentVibrate = options[which]
                updateCallsBlock()
            }.show()
    }

    private fun pickRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
        ringtonePickerLauncher.launch(intent)
    }

    inner class NotificationsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_CATEGORY = 0
        private val TYPE_CHAT = 1
        private val TYPE_CALLS = 2

        override fun getItemViewType(position: Int): Int {
            return when (displayData[position]) {
                is NotifItem.Category -> TYPE_CATEGORY
                is NotifItem.Chat -> TYPE_CHAT
                is NotifItem.CallsSettings -> TYPE_CALLS
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_CATEGORY -> CategoryHolder(inflater.inflate(R.layout.item_notification_category, parent, false))
                TYPE_CHAT -> ChatHolder(inflater.inflate(R.layout.item_notification_chat, parent, false))
                else -> CallsHolder(inflater.inflate(R.layout.item_notification_calls, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = displayData[position]) {
                is NotifItem.Category -> (holder as CategoryHolder).bind(item)
                is NotifItem.Chat -> (holder as ChatHolder).bind(item)
                is NotifItem.CallsSettings -> (holder as CallsHolder).bind()
            }
        }

        override fun getItemCount() = displayData.size

        inner class CategoryHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvCategoryName)
            val ivArrow: ImageView = view.findViewById(R.id.ivArrow)

            fun bind(item: NotifItem.Category) {
                tvName.text = item.name
                ivArrow.rotation = if (item.isExpanded) 90f else 180f

                itemView.setOnClickListener {
                    item.isExpanded = !item.isExpanded
                    allData.filterIsInstance<NotifItem.Category>().forEach {
                        if (it.id != item.id) it.isExpanded = false
                    }
                    updateDisplayData()
                }
            }
        }

        inner class ChatHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvChatName)
            val tvStatus: TextView = view.findViewById(R.id.tvMuteStatus)
            val viewStatusIndicator: View = view.findViewById(R.id.viewStatusIndicator)

            fun bind(item: NotifItem.Chat) {
                tvName.text = item.name

                if (item.isMuted) {
                    val mutedColor = Color.parseColor("#FF3B30") // Красный цвет iOS-стайла
                    tvStatus.text = "Muted"
                    tvStatus.setTextColor(mutedColor)
                    viewStatusIndicator.setBackgroundColor(mutedColor)
                } else {
                    tvStatus.text = "Unmuted"
                    tvStatus.setTextColor(accentColor) // Используем цвет темы!
                    viewStatusIndicator.setBackgroundColor(accentColor) // Используем цвет темы!
                }

                itemView.setOnClickListener { showMuteDialog(item) }
            }
        }

        inner class CallsHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvVibrate: TextView = view.findViewById(R.id.tvVibrateValue)
            val tvRingtone: TextView = view.findViewById(R.id.tvRingtoneValue)
            val btnVibrate: View = view.findViewById(R.id.btnVibrate)
            val btnRingtone: View = view.findViewById(R.id.btnRingtone)

            fun bind() {
                tvVibrate.text = currentVibrate
                tvRingtone.text = currentRingtoneName

                // Красим текст значений в акцентный цвет темы
                tvVibrate.setTextColor(accentColor)
                tvRingtone.setTextColor(accentColor)

                btnVibrate.setOnClickListener { showVibrateDialog() }
                btnRingtone.setOnClickListener { pickRingtone() }
            }
        }
    }
}
