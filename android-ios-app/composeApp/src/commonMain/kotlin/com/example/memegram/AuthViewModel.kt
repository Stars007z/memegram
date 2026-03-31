package com.example.memegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.models.*
import com.example.memegram.mls.MlsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
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
    private val mlsManager: MlsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthState>(AuthState.Idle)
    val uiState: StateFlow<AuthState> = _uiState.asStateFlow()

    init {
        checkAutoLogin()
    }

    private fun checkAutoLogin() {
        if (!keyManager.hasKeyPair() || !sessionManager.isLoggedIn) return
        if (!sessionManager.isTokenExpired) {
            _uiState.value = AuthState.Success
            return
        }
        login()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun login() {
        viewModelScope.launch {
            _uiState.value = AuthState.Loading
            try {
                val deviceId = sessionManager.getDeviceId()
                    ?: run {
                        _uiState.value = AuthState.Error("Устройство не зарегистрировано.")
                        return@launch
                    }
                val initResp = api.loginInit(LoginInitRequest(deviceId = deviceId))
                val signatureBytes = keyManager.signChallenge(initResp.challenge)
                val signatureBase64 = Base64.encode(signatureBytes)
                val result = api.loginComplete(
                    LoginCompleteRequest(
                        deviceId = deviceId,
                        challenge = initResp.challenge,
                        signature = signatureBase64,
                        deviceName = "KMP Device"
                    )
                )
                sessionManager.save(result)

                initMlsAndUploadKeys()

                _uiState.value = AuthState.Success
            } catch (e: Exception) {
                _uiState.value = AuthState.Error(e.message ?: "Ошибка входа")
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun register(username: String, inviteCode: String) {
        viewModelScope.launch {
            _uiState.value = AuthState.Loading
            try {
                mlsManager.clearAll()

                keyManager.getOrCreateKeyPair()
                val pubKey = keyManager.getPublicKeyBase64()
                val deviceId = getHardwareDeviceId()
                val req = RegisterRequest(
                    username = username,
                    inviteCode = inviteCode,
                    deviceId = deviceId,
                    deviceName = "KMP Device",
                    identityKeyPub = pubKey,
                    initKeyPub = pubKey,
                    credentialData = pubKey
                )
                val result = api.register(req)
                sessionManager.save(result)
                initMlsAndUploadKeys()

                _uiState.value = AuthState.Success
            } catch (e: Exception) {
                _uiState.value = AuthState.Error(e.message ?: "Ошибка регистрации")
            }
        }
    }

    private suspend fun initMlsAndUploadKeys() {
        mlsManager.initialize()

        if (mlsManager.needsKeyPackages()) {
            val countOnServer = runCatching { api.getKeyPackagesCount() }.getOrDefault(0)
            if (countOnServer < MlsManager.MIN_KEY_PACKAGES) {
                val packages = mlsManager.generateKeyPackages(MlsManager.BATCH_KEY_PACKAGES)
                mlsManager.flushState()
                api.uploadKeyPackages(packages)
            }
        }
    }
}