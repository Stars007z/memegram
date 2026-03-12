package com.example.memegram

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class GalleryMedia(
    val id: Long,
    val uri: Uri,
    val dateAdded: Long,
    var isSelected: Boolean = false
)

class GalleryAdapter(
    private val onMediaClick: (GalleryMedia) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    var mediaList = listOf<GalleryMedia>()

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.ivPhoto)
        val selection: FrameLayout = view.findViewById(R.id.selectionIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return GalleryViewHolder(view)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        val media = mediaList[position]
        holder.image.load(media.uri) {
            crossfade(true)
            size(300, 300)
        }
        holder.selection.visibility = if (media.isSelected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            media.isSelected = !media.isSelected
            notifyItemChanged(position)
            onMediaClick(media)
        }
    }

    override fun getItemCount() = mediaList.size
}

class AttachmentsAdapter(
    private val onDeleteClick: (GalleryMedia) -> Unit
) : RecyclerView.Adapter<AttachmentsAdapter.AttachmentViewHolder>() {

    val items = mutableListOf<GalleryMedia>()

    class AttachmentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val preview: ImageView = view.findViewById(R.id.ivPreview)
        val btnRemove: ImageView = view.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttachmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attached_file, parent, false)
        return AttachmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttachmentViewHolder, position: Int) {
        val item = items[position]
        holder.preview.load(item.uri)
        holder.btnRemove.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount() = items.size
}

class ChatActivity : BaseActivity() {

    private lateinit var recyclerMessages: RecyclerView
    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var etMessage: EditText
    private lateinit var tvName: TextView
    private lateinit var btnMenu: ImageView
    private lateinit var btnVoice: ImageView
    private lateinit var rvAttachedFiles: RecyclerView
    private lateinit var attachmentsAdapter: AttachmentsAdapter

    // Search
    private var isSearchMode = false
    private val searchMatches = mutableListOf<Int>()
    private var currentMatchIdx = 0
    private lateinit var layoutBottomInput: View
    private lateinit var layoutSearchBar: View
    private lateinit var etSearchChat: EditText
    private lateinit var tvMatchCounter: TextView
    private lateinit var btnMatchPrev: ImageView
    private lateinit var btnMatchNext: ImageView
    private lateinit var btnSearchList: ImageView

