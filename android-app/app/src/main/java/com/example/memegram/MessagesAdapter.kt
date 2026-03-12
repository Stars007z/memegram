package com.example.memegram

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.graphics.toColorInt

class MessagesAdapter(private val messages: MutableList<Message>) :
    RecyclerView.Adapter<MessagesAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    var searchQuery: String = ""
    var currentMatchPosition: Int = -1

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateSeparator: TextView = view.findViewById(R.id.tvDateSeparator)
        val containerIncoming: LinearLayout = view.findViewById(R.id.containerIncoming)
        val tvMessageIncoming: TextView = view.findViewById(R.id.tvMessageIncoming)
        val imgMessageIncoming: ImageView = view.findViewById(R.id.imgMessageIncoming)
        val tvTimeIncoming: TextView = view.findViewById(R.id.tvTimeIncoming)
        val containerOutgoing: LinearLayout = view.findViewById(R.id.containerOutgoing)
        val tvMessageOutgoing: TextView = view.findViewById(R.id.tvMessageOutgoing)
        val imgMessageOutgoing: ImageView = view.findViewById(R.id.imgMessageOutgoing)
        val tvTimeOutgoing: TextView = view.findViewById(R.id.tvTimeOutgoing)
        val imgStatus: ImageView = view.findViewById(R.id.imgStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        holder.dateSeparator.visibility = if (message.showDateSeparator) {
            holder.dateSeparator.text = message.dateSeparatorText
            View.VISIBLE
        } else View.GONE

        if (message.isOutgoing) {
            holder.containerOutgoing.visibility = View.VISIBLE
            holder.containerIncoming.visibility = View.GONE
            applyHighlight(holder.tvMessageOutgoing, message.text, position)
            holder.tvTimeOutgoing.text = timeFormat.format(Date(message.timestamp))
            holder.imgStatus.setImageResource(R.drawable.ic_check)
            if (message.imageUri != null) {
                holder.imgMessageOutgoing.visibility = View.VISIBLE
                holder.imgMessageOutgoing.load(message.imageUri)
            } else {
                holder.imgMessageOutgoing.visibility = View.GONE
            }
        } else {
            holder.containerIncoming.visibility = View.VISIBLE
            holder.containerOutgoing.visibility = View.GONE
            applyHighlight(holder.tvMessageIncoming, message.text, position)
            holder.tvTimeIncoming.text = timeFormat.format(Date(message.timestamp))
            if (message.imageUri != null) {
                holder.imgMessageIncoming.visibility = View.VISIBLE
                holder.imgMessageIncoming.load(message.imageUri)
            } else {
                holder.imgMessageIncoming.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getMessages(): List<Message> = messages.toList()

    private fun applyHighlight(textView: TextView, text: String, position: Int) {
        if (searchQuery.isEmpty() || text.isEmpty()) {
            textView.text = text
            return
        }
        val spannable = SpannableString(text)
        val lowerText = text.lowercase()
        val lowerQuery = searchQuery.lowercase()
        var start = 0
        while (start < lowerText.length) {
            val idx = lowerText.indexOf(lowerQuery, start)
            if (idx == -1) break
            val bgColor = if (position == currentMatchPosition)
                "#FF9500".toColorInt()
            else
                "#FFD60A".toColorInt()
            spannable.setSpan(
                BackgroundColorSpan(bgColor),
                idx, idx + searchQuery.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = idx + searchQuery.length
        }
        textView.text = spannable
    }
}
