package com.example.memegram

import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.graphics.toColorInt

class SearchResultAdapter(
    private val items: List<Pair<Int, Message>>,
    private val userName: String,
    private val query: String,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val avatarColors = listOf(
        "#E53935", "#8E24AA", "#1E88E5", "#00897B", "#43A047", "#FB8C00", "#6D4C41"
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewAvatarBg: View = view.findViewById(R.id.viewAvatarBg)
        val tvAvatarInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvResultTime: TextView = view.findViewById(R.id.tvResultTime)
        val tvMessagePreview: TextView = view.findViewById(R.id.tvMessagePreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_search_result_item, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (adapterPos, message) = items[position]
        val senderName = if (message.isOutgoing) "You" else userName

        val colorHex = avatarColors[senderName.length % avatarColors.size]
        holder.viewAvatarBg.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorHex.toColorInt())
        }
        holder.tvAvatarInitial.text = senderName.firstOrNull()?.uppercase() ?: "?"
        holder.tvSenderName.text = senderName
        holder.tvResultTime.text = timeFormat.format(Date(message.timestamp))

        val spannable = SpannableString(message.text)
        val lower = message.text.lowercase()
        val lowerQ = query.lowercase()
        var start = 0
        while (start < lower.length) {
            val idx = lower.indexOf(lowerQ, start)
            if (idx == -1) break
            spannable.setSpan(
                BackgroundColorSpan("#FFD60A".toColorInt()),
                idx, idx + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = idx + query.length
        }
        holder.tvMessagePreview.text = spannable
        holder.itemView.setOnClickListener { onItemClick(adapterPos) }
    }

    override fun getItemCount() = items.size
}
