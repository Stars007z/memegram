package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.models.CommitGroupChangeRequest
import com.example.memegram.data.models.DeviceWelcome
import com.example.memegram.data.models.LeaveConversationRequest
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
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _members = MutableStateFlow<List<GroupMemberUI>>(emptyList())

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
            // Используем реальную MLS-эпоху вместо metadata для точной синхронизации
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
                        // Берём реальную MLS-эпоху после применения commit'а
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
        // Возвращаем реальную MLS-эпоху
        val finalEpoch = mlsManager.getRealMlsEpoch(conversationId)
        return if (finalEpoch >= 0) finalEpoch else mlsManager.getGroupEpoch(conversationId)
    }

    fun addMemberByUserId(conversationId: String, userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                var serverEpochCounter = syncGroupState(conversationId)

                // 🔥 ИСПРАВЛЕНИЕ: Берем .last(), чтобы получить свежий KeyPackage,
                // если бекенд забыл удалить уже использованные старые ключи.
                val packages = api.getKeyPackagesForUser(userId).distinctBy { it.deviceId }

                if (packages.isEmpty()) throw Exception("У пользователя нет устройств (KeyPackages не найдены)")

                for (kp in packages) {
                    try { mlsManager.flushState() } catch (_: Exception) {}

                    // clearPendingCommit и clearPendingProposals теперь внутри addMemberToGroup

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

    fun leaveGroup(conversationId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val serverEpochCounter = syncGroupState(conversationId)
                val commitDataB64 = mlsManager.leaveGroup(conversationId)
                val nextServerEpoch = (serverEpochCounter + 1).toInt()

                try {
                    api.commitGroupChange(
                        conversationId,
                        CommitGroupChangeRequest(
                            commitData = commitDataB64,
                            newEpoch = nextServerEpoch,
                            removedDeviceIds = listOf(mlsManager.getMyDeviceId())
                        )
                    )
                    api.leaveConversation(conversationId, LeaveConversationRequest(commitDataB64))

                    chatRepository.deleteChat(conversationId)
                    mlsManager.flushState()
                    onSuccess()
                } catch (networkError: Exception) {
                    mlsManager.clearPendingCommit(conversationId)
                    throw networkError
                }
            } catch (e: Exception) {
                _error.value = "Ошибка при выходе: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}