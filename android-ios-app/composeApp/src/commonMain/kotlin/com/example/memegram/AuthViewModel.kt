package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.auth.SessionRefresher
import com.example.memegram.auth.SessionState
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.models.*
import com.example.memegram.data.repository.NotificationsRepository
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.io.encoding.ExperimentalEncodingApi

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val keyManager: KeyManager,
    private val mlsManager: MlsManager,
    private val notificationsRepository: NotificationsRepository,
    private val sessionRefresher: SessionRefresher,
) : ViewModel() {

    private val _localOverride = MutableStateFlow<AuthState?>(null)

    val uiState: StateFlow<AuthState> = combine(
        sessionRefresher.state,
        _localOverride,
    ) { session, override ->
        override ?: session.toAuthState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = _localOverride.value ?: sessionRefresher.state.value.toAuthState()
    )

    private fun SessionState.toAuthState(): AuthState = when (this) {
        is SessionState.Authenticated -> AuthState.Success
        is SessionState.Refreshing -> AuthState.Loading
        is SessionState.Failed -> AuthState.Error(message)
        is SessionState.NoCredentials, is SessionState.Unknown -> AuthState.Idle
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun register(username: String, inviteCode: String) {
        viewModelScope.launch {
            _localOverride.value = AuthState.Loading
            var sessionSaved = false
            try {
                mlsManager.clearAll()
                keyManager.getOrCreateKeyPair()
                val pubKey = keyManager.getPublicKeyBase64()
                val deviceId = getHardwareDeviceId()
                val req = RegisterRequest(
                    username = username,
                    inviteCode = inviteCode,
                    deviceId = deviceId,
                    deviceName = getDeviceModelName(),
                    identityKeyPub = pubKey,
                    initKeyPub = pubKey,
                    credentialData = pubKey
                )
                val result = api.register(req)
                sessionManager.save(result)
                sessionSaved = true
                initMlsAndUploadKeys()
                notificationsRepository.registerCurrentDeviceToken()
                sessionRefresher.markAuthenticated()
                _localOverride.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (sessionSaved) {
                    sessionManager.clearAuth()
                    mlsManager.clearAll()
                    sessionRefresher.markNoCredentials()
                }
                _localOverride.value = AuthState.Error(e.message ?: "Ошибка регистрации")
            }
        }
    }
    fun clearError() {
        if (_localOverride.value is AuthState.Error) _localOverride.value = null
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
                api.uploadKeyPackages(packages, mlsManager.getOwnSignaturePublicKeyB64())
                sessionManager.clearPendingKpCleanup()
            }
        }
    }
}
