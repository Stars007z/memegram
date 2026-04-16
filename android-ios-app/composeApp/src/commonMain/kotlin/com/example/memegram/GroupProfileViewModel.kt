package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.CommitGroupChangeRequest
import com.example.memegram.data.models.DeviceWelcome
import com.example.memegram.data.models.InitiateItemUploadRequest
import com.example.memegram.data.models.LeaveConversationRequest
import com.example.memegram.data.models.UpdateGroupAvatarRequest
import com.example.memegram.data.models.UpdateGroupNameRequest
import com.example.memegram.data.models.UserProfileResponse
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class GroupMemberUI(
    val user: UserProfileResponse,
    val role: String
)

class GroupProfileViewModel(
    private val api: ApiService,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _members = MutableStateFlow<List<GroupMemberUI>>(emptyList())

    private val _myRole = MutableStateFlow("member")
    val myRole: StateFlow<String> = _myRole.asStateFlow()

    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()

    private val _groupAvatarMediaId = MutableStateFlow<String?>(null)
    val groupAvatarMediaId: StateFlow<String?> = _groupAvatarMediaId.asStateFlow()

    val currentUserId: String
        get() = sessionManager.getUserId() ?: ""

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredMembers: StateFlow<List<GroupMemberUI>> = combine(_members, _searchQuery) { list, query ->
        if (query.isBlank()) list
        else list.filter { it.user.username?.contains(query, ignoreCase = true) == true }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadGroup(conversationId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val conv = api.getConversation(conversationId)
                _groupName.value = conv.name ?: ""
                _groupAvatarMediaId.value = conv.avatarMediaId?.takeIf { it.isNotBlank() }

                val loadedMembers = mutableListOf<GroupMemberUI>()

                for (member in conv.members) {
                    try {
                        val profile = api.getUserById(member.userId)
                        loadedMembers.add(GroupMemberUI(profile, member.role))
                    } catch (e: Exception) {
                        println("MemegramDebug [GroupProfile]: ❌ Не удалось загрузить юзера ${member.userId}: ${e.message}")
                    }
                }
                _members.value = loadedMembers
                val myId = currentUserId
                val myMember = conv.members.find { it.userId == myId }
                _myRole.value = myMember?.role ?: "member"
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки группы: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private suspend fun syncGroupState(conversationId: String): Long {
        try {
            val realEpoch = mlsManager.getRealMlsEpoch(conversationId)
            val localEpoch = if (realEpoch >= 0) realEpoch else mlsManager.getGroupEpoch(conversationId)
            val commits = api.getPendingCommits(conversationId, localEpoch)

            val newCommits = commits.filter { it.epoch > localEpoch }

            if (newCommits.isNotEmpty()) {
                newCommits.sortedBy { it.epoch }.forEach { commit ->
                    val success = try {
                        mlsManager.processCommit(conversationId, commit.commitDataB64)
                    } catch (_: Exception) { false }

                    if (success) {
                        val newRealEpoch = mlsManager.getRealMlsEpoch(conversationId)
                        if (newRealEpoch > 0) {
                            mlsManager.updateGroupEpoch(conversationId, newRealEpoch)
                        } else {
                            mlsManager.updateGroupEpoch(conversationId, commit.epoch)
                        }
                    }
                }
                mlsManager.flushState()
            }
        } catch (e: Exception) {
            println("MemegramDebug [Sync]: ❌ Ошибка синхронизации: ${e.message}")
        }
        val finalEpoch = mlsManager.getRealMlsEpoch(conversationId)
        return if (finalEpoch >= 0) finalEpoch else mlsManager.getGroupEpoch(conversationId)
    }

    fun addMemberByUserId(conversationId: String, userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                var serverEpochCounter = syncGroupState(conversationId)

                val packages = api.getKeyPackagesForUser(userId).distinctBy { it.deviceId }

                if (packages.isEmpty()) throw Exception("У пользователя нет устройств (KeyPackages не найдены)")

                for (kp in packages) {
                    try { mlsManager.flushState() } catch (_: Exception) {}


                    val addResult = mlsManager.addMemberToGroup(conversationId, kp.keyPackageData)
                    mlsManager.flushState()

                    val nextServerEpoch = (serverEpochCounter + 1).toInt()

                    try {
                        api.commitGroupChange(
                            conversationId,
                            CommitGroupChangeRequest(
                                commitData = addResult.commitB64,
                                newEpoch = nextServerEpoch,
                                welcomeMessages = listOf(DeviceWelcome(kp.deviceId, addResult.welcomeB64)),
                                addedUserIds = listOf(userId)
                            )
                        )

                        mlsManager.mergePendingCommit(conversationId)
                        serverEpochCounter++
                        mlsManager.updateGroupEpoch(conversationId, serverEpochCounter)

                    } catch (networkError: Exception) {
                        mlsManager.clearPendingCommit(conversationId)
                        throw networkError
                    }
                }
                loadGroup(conversationId)

            } catch (e: Exception) {
                val errorMsg = e.message ?: "Неизвестная ошибка"
                println("MemegramDebug [AddMember]: ❌ ОШИБКА: $errorMsg")
                _error.value = "Не удалось добавить: $errorMsg"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun kickMember(conversationId: String, targetUserId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.kickMember(conversationId, targetUserId)

                if (mlsManager.hasGroup(conversationId)) {
                    try {
                        val serverEpochCounter = syncGroupState(conversationId)

                        val commitB64 = mlsManager.removeMember(conversationId, targetUserId)
                        mlsManager.flushState()

                        val nextServerEpoch = (serverEpochCounter + 1).toInt()

                        api.commitGroupChange(
                            conversationId,
                            CommitGroupChangeRequest(
                                commitData = commitB64,
                                newEpoch = nextServerEpoch,
                                removedDeviceIds = emptyList()
                            )
                        )

                        mlsManager.mergePendingCommit(conversationId)
                        mlsManager.updateGroupEpoch(conversationId, nextServerEpoch.toLong())
                        mlsManager.flushState()

                        println("MemegramDebug [Kick]: ✅ MLS Remove Commit sent, epoch=$nextServerEpoch")
                    } catch (mlsError: Exception) {
                        println("MemegramDebug [Kick]: ⚠️ MLS Remove Commit failed (kick still valid): ${mlsError.message}")
                        try { mlsManager.clearPendingCommit(conversationId) } catch (_: Exception) {}
                    }
                }

                loadGroup(conversationId)
            } catch (e: Exception) {
                _error.value = "Ошибка при удалении участника: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateMemberRole(conversationId: String, targetUserId: String, newRole: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.updateMemberRole(conversationId, targetUserId, newRole)
                loadGroup(conversationId)
            } catch (e: Exception) {
                _error.value = "Ошибка при изменении роли: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateGroupName(conversationId: String, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                api.updateGroupName(conversationId, UpdateGroupNameRequest(name = newName))
                _groupName.value = newName
            } catch (e: Exception) {
                _error.value = "Failed to update group name: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateGroupAvatar(conversationId: String, bytes: ByteArray) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val mediaId = uploadImageToItemStorage(bytes, "avatar", "image/jpeg")
                api.updateGroupAvatar(conversationId, UpdateGroupAvatarRequest(avatarMediaId = mediaId))
                _groupAvatarMediaId.value = mediaId
            } catch (e: Exception) {
                _error.value = "Failed to update group avatar: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun uploadImageToItemStorage(
        bytes: ByteArray,
        itemType: String,
        mimeType: String
    ): String {
        val initiateResp = api.initiateItemUpload(
            InitiateItemUploadRequest(
                itemType = itemType,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong()
            )
        )
        api.uploadBytesToPresignedUrl(initiateResp.uploadUrl, bytes, mimeType)
        val confirmResp = api.confirmItemUpload(initiateResp.itemId)
        if (!confirmResp.success) {
            throw Exception("Upload confirmation failed for item ${initiateResp.itemId}")
        }
        return initiateResp.itemId
    }

    fun leaveGroup(conversationId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                api.leaveConversation(conversationId, LeaveConversationRequest())

                try { mlsManager.deleteLocalGroup(conversationId) } catch (_: Exception) {}

                chatRepository.deleteChat(conversationId)
                mlsManager.flushState()
                onSuccess()
            } catch (e: Exception) {
                _error.value = "Ошибка при выходе: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}