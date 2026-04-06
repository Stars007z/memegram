package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ContactsRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.utils.generateUuid
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ContactsViewModel(
    private val contactsRepository: ContactsRepository,
    private val api: ApiService,
    private val mlsManager: MlsManager
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts: StateFlow<List<ContactEntry>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _addSuccess = MutableStateFlow(false)
    val addSuccess: StateFlow<Boolean> = _addSuccess.asStateFlow()

    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    private val _chatCreated = MutableStateFlow<String?>(null)
    val chatCreated: StateFlow<String?> = _chatCreated.asStateFlow()

    private val _isCreatingChat = MutableStateFlow(false)
    val isCreatingChat: StateFlow<Boolean> = _isCreatingChat.asStateFlow()

    init { loadContacts() }

    fun loadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            contactsRepository.getContacts()
                .onSuccess { _contacts.value = it.sortedByDescending { c -> c.isFavorite } }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    fun addContact(publicKey: String) {
        if (publicKey.isBlank()) { _error.value = "Введите публичный ключ"; return }
        viewModelScope.launch {
            _isAdding.value = true
            contactsRepository.addContact(publicKey)
                .onSuccess { entry ->
                    _contacts.value = (_contacts.value + entry)
                        .sortedByDescending { it.isFavorite }
                    _addSuccess.value = true
                }
                .onFailure { _error.value = it.message }
            _isAdding.value = false
        }
    }
    fun clearChatCreated() { _chatCreated.value = null }

    fun toggleFavorite(contactUserId: String) {
        val entry = _contacts.value.find { it.contactUserId == contactUserId } ?: return
        viewModelScope.launch {
            contactsRepository.updateContact(contactUserId, !entry.isFavorite)
                .onSuccess { updated ->
                    _contacts.value = _contacts.value
                        .map { if (it.contactUserId == contactUserId) updated else it }
                        .sortedByDescending { it.isFavorite }
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun removeContact(contactUserId: String) {
        viewModelScope.launch {
            contactsRepository.removeContact(contactUserId)
                .onSuccess { _contacts.value = _contacts.value.filter { it.contactUserId != contactUserId } }
                .onFailure { _error.value = it.message }
        }
    }

    fun blockUser(contactUserId: String) {
        viewModelScope.launch {
            contactsRepository.blockUser(contactUserId)
                .onSuccess { _contacts.value = contacts.value.filter { it.contactUserId != contactUserId } }
                .onFailure { _error.value = it.message }
        }
    }

    fun resetAddSuccess() { _addSuccess.value = false }
    fun clearError() { _error.value = null }

    private val _pendingChatContact = MutableStateFlow<String?>(null)

    fun startDirectChatWith(entry: ContactEntry) {
        val displayName = entry.profile?.username
            ?.takeIf { it.isNotBlank() }
            ?: entry.contactUserId.take(8)
        _pendingChatContact.value = displayName

        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null
            try {
                val recipientPackages = api.getKeyPackagesForUser(entry.contactUserId)
                val myUserId = mlsManager.getMyUserId()
                val myDeviceId = mlsManager.getMyDeviceId()
                val myPackages = if (myUserId.isNotBlank()) {
                    api.getKeyPackagesForUser(myUserId).filter { it.deviceId != myDeviceId }
                } else emptyList()

                val allPackagesToAdd = recipientPackages + myPackages

                if (allPackagesToAdd.isEmpty()) {
                    _error.value = "Не найдено устройств для создания чата"
                    return@launch
                }

                val mlsGroupId = "${entry.contactUserId.take(16)}_${Clock.System.now().toEpochMilliseconds()}"

                val firstKp = allPackagesToAdd.first()
                val createResult = mlsManager.createGroup(
                    mlsGroupId = mlsGroupId,
                    peerKeyPackageB64 = firstKp.keyPackageData
                )

                val conv = api.createDirectConversation(
                    CreateDirectConversationRequest(
                        recipientUserId = entry.contactUserId,
                        welcomeMessages = listOf(
                            DeviceWelcome(
                                deviceId = firstKp.deviceId,
                                welcomeData = createResult.welcomeB64
                            )
                        )
                    )
                )

                mlsManager.bindConversation(conv.id, mlsGroupId)
                var currentEpoch = 1L
                mlsManager.updateGroupEpoch(conv.id, currentEpoch)

                for (kp in allPackagesToAdd.drop(1)) {
                    try {
                        val addResult = mlsManager.addMemberToGroup(conv.id, kp.keyPackageData)
                        mlsManager.flushState()

                        api.commitGroupChange(
                            conv.id,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = (currentEpoch + 1).toInt(),
                                welcomeMessages = listOf(
                                    DeviceWelcome(deviceId = kp.deviceId, welcomeData = addResult.welcomeB64)
                                )
                            )
                        )
                        currentEpoch++
                        mlsManager.updateGroupEpoch(conv.id, currentEpoch)
                        println("MemegramDebug: Добавлено дополнительное устройство ${kp.deviceId}, epoch=$currentEpoch")
                    } catch (e: Exception) {
                        println("MemegramDebug: Ошибка добавления устройства ${kp.deviceId}: ${e.message}")
                    }
                }

                mlsManager.onKeyPackageConsumed()
                _chatCreated.value = conv.id

            } catch (e: Exception) {
                _error.value = "Не удалось создать чат: ${e.message}"
            } finally {
                _isCreatingChat.value = false
            }
        }
    }

    fun getPendingChatName(): String? = _pendingChatContact.value
    fun clearPendingChatName() { _pendingChatContact.value = null }

    fun createGroupChat(groupName: String, selectedUserIds: List<String>) {
        if (groupName.isBlank() || selectedUserIds.isEmpty()) {
            _error.value = "Введите название группы и выберите участников"
            return
        }

        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null
            try {
                val allDevicesToInvite = mutableListOf<Pair<String, UserDeviceKeyPackage>>()

                for (userId in selectedUserIds) {
                    try {
                        val packages = api.getKeyPackagesForUser(userId)
                        packages.forEach { allDevicesToInvite.add(userId to it) }
                    } catch (e: Exception) {
                        println("MemegramDebug: Не удалось получить ключи для юзера $userId")
                    }
                }

                val myUserId = mlsManager.getMyUserId()
                val myDeviceId = mlsManager.getMyDeviceId()
                if (myUserId.isNotBlank()) {
                    val myPackages = api.getKeyPackagesForUser(myUserId).filter { it.deviceId != myDeviceId }
                    myPackages.forEach { allDevicesToInvite.add(myUserId to it) }
                }

                if (allDevicesToInvite.isEmpty()) {
                    _error.value = "Не найдено устройств для добавления в группу"
                    return@launch
                }

                val mlsGroupId = "group_${generateUuid()}"

                val firstDevice = allDevicesToInvite.first()
                val createResult = mlsManager.createGroup(
                    mlsGroupId = mlsGroupId,
                    peerKeyPackageB64 = firstDevice.second.keyPackageData
                )

                val uniqueUserIds = allDevicesToInvite.map { it.first }.distinct()
                val membersList = uniqueUserIds.map { uid ->
                    if (uid == firstDevice.first) {
                        MemberWelcomes(
                            userId = uid,
                            welcomes = listOf(
                                DeviceWelcome(
                                    deviceId = firstDevice.second.deviceId,
                                    welcomeData = createResult.welcomeB64
                                )
                            )
                        )
                    } else {
                        MemberWelcomes(userId = uid, welcomes = emptyList())
                    }
                }

                val conv = api.createGroupConversation(
                    CreateGroupConversationRequest(
                        name = groupName,
                        members = membersList
                    )
                )

                mlsManager.bindConversation(conv.id, mlsGroupId)
                var currentEpoch = 1L
                mlsManager.updateGroupEpoch(conv.id, currentEpoch)

                for (device in allDevicesToInvite.drop(1)) {
                    try {
                        val addResult = mlsManager.addMemberToGroup(conv.id, device.second.keyPackageData)
                        mlsManager.flushState()

                        try {
                            mlsManager.processCommit(conv.id, addResult.commitB64)
                            mlsManager.flushState()
                        } catch (e: Exception) {
                            println("MemegramDebug: Локальный коммит пропущен (уже применен)")
                        }

                        api.commitGroupChange(
                            conv.id,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = currentEpoch.toInt(),
                                welcomeMessages = listOf(
                                    DeviceWelcome(
                                        deviceId = device.second.deviceId,
                                        welcomeData = addResult.welcomeB64
                                    )
                                )
                            )
                        )

                        currentEpoch++
                        mlsManager.updateGroupEpoch(conv.id, currentEpoch)
                        println("MemegramDebug: В группу добавлен девайс ${device.second.deviceId}")
                    } catch (e: Exception) {
                        println("MemegramDebug: 🚨 Ошибка добавления девайса ${device.second.deviceId}: ${e.message}")
                        break
                    }
                }

                mlsManager.onKeyPackageConsumed()
                _chatCreated.value = conv.id

            } catch (e: Exception) {
                _error.value = "Ошибка создания группы: ${e.message}"
            } finally {
                _isCreatingChat.value = false
            }
        }
    }
    fun addAndStartChat(publicKey: String) {
        if (publicKey.isBlank()) return

        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null

            contactsRepository.addContact(publicKey)
                .onSuccess { newContactEntry ->
                    _contacts.value = (_contacts.value + newContactEntry).sortedByDescending { it.isFavorite }
                    _addSuccess.value = true
                    startDirectChatWith(newContactEntry)
                }
                .onFailure {
                    _error.value = "Не удалось добавить пользователя: ${it.message}"
                    _isCreatingChat.value = false
                }
        }
    }
}