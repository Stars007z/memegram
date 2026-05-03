package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiException
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
    private val mlsManager: MlsManager,
    private val blockedUsersCache: BlockedUsersCache
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

    private val _blockedByPeerError = MutableStateFlow<String?>(null)
    val blockedByPeerError: StateFlow<String?> = _blockedByPeerError.asStateFlow()
    fun clearBlockedByPeerError() { _blockedByPeerError.value = null }

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

    private suspend fun grantDirectPeerAdmin(conversationId: String, peerUserId: String) {
        runCatching { api.updateMemberRole(conversationId, peerUserId, "admin") }
            .onFailure { e ->
                println("MemegramDebug [DirectRole]: peer admin grant skipped: ${e.message}")
            }
    }

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
                .onSuccess {
                    _contacts.value = contacts.value.filter { it.contactUserId != contactUserId }
                    blockedUsersCache.add(contactUserId)
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun unblockUser(userId: String) {
        viewModelScope.launch {
            contactsRepository.unblockUser(userId)
                .onSuccess { blockedUsersCache.remove(userId) }
                .onFailure { _error.value = it.message }
        }
    }

    fun resetAddSuccess() { _addSuccess.value = false }
    fun clearError() { _error.value = null }

    private suspend fun commitGroupChangeWithRetry(
        convId: String,
        request: CommitGroupChangeRequest
    ): Long {
        return try {
            api.commitGroupChange(convId, request)
            request.newEpoch.toLong()
        } catch (e: Exception) {
            val expected = Regex("""expected\s+(\d+)""")
                .find(e.message ?: "")?.groupValues?.get(1)?.toLongOrNull()
            if (expected != null && expected > request.newEpoch) {
                println("MemegramDebug [MLS] Epoch conflict retry: ${request.newEpoch} → $expected for conv=$convId")
                api.commitGroupChange(convId, request.copy(newEpoch = expected.toInt()))
                expected
            } else {
                throw e
            }
        }
    }

    private val _pendingChatContact = MutableStateFlow<String?>(null)
    private val _pendingChatAvatarMediaId = MutableStateFlow<String?>(null)

    fun startDirectChatWith(entry: ContactEntry) {
        val displayName = entry.profile?.username
            ?.takeIf { it.isNotBlank() }
            ?: entry.contactUserId.take(8)
        _pendingChatContact.value = displayName
        _pendingChatAvatarMediaId.value = entry.profile?.avatarMediaId

        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null
            try {
                val recipientPackages = api.getKeyPackagesForUser(entry.contactUserId)
                    .groupBy { it.deviceId }.map { it.value.last() }
                val myUserId = mlsManager.getMyUserId()
                val myDeviceId = mlsManager.getMyDeviceId()
                val myPackages = if (myUserId.isNotBlank()) {
                    api.getKeyPackagesForUser(myUserId)
                        .filter { it.deviceId != myDeviceId }
                        .groupBy { it.deviceId }.map { it.value.last() }
                } else emptyList()

                val allPackagesToAdd = recipientPackages + myPackages

                if (allPackagesToAdd.isEmpty()) {
                    _error.value = "Не найдено устройств для создания чата"
                    return@launch
                }

                val mlsGroupId = "${entry.contactUserId.take(16)}_${Clock.System.now().toEpochMilliseconds()}"

                mlsManager.createEmptyGroup(mlsGroupId)

                val conv = api.createDirectConversation(
                    CreateDirectConversationRequest(
                        recipientUserId = entry.contactUserId,
                        welcomeMessages = emptyList()
                    )
                )

                mlsManager.bindConversation(conv.id, mlsGroupId)
                grantDirectPeerAdmin(conv.id, entry.contactUserId)

                var serverEpoch = conv.mlsGroup?.currentEpoch ?: 0L

                for (kp in allPackagesToAdd) {
                    try {
                        val addResult = mlsManager.addMemberToGroup(conv.id, kp.keyPackageData)
                        mlsManager.flushState()

                        val nextEpoch = serverEpoch + 1L

                        val actualEpoch = commitGroupChangeWithRetry(
                            conv.id,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = nextEpoch.toInt(),
                                welcomeMessages = listOf(
                                    DeviceWelcome(deviceId = kp.deviceId, welcomeData = addResult.welcomeB64)
                                ),
                                addedUserIds = listOf(entry.contactUserId)
                            )
                        )
                        mlsManager.mergePendingCommit(conv.id)
                        serverEpoch = actualEpoch
                        mlsManager.updateCommitCursor(conv.id, serverEpoch)
                        val realEpoch = mlsManager.getRealMlsEpoch(conv.id)
                        mlsManager.updateGroupEpoch(conv.id, realEpoch)
                    } catch (e: Exception) {
                        println("MemegramDebug [MLS] ❌ Ошибка добавления устройства ${kp.deviceId}: ${e.message}")
                        try { mlsManager.clearPendingCommit(conv.id) } catch (_: Exception) {}
                    }
                }

                mlsManager.onKeyPackageConsumed()
                _chatCreated.value = conv.id

            } catch (e: ApiException) {
                if (e.isBlocked) {
                    _blockedByPeerError.value = displayName
                } else {
                    _error.value = "Не удалось создать чат: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "Не удалось создать чат: ${e.message}"
            } finally {
                _isCreatingChat.value = false
            }
        }
    }

    fun getPendingChatName(): String? = _pendingChatContact.value
    fun clearPendingChatName() { _pendingChatContact.value = null }
    fun getPendingChatAvatarMediaId(): String? = _pendingChatAvatarMediaId.value
    fun clearPendingChatAvatarMediaId() { _pendingChatAvatarMediaId.value = null }

    fun createGroupChat(groupName: String, selectedUserIds: List<String>) {
        if (groupName.isBlank() || selectedUserIds.isEmpty()) {
            _error.value = "Введите название группы и выберите участников"
            return
        }

        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null
            try {
                val myUserId = mlsManager.getMyUserId()
                val myDeviceId = mlsManager.getMyDeviceId()

                val usersToInvite = selectedUserIds.filter { it != myUserId }

                if (usersToInvite.isEmpty()) {
                    _error.value = "Выберите других участников"
                    _isCreatingChat.value = false
                    return@launch
                }

                val allDevicesToInvite = mutableListOf<Pair<String, UserDeviceKeyPackage>>()

                for (userId in usersToInvite) {
                    try {
                        val packages = api.getKeyPackagesForUser(userId)
                            .groupBy { it.deviceId }.map { it.value.last() }
                        packages.forEach { allDevicesToInvite.add(userId to it) }
                    } catch (_: Exception) {}
                }

                if (myUserId.isNotBlank()) {
                    val myPackages = api.getKeyPackagesForUser(myUserId)
                        .filter { it.deviceId != myDeviceId }
                        .groupBy { it.deviceId }.map { it.value.last() }
                    myPackages.forEach { allDevicesToInvite.add(myUserId to it) }
                }

                if (allDevicesToInvite.isEmpty()) {
                    _error.value = "Не найдено устройств для добавления в группу"
                    return@launch
                }

                val mlsGroupId = "group_${generateUuid()}"

                mlsManager.createEmptyGroup(mlsGroupId)

                val uniqueUserIds = allDevicesToInvite.map { it.first }.distinct()
                val membersList = uniqueUserIds.map { uid ->
                    MemberWelcomes(userId = uid, welcomes = emptyList())
                }

                val conv = api.createGroupConversation(
                    CreateGroupConversationRequest(
                        name = groupName,
                        members = membersList.filter { it.userId != myUserId }
                    )
                )

                mlsManager.bindConversation(conv.id, mlsGroupId)

                var serverEpoch = conv.mlsGroup?.currentEpoch ?: 0L

                for (device in allDevicesToInvite) {
                    try {
                        val addResult = mlsManager.addMemberToGroup(conv.id, device.second.keyPackageData)
                        mlsManager.flushState()

                        val nextEpoch = serverEpoch + 1L

                        val actualEpoch = commitGroupChangeWithRetry(
                            conv.id,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = nextEpoch.toInt(),
                                welcomeMessages = listOf(
                                    DeviceWelcome(
                                        deviceId = device.second.deviceId,
                                        welcomeData = addResult.welcomeB64
                                    )
                                ),
                                addedUserIds = listOf(device.first)
                            )
                        )

                        mlsManager.mergePendingCommit(conv.id)
                        serverEpoch = actualEpoch
                        mlsManager.updateCommitCursor(conv.id, serverEpoch)
                        val realEpoch = mlsManager.getRealMlsEpoch(conv.id)
                        mlsManager.updateGroupEpoch(conv.id, realEpoch)
                    } catch (e: Exception) {
                        println("MemegramDebug [MLS] ❌ Ошибка добавления устройства ${device.second.deviceId}: ${e.message}")
                        try { mlsManager.clearPendingCommit(conv.id) } catch (_: Exception) {}
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

    fun startDirectChatByUserId(userId: String) {
        viewModelScope.launch {
            _isCreatingChat.value = true
            _error.value = null
            try {
                val recipientPackages = api.getKeyPackagesForUser(userId)
                    .groupBy { it.deviceId }.map { it.value.last() }

                val myUserId = mlsManager.getMyUserId()
                val myDeviceId = mlsManager.getMyDeviceId()
                val myPackages = if (myUserId.isNotBlank()) {
                    api.getKeyPackagesForUser(myUserId)
                        .filter { it.deviceId != myDeviceId }
                        .groupBy { it.deviceId }.map { it.value.last() }
                } else emptyList()

                val allPackagesToAdd = recipientPackages + myPackages
                if (allPackagesToAdd.isEmpty()) {
                    _error.value = "Не найдено устройств для создания чата"
                    return@launch
                }

                val mlsGroupId = "${userId.take(16)}_${Clock.System.now().toEpochMilliseconds()}"

                mlsManager.createEmptyGroup(mlsGroupId)

                val conv = api.createDirectConversation(
                    CreateDirectConversationRequest(
                        recipientUserId = userId,
                        welcomeMessages = emptyList()
                    )
                )

                mlsManager.bindConversation(conv.id, mlsGroupId)
                grantDirectPeerAdmin(conv.id, userId)

                var serverEpoch = conv.mlsGroup?.currentEpoch ?: 0L

                for (kp in allPackagesToAdd) {
                    try {
                        val addResult = mlsManager.addMemberToGroup(conv.id, kp.keyPackageData)
                        mlsManager.flushState()

                        val nextEpoch = serverEpoch + 1L
                        val actualEpoch = commitGroupChangeWithRetry(
                            conv.id,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = nextEpoch.toInt(),
                                welcomeMessages = listOf(DeviceWelcome(kp.deviceId, addResult.welcomeB64)),
                                addedUserIds = listOf(userId)
                            )
                        )
                        mlsManager.mergePendingCommit(conv.id)
                        serverEpoch = actualEpoch
                        mlsManager.updateCommitCursor(conv.id, serverEpoch)
                        val realEpoch = mlsManager.getRealMlsEpoch(conv.id)
                        mlsManager.updateGroupEpoch(conv.id, realEpoch)
                    } catch (e: Exception) {
                        println("MemegramDebug [MLS] ❌ Ошибка добавления устройства ${kp.deviceId}: ${e.message}")
                        try { mlsManager.clearPendingCommit(conv.id) } catch (_: Exception) {}
                    }
                }
                mlsManager.onKeyPackageConsumed()
                _chatCreated.value = conv.id
            } catch (e: ApiException) {
                if (e.isBlocked) {
                    _blockedByPeerError.value = userId.take(8)
                } else {
                    _error.value = "Не удалось создать чат: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "Не удалось создать чат: ${e.message}"
            } finally {
                _isCreatingChat.value = false
            }
        }
    }
}
