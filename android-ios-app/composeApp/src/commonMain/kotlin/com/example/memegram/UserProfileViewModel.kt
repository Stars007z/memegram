package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserProfileViewModel(
    private val api: ApiService,
    private val contactsRepository: ContactsRepository
) : ViewModel() {

    private val _userProfile = MutableStateFlow<UserProfileResponse?>(null)
    val userProfile: StateFlow<UserProfileResponse?> = _userProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun loadUser(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _userProfile.value = api.getUserById(userId)
            } catch (e: Exception) {
                _actionMessage.value = "Ошибка загрузки профиля"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToContacts() {
        val pubKey = _userProfile.value?.userPublicKey ?: return
        viewModelScope.launch {
            try {
                contactsRepository.addContact(pubKey)
                _actionMessage.value = "Пользователь добавлен в контакты"
            } catch (e: Exception) {
                _actionMessage.value = "Ошибка: Пользователь уже в контактах или недоступен"
            }
        }
    }

    fun clearMessage() {
        _actionMessage.value = null
    }
}