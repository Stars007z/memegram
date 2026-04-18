package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.NotificationsRepository
import com.example.memegram.mls.MlsManager
import com.example.memegram.mls.MlsManager.Companion.BATCH_KEY_PACKAGES
import com.example.memegram.utils.generateUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AddDeviceStep { SCANNING, SUBMITTING, WAITING_APPROVAL, CONFIRMED, REJECTED, ERROR }

class AddDeviceViewModel(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val mlsManager: MlsManager,
    private val keyManager: KeyManager,
    private val notificationsRepository: NotificationsRepository
) : ViewModel() {

    private val _step  = MutableStateFlow(AddDeviceStep.SCANNING)
    val step: StateFlow<AddDeviceStep> = _step.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: Job? = null

    fun onQrScanned(rawContent: String) {
        val normalized = rawContent.trim()
        val path = when {
            normalized.startsWith("memegram://add-device/") ->
                normalized.removePrefix("memegram://add-device/")
            normalized.contains("/") -> normalized
            else -> {
                _error.value = "Неверный формат кода. Ожидается 'id/код' или QR-ссылка."
                return
            }
        }
        val parts = path.split("/")
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            _error.value = "Неверный формат кода"
            return
        }
        submitDevice(registrationId = parts[0].trim(), registrationCode = parts[1].trim())
    }

    private fun submitDevice(registrationId: String, registrationCode: String) {
        viewModelScope.launch {
            _step.value = AddDeviceStep.SUBMITTING
            try {
                keyManager.getOrCreateKeyPair()
                val authPubKey = keyManager.getPublicKeyBase64()
                val localDeviceId = generateUuid()
                mlsManager.initialize()
                val creds = mlsManager.exportCredentials()

                api.submitDeviceData(
                    registrationId = registrationId,
                    request = SubmitDeviceDataRequest(
                        deviceId       = localDeviceId,
                        deviceName     = getDeviceName(),
                        identityKeyPub = authPubKey,
                        initKeyPub     = creds.initKeyPub,
                        credentialData = creds.credentialData,
                        registrationCode = registrationCode
                    )
                )

                _step.value = AddDeviceStep.WAITING_APPROVAL
                startPolling(registrationId)

            } catch (e: Exception) {
                _step.value = AddDeviceStep.ERROR
                _error.value = "Ошибка отправки данных: ${e.message}"
            }
        }
    }

    private fun startPolling(registrationId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val statusResp = api.getDeviceAdditionStatus(registrationId)
                    when (statusResp.status) {
                        "confirmed" -> {
                            val token   = statusResp.accessToken  ?: ""
                            val refresh = statusResp.refreshToken ?: ""
                            val device  = statusResp.device

                            if (token.isNotBlank() && device != null) {
                                sessionManager.save(
                                    AuthResponse(
                                        accessToken  = token,
                                        refreshToken = refresh,
                                        userId       = device.userId,
                                        deviceId     = device.clientDeviceId,
                                        expiresAt    = statusResp.tokenExpiresAt,
                                        deviceType   = "secondary"
                                    )
                                )

                                mlsManager.clearAll()
                                mlsManager.initialize()
                                val newCreds = mlsManager.exportCredentials()

                                try {
                                    api.updateDeviceKeys(
                                        deviceId = device.id,
                                        request = UpdateDeviceKeysRequest(
                                            identityKeyPub = newCreds.identityKeyPub,
                                            initKeyPub = newCreds.initKeyPub,
                                            credentialData = newCreds.credentialData
                                        )
                                    )
                                    println("AddDeviceVM ✅ Реальные ключи устройства загружены на сервер")
                                } catch (e: Exception) {
                                    println("AddDeviceVM ❌ Ошибка updateDeviceKeys: ${e.message}")
                                }

                                uploadKeyPackages()
                                notificationsRepository.registerCurrentDeviceToken()
                                _step.value = AddDeviceStep.CONFIRMED
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    println("AddDeviceVM polling error: ${e.message}")
                }
                delay(3_000)
            }
        }
    }

    private suspend fun uploadKeyPackages() {
        try {
            val packages = mlsManager.generateKeyPackages(BATCH_KEY_PACKAGES)
            api.uploadKeyPackages(packages)
            println("AddDeviceVM ✅ key packages загружены")
        } catch (e: Exception) {
            println("AddDeviceVM ⚠️ ошибка загрузки key packages: ${e.message}")
        }
    }

    fun retryScanning() {
        pollingJob?.cancel()
        _step.value = AddDeviceStep.SCANNING
        _error.value = null
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun getDeviceName(): String = try {
        getPlatform().name
    } catch (_: Throwable) {
        "Unknown Device"
    }
}