    private var chatId: Int = -1
    private var userName: String = ""
    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    private val selectedMedia = mutableListOf<GalleryMedia>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) loadImagesAndShowSheet()
            else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    addAttachment(GalleryMedia(0, uri, System.currentTimeMillis()))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        applyWindowInsets(R.id.mainLayout)

        chatId = intent.getIntExtra("chat_id", -1)
        userName = intent.getStringExtra("user_name") ?: "User"

        initViews()
        setupRecyclerView()
        setupAttachmentsRecycler()
        loadMessages()
        setupListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSearchMode) closeSearchMode() else finish()
            }
        })
    }

    private fun initViews() {
        recyclerMessages = findViewById(R.id.recyclerMessages)
        etMessage = findViewById(R.id.etMessage)
        tvName = findViewById(R.id.tvName)
        btnMenu = findViewById(R.id.btnMenu)
        btnVoice = findViewById(R.id.btnVoice)
        rvAttachedFiles = findViewById(R.id.rvAttachedFiles)
        tvName.text = userName

        layoutBottomInput = findViewById(R.id.layoutBottomInput)
        layoutSearchBar = findViewById(R.id.layoutSearchBar)
        etSearchChat = layoutSearchBar.findViewById(R.id.etSearchChat)
        tvMatchCounter = layoutSearchBar.findViewById(R.id.tvMatchCounter)
        btnMatchPrev = layoutSearchBar.findViewById(R.id.btnMatchPrev)
        btnMatchNext = layoutSearchBar.findViewById(R.id.btnMatchNext)
        btnSearchList = layoutSearchBar.findViewById(R.id.btnSearchList)
        layoutSearchBar.visibility = View.GONE

        layoutSearchBar.findViewById<ImageView>(R.id.btnExitSearch).setOnClickListener {
            closeSearchMode()
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        messagesAdapter = MessagesAdapter(mutableListOf())
        recyclerMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = messagesAdapter
        }
    }

    private fun setupAttachmentsRecycler() {
        attachmentsAdapter = AttachmentsAdapter { item ->
            val position = attachmentsAdapter.items.indexOf(item)
            if (position != -1) {
                attachmentsAdapter.items.removeAt(position)
                attachmentsAdapter.notifyItemRemoved(position)
                if (attachmentsAdapter.items.isEmpty()) {
                    rvAttachedFiles.visibility = View.GONE
                    if (etMessage.text.toString().trim().isEmpty()) setSendButtonState(false)
                }
            }
        }
        rvAttachedFiles.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = attachmentsAdapter
        }
    }

    private fun loadMessages() {
        val exampleMessages = listOf(
            Message(
                1, "Ты кто", null,
                System.currentTimeMillis() - 86400000, false, false,
                true, dateFormat.format(Date(System.currentTimeMillis() - 86400000))
            ),
            Message(2, "Hello!", null, System.currentTimeMillis(), true)
        )
        exampleMessages.forEach { messagesAdapter.addMessage(it) }
        recyclerMessages.scrollToPosition(messagesAdapter.itemCount - 1)
    }

    private fun setupListeners() {
        btnMenu.setOnClickListener { showChatMenu(it) }

        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                setSendButtonState(
                    s.toString().trim().isNotEmpty() || attachmentsAdapter.items.isNotEmpty()
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnVoice.setOnClickListener {
            if (etMessage.text.toString().trim().isNotEmpty() || attachmentsAdapter.items.isNotEmpty()) {
                sendMessage()
            } else {
                Toast.makeText(this, "Voice message (holding) coming soon", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageView>(R.id.btnAttach).setOnClickListener { checkPermissionAndOpenGallery() }
        findViewById<ImageView>(R.id.btnEmoji).setOnClickListener {
            Toast.makeText(this, "Emoji picker coming soon", Toast.LENGTH_SHORT).show()
        }

        setupSearchBar()
    }

    private fun setSendButtonState(isSend: Boolean) {
        if (isSend) {
            btnVoice.setImageResource(R.drawable.ic_send)
            btnVoice.setBackgroundResource(R.drawable.circle_green_bg)
            btnVoice.imageTintList = ColorStateList.valueOf(Color.WHITE)
            val padding = (10 * resources.displayMetrics.density).toInt()
            btnVoice.setPadding(padding, padding, padding, padding)
        } else {
            btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now)
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            btnVoice.setBackgroundResource(outValue.resourceId)
            btnVoice.imageTintList = ColorStateList.valueOf(Color.GRAY)
            val padding = (8 * resources.displayMetrics.density).toInt()
            btnVoice.setPadding(padding, padding, padding, padding)
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()

        if (attachmentsAdapter.items.isNotEmpty()) {
            val size = attachmentsAdapter.items.size
            attachmentsAdapter.items.clear()
            attachmentsAdapter.notifyItemRangeRemoved(0, size)
            rvAttachedFiles.visibility = View.GONE
        }

        if (text.isNotEmpty()) {
            val message = Message((0..10000).random(), text, null, System.currentTimeMillis(), true)
            messagesAdapter.addMessage(message)
            recyclerMessages.scrollToPosition(messagesAdapter.itemCount - 1)
            etMessage.text.clear()
        }

        setSendButtonState(false)
    }

    private fun checkPermissionAndOpenGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            loadImagesAndShowSheet()
        else
            requestPermissionLauncher.launch(permission)
    }

    private fun loadImagesAndShowSheet() {
        lifecycleScope.launch {
            val galleryList = withContext(Dispatchers.IO) { getAllImages(this@ChatActivity) }
            showAttachmentBottomSheet(galleryList)
        }
    }

    @SuppressLint("InflateParams")
    private fun showAttachmentBottomSheet(galleryList: List<GalleryMedia>) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_attachment_sheet, null)
        dialog.setContentView(view)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.85).toInt()
        val behavior = BottomSheetBehavior.from(bottomSheet!!)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        val rvGallery = view.findViewById<RecyclerView>(R.id.rvGallery)
        val layoutFastScroll = view.findViewById<View>(R.id.layoutFastScroll)
        val tvDate = view.findViewById<TextView>(R.id.tvDateIndicator)
        val btnConfirm = view.findViewById<FloatingActionButton>(R.id.btnConfirmSelection)
        val btnFileSystem = view.findViewById<View>(R.id.btnOpenFileSystem)

        val galleryAdapter = GalleryAdapter { media ->
            if (media.isSelected) {
                if (!selectedMedia.contains(media)) selectedMedia.add(media)
            } else {
                selectedMedia.remove(media)
            }
            btnConfirm.visibility = if (selectedMedia.isNotEmpty()) View.VISIBLE else View.GONE
        }
        galleryAdapter.mediaList = galleryList
        rvGallery.layoutManager = GridLayoutManager(this, 3)
        rvGallery.adapter = galleryAdapter

        var isFastScrolling = false

        rvGallery.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as GridLayoutManager
                val firstPos = lm.findFirstVisibleItemPosition()
                if (firstPos != RecyclerView.NO_POSITION) {
                    val date = galleryList[firstPos].dateAdded * 1000
                    tvDate.text = DateFormat.format("MMMM yyyy", date)
                    if (!isFastScrolling) {
                        val extent = recyclerView.computeVerticalScrollExtent()
                        val range = recyclerView.computeVerticalScrollRange()
                        val offset = recyclerView.computeVerticalScrollOffset()
                        if (range > extent) {
                            val scrollRatio = offset.toFloat() / (range - extent).toFloat()
                            val maxY = recyclerView.height - layoutFastScroll.height
                            layoutFastScroll.translationY = scrollRatio * maxY
                        }
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !isFastScrolling)
                    layoutFastScroll.animate().alpha(0f).setDuration(300).start()
                else if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
                    layoutFastScroll.animate().alpha(1f).setDuration(150).start()
            }
        })

        layoutFastScroll.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isFastScrolling = true
                    v.animate().alpha(1f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val y = event.rawY
                    val rvLocation = IntArray(2)
                    rvGallery.getLocationOnScreen(rvLocation)
                    val relativeY = y - rvLocation[1]
                    val maxY = rvGallery.height - v.height
                    var newY = relativeY - v.height / 2f
                    newY = newY.coerceIn(0f, maxY.toFloat())
                    v.translationY = newY
                    val scrollRatio = newY / maxY
                    val totalItems = galleryList.size
                    val targetPosition = (scrollRatio * totalItems).toInt().coerceIn(0, totalItems - 1)
                    rvGallery.scrollToPosition(targetPosition)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isFastScrolling = false
                    v.animate().alpha(0f).setDuration(300).start()
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isFastScrolling = false
                    v.animate().alpha(0f).setDuration(300).start()
                    true
                }
                else -> false
            }
        }

        btnConfirm.setOnClickListener {
            selectedMedia.forEach { addAttachment(it) }
            selectedMedia.clear()
            dialog.dismiss()
        }

        btnFileSystem.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePickerLauncher.launch(intent)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun addAttachment(media: GalleryMedia) {
        rvAttachedFiles.visibility = View.VISIBLE
        attachmentsAdapter.items.add(media)
        attachmentsAdapter.notifyItemInserted(attachmentsAdapter.items.size - 1)
        setSendButtonState(true)
    }

    private fun getAllImages(context: Context): List<GalleryMedia> {
        val images = mutableListOf<GalleryMedia>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                images.add(GalleryMedia(id, contentUri, dateAdded))
            }
        }
        return images
    }

    private fun showChatMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_chat, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_search -> { openSearchMode(); true }
                R.id.menu_call -> { Toast.makeText(this, "Call", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_mute -> { Toast.makeText(this, "Mute", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_clear_history -> { showClearHistoryDialog(); true }
                R.id.menu_delete_chat -> { showDeleteChatDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear history")
            .setMessage("Are you sure?")
            .setPositiveButton("Clear") { _, _ ->
                messagesAdapter.clearMessages()
                Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteChatDialog() {
        val options = arrayOf("Delete for me", "Delete for both")
        AlertDialog.Builder(this)
            .setTitle("Delete chat")
            .setItems(options) { _, _ ->
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── SEARCH ──────────────────────────────────────────────

    private fun openSearchMode() {
        isSearchMode = true
        layoutBottomInput.visibility = View.GONE
        rvAttachedFiles.visibility = View.GONE
        layoutSearchBar.visibility = View.VISIBLE
        etSearchChat.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearchChat, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchMode() {
        isSearchMode = false
        layoutSearchBar.visibility = View.GONE
        layoutBottomInput.visibility = View.VISIBLE
        if (attachmentsAdapter.items.isNotEmpty()) rvAttachedFiles.visibility = View.VISIBLE
        searchMatches.clear()
        messagesAdapter.searchQuery = ""
        messagesAdapter.currentMatchPosition = -1
        messagesAdapter.notifyItemRangeChanged(0, messagesAdapter.itemCount)
        etSearchChat.text.clear()
        tvMatchCounter.text = ""
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchChat.windowToken, 0)
    }

    private fun setupSearchBar() {
        etSearchChat.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s.toString().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        btnMatchPrev.setOnClickListener { navigateMatch(1) }
        btnMatchNext.setOnClickListener { navigateMatch(-1) }
        btnSearchList.setOnClickListener { showSearchResultsList() }
    }

    private fun performSearch(query: String) {
        searchMatches.clear()
        if (query.isEmpty()) {
            messagesAdapter.searchQuery = ""
            messagesAdapter.currentMatchPosition = -1
            messagesAdapter.notifyItemRangeChanged(0, messagesAdapter.itemCount)
            tvMatchCounter.text = ""
            return
        }
        messagesAdapter.getMessages().forEachIndexed { index, message ->
            if (message.text.contains(query, ignoreCase = true)) searchMatches.add(index)
        }
        messagesAdapter.searchQuery = query
        if (searchMatches.isNotEmpty()) {
            currentMatchIdx = searchMatches.size - 1
            messagesAdapter.currentMatchPosition = searchMatches[currentMatchIdx]
            recyclerMessages.scrollToPosition(searchMatches[currentMatchIdx])
        } else {
            currentMatchIdx = 0
            messagesAdapter.currentMatchPosition = -1
        }
        messagesAdapter.notifyItemRangeChanged(0, messagesAdapter.itemCount)
        updateMatchCounter()
    }

    private fun navigateMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIdx = (currentMatchIdx + direction + searchMatches.size) % searchMatches.size
        messagesAdapter.currentMatchPosition = searchMatches[currentMatchIdx]
        messagesAdapter.notifyItemRangeChanged(0, messagesAdapter.itemCount)
        recyclerMessages.scrollToPosition(searchMatches[currentMatchIdx])
        updateMatchCounter()
    }

    private fun updateMatchCounter() {
        tvMatchCounter.text = if (searchMatches.isEmpty()) {
            if (messagesAdapter.searchQuery.isEmpty()) "" else "0"
        } else {
            val displayIdx = searchMatches.size - currentMatchIdx
            "$displayIdx/${searchMatches.size}"
        }
    }

    @SuppressLint("InflateParams")
    private fun showSearchResultsList() {
        if (searchMatches.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_search_results_sheet, null)
        dialog.setContentView(view)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.75).toInt()
        val behavior = BottomSheetBehavior.from(bottomSheet!!)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        view.findViewById<TextView>(R.id.tvResultsTitle).text =
            "Found ${searchMatches.size} result${if (searchMatches.size != 1) "s" else ""}"

        val allMessages = messagesAdapter.getMessages()
        val items = searchMatches.map { pos -> Pair(pos, allMessages[pos]) }

        val adapter = SearchResultAdapter(
            items, userName, messagesAdapter.searchQuery
        ) { adapterPos ->
            dialog.dismiss()
            currentMatchIdx = searchMatches.indexOf(adapterPos)
            messagesAdapter.currentMatchPosition = adapterPos
            messagesAdapter.notifyItemRangeChanged(0, messagesAdapter.itemCount)
            recyclerMessages.scrollToPosition(adapterPos)
            updateMatchCounter()
        }

        view.findViewById<RecyclerView>(R.id.rvSearchResults).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            this.adapter = adapter
        }
        dialog.show()
    }

    override fun applyThemeToCurrentActivity() {
        super.applyThemeToCurrentActivity()
        val chatBg = findViewById<View>(R.id.recyclerMessages)
        ThemeHelper.applyBackground(
            this, chatBg,
            ThemeHelper.KEY_CHAT_BG_COLOR,
            ThemeHelper.KEY_CHAT_BG_IMAGE,
            ThemeHelper.DEFAULT_CHAT_BG
        )
    }
}
