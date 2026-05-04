package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DeviceUiModel(
    val serverId: String,
    val clientDeviceId: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isCurrentDevice: Boolean,
    val lastSeen: Long
)

class LinkedDevicesViewModel(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val mlsManager: MlsManager,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _devices           = MutableStateFlow<List<DeviceUiModel>>(emptyList())
    val devices: StateFlow<List<DeviceUiModel>> = _devices.asStateFlow()

    private val _pendingAdditions  = MutableStateFlow<List<PendingDeviceRegistration>>(emptyList())
    val pendingAdditions: StateFlow<List<PendingDeviceRegistration>> = _pendingAdditions.asStateFlow()

    private val _qrPayload         = MutableStateFlow<String?>(null)
    val qrPayload: StateFlow<String?> = _qrPayload.asStateFlow()

    private val _isLoading         = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error             = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _successMessage    = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private var pollingJob: Job? = null

    val canManagePrimary: Boolean
        get() = sessionManager.getDeviceType() in setOf("primary", "admin")

    init {
        load()
        startPendingPolling()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val currentDeviceId = sessionManager.getDeviceId()
                val loadedDevices = api.getDevices().map { it.toUiModel(currentDeviceId) }
                loadedDevices.firstOrNull { it.isCurrentDevice }
                    ?.let { sessionManager.updateDeviceType(it.type) }
                _devices.value = loadedDevices
                refreshPending()
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки устройств"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshPending() {
        try { _pendingAdditions.value = api.getPendingDeviceAdditions() }
        catch (_: Exception) {}
    }

    fun initAddDevice() {
        viewModelScope.launch {
            try {
                val resp = api.initDeviceAddition()
                _qrPayload.value = "memegram://add-device/${resp.registrationId}/${resp.registrationCode}"
            } catch (e: Exception) {
                _error.value = "Не удалось создать QR: ${e.message}"
            }
        }
    }

    fun clearQr() { _qrPayload.value = null }

    fun confirmAddition(registrationId: String, confirm: Boolean) {
        viewModelScope.launch {
            try {
                val pendingDev = _pendingAdditions.value.find { it.registrationId == registrationId }
                if (confirm && pendingDev?.status != "awaiting_confirmation") {
                    _error.value = "Новое устройство ещё не отправило данные для подтверждения"
                    return@launch
                }

                val resp = api.confirmDeviceAddition(
                    registrationId,
                    ConfirmDeviceAdditionRequest(
                        confirm = confirm,
                        newDeviceName = if (confirm) pendingDev?.deviceName else null
                    )
                )

                if (confirm) {
                    val addedChats = addNewDeviceToAllMlsGroups(
                        newDeviceId = resp.newDeviceId,
                        userId      = resp.userId
                    )
                    _successMessage.value = "Устройство добавлено в $addedChats чатов"
                }
                refreshPending()
                load()
            } catch (e: Exception) {
                _error.value = "Ошибка подтверждения: ${e.message}"
            }
        }
    }

    private suspend fun addNewDeviceToAllMlsGroups(newDeviceId: String, userId: String): Int {
        println("LinkedDevicesVM НАЧАЛО добавления устройства $newDeviceId во все чаты")

        var keyPackageData: String? = null
        var attempts = 0

        while (attempts < 15 && keyPackageData == null) {
            try {
                println("LinkedDevicesVM ⏳ Попытка ${attempts + 1}: Запрашиваем ключи для $userId...")
                val packages = api.getKeyPackagesForUser(userId)

                keyPackageData = packages.find { it.deviceId == newDeviceId }?.keyPackageData

                if (keyPackageData != null) {
                    println("LinkedDevicesVM ✅ НАЙДЕН key package нового устройства! (размер: ${keyPackageData.length} байт)")
                }
            } catch (e: Exception) {
                println("LinkedDevicesVM ❌ Ошибка запроса ключей: ${e.message}")
            }

            if (keyPackageData == null) {
                delay(2000)
                attempts++
            }
        }

        if (keyPackageData == null) {
            println("LinkedDevicesVM 🚨 КРИТИЧЕСКАЯ ОШИБКА: Ключи для нового устройства так и не появились на сервере за 30 сек!")
            _error.value = "Устройство добавлено, но MLS-ключи не появились. Откройте список устройств позже и повторите синхронизацию."
            return 0
        }

        println("LinkedDevicesVM 📥 Запрашиваем актуальный список чатов с бэкенда...")
        try {
            val conversations = loadAllConversations()
            println("LinkedDevicesVM 💬 Найдено чатов для обработки: ${conversations.size}")

            if (conversations.isEmpty()) {
                println("LinkedDevicesVM ⚠️ У пользователя нет чатов, Welcome-сообщения создавать не для чего.")
                return 0
            }

            var addedChats = 0
            conversations.forEach { chat ->
                val convId = chat.id
                println("LinkedDevicesVM ⚙️ Обработка чата $convId...")

                if (!mlsManager.hasGroup(convId)) {
                    println("LinkedDevicesVM ⏭️ Пропускаем $convId: у нас нет локальных ключей от этого чата")
                    return@forEach
                }

                try {
                    val addResult = mlsManager.addMemberToGroup(convId, keyPackageData)
                    mlsManager.rememberDeviceSignatureKey(newDeviceId, addResult.memberSignatureKeyB64)
                    mlsManager.flushState()
                    val serverEpoch = mlsManager.getCommitCursor(convId)
                        ?: runCatching { api.getConversation(convId).mlsGroup?.currentEpoch }.getOrNull()
                        ?: mlsManager.getGroupEpoch(convId)

                    println("LinkedDevicesVM 📤 Отправляем Commit+Welcome на сервер (epoch = ${serverEpoch + 1})...")
                    val committedEpoch = commitGroupChangeWithRetry(
                        convId,
                        CommitGroupChangeRequest(
                            commitData      = addResult.commitB64,
                            newEpoch        = (serverEpoch + 1).toInt(),
                            welcomeMessages = listOf(
                                DeviceWelcome(deviceId = newDeviceId, welcomeData = addResult.welcomeB64)
                            )
                        )
                    )
                    mlsManager.mergePendingCommit(convId)
                    mlsManager.updateCommitCursor(convId, committedEpoch)
                    val realEpoch = mlsManager.getRealMlsEpoch(convId)
                    mlsManager.updateGroupEpoch(convId, realEpoch)
                    addedChats++
                    println("LinkedDevicesVM ✅ УСПЕХ! Новое устройство добавлено в чат $convId")
                } catch (e: Exception) {
                    try { mlsManager.clearPendingCommit(convId) } catch (_: Exception) {}
                    println("LinkedDevicesVM ❌ ОШИБКА при добавлении в $convId: ${e.message}")
                }
            }
            println("LinkedDevicesVM ✅ ПРОЦЕСС ДОБАВЛЕНИЯ ПОЛНОСТЬЮ ЗАВЕРШЕН!")
            if (addedChats < conversations.count { mlsManager.hasGroup(it.id) }) {
                _error.value = "Устройство добавлено не во все локальные чаты. Часть чатов синхронизируется при следующем изменении MLS."
            }
            return addedChats

        } catch (e: Exception) {
            println("LinkedDevicesVM ❌ Ошибка при получении списка чатов: ${e.message}")
            _error.value = "Устройство добавлено, но список чатов не удалось синхронизировать: ${e.message}"
            return 0
        }
    }

    private suspend fun loadAllConversations(): List<com.example.memegram.data.models.ConversationSummary> {
        val result = mutableListOf<com.example.memegram.data.models.ConversationSummary>()
        var cursor = ""
        do {
            val page = api.getConversations(limit = 100, cursor = cursor)
            result += page.items
            cursor = page.nextCursor
        } while (cursor.isNotBlank())
        return result
    }

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
                api.commitGroupChange(convId, request.copy(newEpoch = expected.toInt()))
                expected
            } else {
                throw e
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                api.revokeDevice(deviceId, RevokeDeviceRequest())
                _successMessage.value = "Устройство отозвано"
                load()
            } catch (e: Exception) {
                _error.value = "Ошибка отзыва: ${e.message}"
            }
        }
    }

    fun transferPrimary(deviceId: String) {
        viewModelScope.launch {
            try {
                val response = api.transferPrimary(deviceId)
                if (!response.success) {
                    _error.value = response.message
                    return@launch
                }
                if (sessionManager.getDeviceType() != "admin") {
                    sessionManager.updateDeviceType("secondary")
                }
                _successMessage.value = "Основное устройство передано"
                load()
            } catch (e: Exception) {
                _error.value = "Ошибка передачи основного устройства: ${e.message}"
            }
        }
    }

    fun clearError()   { _error.value = null }
    fun clearSuccess() { _successMessage.value = null }

    private fun startPendingPolling() {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refreshPending()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun DeviceInfoResponse.toUiModel(currentDeviceId: String?) = DeviceUiModel(
        serverId          = id,
        clientDeviceId    = clientDeviceId,
        name              = deviceName,
        type              = deviceType,
        isActive          = isActive,
        isCurrentDevice   = id == currentDeviceId,
        lastSeen          = lastSeen
    )
}
