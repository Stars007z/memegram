package com.example.memegram

import android.content.Intent
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.toColorInt

class ChatsAdapter(private var chats: List<ChatModel>) :
    RecyclerView.Adapter<ChatsAdapter.ChatViewHolder>() {

    private var query: String = ""

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.chatName)
        val tvLastMessage: TextView = view.findViewById(R.id.chatLastMessage)
        val imgAvatar: ImageView = view.findViewById(R.id.chatAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = chats[position]

        holder.tvName.text = highlightText(chat.name, query)
        holder.tvLastMessage.text = highlightText(chat.lastMessage, query)
        holder.imgAvatar.setImageResource(chat.avatarResId)

        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra("chat_id", chat.id)
                putExtra("user_name", chat.name)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = chats.size

    fun updateData(newChats: List<ChatModel>, searchQuery: String) {
        chats = newChats
        query = searchQuery
        notifyDataSetChanged()
    }

    private fun highlightText(text: String, query: String): SpannableString {
        val spannable = SpannableString(text)
        if (query.isNotEmpty()) {
            val startPos = text.lowercase().indexOf(query.lowercase())
            if (startPos != -1) {
                val endPos = startPos + query.length
                spannable.setSpan(
                    BackgroundColorSpan("#B3E5FC".toColorInt()),
                    startPos,
                    endPos,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }
}
