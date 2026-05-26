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

    private val _pendingChatContact = MutableStateFlow<String?>(null)
    private val _pendingChatAvatarMediaId = MutableStateFlow<String?>(null)

    private suspend fun createDirectChat(
        peerUserId: String,
        missingDeviceMessage: String,
    ): String {
        val recipientPackages = api.getKeyPackagesForUser(peerUserId)
            .groupBy { it.deviceId }
            .map { it.value.last() }
        if (recipientPackages.isEmpty()) throw IllegalStateException(missingDeviceMessage)

        val packagesToAdd = recipientPackages.distinctBy { it.deviceId }
        val mlsGroupId = "direct_${generateUuid()}"

        val initialGroup = try {
            mlsManager.prepareInitialGroup(mlsGroupId, packagesToAdd)
        } catch (e: Exception) {
            runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
            throw e
        }

        val conv = try {
            api.createDirectConversation(
                CreateDirectConversationRequest(
                    recipientUserId = peerUserId,
                    welcomeMessages = initialGroup.welcomeMessages
                )
            )
        } catch (e: Exception) {
            runCatching { mlsManager.clearPendingCommitForGroupId(mlsGroupId) }
            runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
            throw e
        }

        if (mlsManager.hasGroup(conv.id, log = false)) {
            runCatching { mlsManager.clearPendingCommitForGroupId(mlsGroupId) }
            runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
            return conv.id
        }

        mlsManager.bindConversation(conv.id, mlsGroupId)
        initialGroup.deviceSignatureKeys.forEach { (deviceId, signatureKey) ->
            mlsManager.rememberDeviceSignatureKey(deviceId, signatureKey)
        }
        mlsManager.mergePendingCommit(conv.id)
        val realEpoch = mlsManager.getRealMlsEpoch(conv.id)
        mlsManager.updateCommitCursor(conv.id, realEpoch)
        mlsManager.updateGroupEpoch(conv.id, realEpoch)
        mlsManager.flushState()
        grantDirectPeerAdmin(conv.id, peerUserId)

        return conv.id
    }

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
                _chatCreated.value = createDirectChat(
                    peerUserId = entry.contactUserId,
                    missingDeviceMessage = "Не найдено устройств для создания чата"
                )

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

                val invitedDevices = mutableListOf<Pair<String, UserDeviceKeyPackage>>()

                for (userId in usersToInvite) {
                    try {
                        val packages = api.getKeyPackagesForUser(userId)
                            .groupBy { it.deviceId }.map { it.value.last() }
                        packages.forEach { invitedDevices.add(userId to it) }
                    } catch (_: Exception) {}
                }
                if (myUserId.isNotBlank()) {
                    runCatching {
                        api.getKeyPackagesForUser(myUserId)
                            .filter { it.deviceId != myDeviceId }
                            .groupBy { it.deviceId }.map { it.value.last() }
                    }.getOrDefault(emptyList())
                        .forEach { invitedDevices.add(myUserId to it) }
                }

                if (invitedDevices.isEmpty()) {
                    _error.value = "Не найдено устройств для добавления в группу"
                    return@launch
                }
                val usersWithKeyPackages = invitedDevices.map { it.first }.toSet()
                val usersWithoutKeyPackages = usersToInvite.filter { it !in usersWithKeyPackages }
                if (usersWithoutKeyPackages.isNotEmpty()) {
                    _error.value = "Не найдено устройств для некоторых участников"
                    return@launch
                }

                val mlsGroupId = "group_${generateUuid()}"

                val initialGroup = try {
                    mlsManager.prepareInitialGroup(mlsGroupId, invitedDevices.map { it.second })
                } catch (e: Exception) {
                    runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
                    throw e
                }

                val welcomesByDeviceId = initialGroup.welcomeMessages.associateBy { it.deviceId }
                val memberUserIds = (usersToInvite + myUserId.takeIf { it.isNotBlank() }).filterNotNull().distinct()
                val membersList = memberUserIds.mapNotNull { uid ->
                    val welcomes = invitedDevices
                        .filter { it.first == uid }
                        .mapNotNull { welcomesByDeviceId[it.second.deviceId] }
                    if (welcomes.isEmpty()) null else MemberWelcomes(userId = uid, welcomes = welcomes)
                }

                val conv = try {
                    api.createGroupConversation(
                        CreateGroupConversationRequest(
                            name = groupName,
                            members = membersList
                        )
                    )
                } catch (e: Exception) {
                    runCatching { mlsManager.clearPendingCommitForGroupId(mlsGroupId) }
                    runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
                    throw e
                }

                try {
                    mlsManager.bindConversation(conv.id, mlsGroupId)
                    initialGroup.deviceSignatureKeys.forEach { (deviceId, signatureKey) ->
                        mlsManager.rememberDeviceSignatureKey(deviceId, signatureKey)
                    }
                    mlsManager.mergePendingCommit(conv.id)
                    val realEpoch = mlsManager.getRealMlsEpoch(conv.id)
                    mlsManager.updateCommitCursor(conv.id, realEpoch)
                    mlsManager.updateGroupEpoch(conv.id, realEpoch)
                    mlsManager.flushState()
                } catch (e: Exception) {
                    runCatching { api.deleteConversation(conv.id) }
                    runCatching { mlsManager.clearPendingCommit(conv.id) }
                    runCatching { mlsManager.deleteLocalGroup(conv.id) }
                    runCatching { mlsManager.clearPendingCommitForGroupId(mlsGroupId) }
                    runCatching { mlsManager.deleteUnboundGroup(mlsGroupId) }
                    throw e
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
                _chatCreated.value = createDirectChat(
                    peerUserId = userId,
                    missingDeviceMessage = "Не найдено устройств для создания чата"
                )
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
