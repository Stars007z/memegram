package com.example.memegram.auth

import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.LoginCompleteRequest
import com.example.memegram.data.models.LoginInitRequest
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.repository.NotificationsRepository
import com.example.memegram.getHardwareDeviceId
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed class SessionState {
    object Unknown : SessionState()
    object NoCredentials : SessionState()
    object Authenticated : SessionState()
    object Refreshing : SessionState()
    data class Failed(val message: String) : SessionState()
}

class SessionRefresher(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val keyManager: KeyManager,
    private val mlsManager: MlsManager,
    private val notificationsRepository: NotificationsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var inFlight: Job? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun refreshIfNeeded() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch {
            mutex.withLock { doRefresh() }
        }
    }

    fun markAuthenticated() {
        _state.value = SessionState.Authenticated
    }

    fun markNoCredentials() {
        _state.value = SessionState.NoCredentials
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun doRefresh() {
        val hasKp = keyManager.hasKeyPair()
        val isLogged = sessionManager.isLoggedIn
        println("MemegramDebug [SessionRefresher] doRefresh: hasKeyPair=$hasKp, isLoggedIn=$isLogged")
        if (!hasKp || !isLogged) {
            _state.value = SessionState.NoCredentials
            return
        }
        val expired = sessionManager.isTokenExpired
        println("MemegramDebug [SessionRefresher] tokenExpired=$expired")
        if (!expired) {
            _state.value = SessionState.Authenticated
            return
        }
        _state.value = SessionState.Refreshing
        try {
            val deviceId = sessionManager.getDeviceId() ?: getHardwareDeviceId()
            val initResp = api.loginInit(LoginInitRequest(deviceId = deviceId))
            val signatureBytes = keyManager.signChallenge(initResp.challenge)
            val signatureBase64 = Base64.encode(signatureBytes)
            val result = api.loginComplete(
                LoginCompleteRequest(
                    deviceId = deviceId,
                    challenge = initResp.challenge,
                    signature = signatureBase64,
                    deviceName = "KMP Device",
                )
            )
            sessionManager.save(result)
            initMlsAndUploadKeys()
            notificationsRepository.registerCurrentDeviceToken()
            _state.value = SessionState.Authenticated
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Ошибка входа"
            if (errorMsg.contains("422") ||
                errorMsg.contains("Device not found") ||
                errorMsg.contains("401")
            ) {
                sessionManager.clear()
                sessionManager.clearDeviceId()
                mlsManager.clearAll()
                _state.value = SessionState.Failed(
                    "Аккаунт не найден на сервере. Зарегистрируйтесь заново."
                )
            } else {
                _state.value = SessionState.Failed(errorMsg)
            }
        }
    }

    private suspend fun initMlsAndUploadKeys() {
        mlsManager.initialize()

        if (sessionManager.hasPendingKpCleanup()) {
            try {
                val deleted = api.deleteMyKeyPackages()
                sessionManager.clearPendingKpCleanup()
                println("MemegramDebug [MLS] Pending KP cleanup retried: $deleted deleted")
            } catch (e: Exception) {
                println("MemegramDebug [MLS] Pending KP cleanup still failing: ${e.message}")
            }
        }

        if (mlsManager.needsKeyPackages()) {
            val countOnServer = runCatching { api.getKeyPackagesCount() }.getOrDefault(0)
            if (countOnServer < MlsManager.MIN_KEY_PACKAGES) {
                val packages = mlsManager.generateKeyPackages(MlsManager.BATCH_KEY_PACKAGES)
                mlsManager.flushState()
                api.uploadKeyPackages(packages)
                sessionManager.clearPendingKpCleanup()
            }
        }
    }
}
