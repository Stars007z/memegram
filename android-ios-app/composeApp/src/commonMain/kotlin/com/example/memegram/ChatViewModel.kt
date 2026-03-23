package com.example.memegram

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Message(
    val id: Int,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean
)
class ChatViewModel(
    themePreferences: ThemePreferences
) : ViewModel() {

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    private val _chatBgColor = MutableStateFlow(themePreferences.getColor("chatbg", ThemePreferences.DefaultChatBg))
    val chatBgColor = _chatBgColor.asStateFlow()

    private val _myBubbleColor = MutableStateFlow(themePreferences.getColor("mybubble", ThemePreferences.DefaultMyBubble))
    val myBubbleColor = _myBubbleColor.asStateFlow()

    private val _theirBubbleColor = MutableStateFlow(themePreferences.getColor("theirbubble", ThemePreferences.DefaultTheirBubble))
    val theirBubbleColor = _theirBubbleColor.asStateFlow()

    init {
        _messages.value = listOf(
            Message(1, "Привет!", kotlin.time.Clock.System.now().toEpochMilliseconds() - 60000, false),
            Message(2, "Как дела с переходом на KMP? Плохо?", kotlin.time.Clock.System.now().toEpochMilliseconds() - 30000, false)
        )
    }

    fun updateInput(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isNotEmpty()) {
            val newMessage = Message(
                id = (0..10000).random(),
                text = text,
                timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                isOutgoing = true
            )
            _messages.update { current -> current + newMessage }
            _inputText.value = ""
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

}