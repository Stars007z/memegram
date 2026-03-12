package com.example.memegram

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Простая модель данных для заблокированного пользователя
data class BlockedUser(val id: Int, val name: String)

// Адаптер
class BlackListAdapter(
    private val items: MutableList<BlockedUser>,
    private val onUnblockClick: (BlockedUser, Int) -> Unit
) : RecyclerView.Adapter<BlackListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val btnUnblock: TextView = view.findViewById(R.id.btnUnblock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_black_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = items[position]
        holder.name.text = user.name

        holder.btnUnblock.setOnClickListener {
            onUnblockClick(user, position)
        }
    }

    override fun getItemCount() = items.size
}

class BlackListActivity : BaseActivity() {

    private lateinit var rvBlackList: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var adapter: BlackListAdapter

    // Заглушка пока что
    private val blockedUsers = mutableListOf(
        BlockedUser(1, "123"),
        BlockedUser(2, "Bot"),
        BlockedUser(3, "Ex")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_black_list)
        applyWindowInsets(R.id.mainLayout)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        rvBlackList = findViewById(R.id.rvBlackList)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        setupRecyclerView()
        checkEmptyState()
    }

    private fun setupRecyclerView() {
        adapter = BlackListAdapter(blockedUsers) { user, position ->
            // Логика разблокировки
            blockedUsers.removeAt(position)
            adapter.notifyItemRemoved(position)
            checkEmptyState()
        }

        rvBlackList.layoutManager = LinearLayoutManager(this)
        rvBlackList.adapter = adapter
    }

    private fun checkEmptyState() {
        if (blockedUsers.isEmpty()) {
            rvBlackList.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
        } else {
            rvBlackList.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
        }
    }
}
