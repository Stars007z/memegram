package com.example.memegram.data.network

import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ApiService(
    private val client: HttpClient,
    private val sessionManager: SessionManager,
    private val baseUrl: String
) {
    private fun token() = sessionManager.getAccessToken() ?: ""

// ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun register(body: RegisterRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("Регистрация: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun loginInit(body: LoginInitRequest): LoginInitResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-init") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("LoginInit: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun loginComplete(body: LoginCompleteRequest): AuthResponse {
        val response = client.post("$baseUrl/api/v1/auth/login-complete") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("LoginComplete: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun logout(body: LogoutRequest): LogoutResponse {
        val response = client.post("$baseUrl/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess())
            throw Exception("Logout: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

// ── User ──────────────────────────────────────────────────────────────────

    suspend fun getMe(): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/me") { bearerAuth(token()) }.body()

    suspend fun updateMe(request: UpdateProfileRequest): UserProfileResponse =
        client.patch("$baseUrl/api/v1/user/me") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getMySettings(): UserSettingsResponse =
        client.get("$baseUrl/api/v1/user/me/settings") { bearerAuth(token()) }.body()

    suspend fun updateMySettings(request: UpdateSettingsRequest): UserSettingsResponse =
        client.patch("$baseUrl/api/v1/user/me/settings") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deleteMe() {
        val response = client.delete("$baseUrl/api/v1/user/me") { bearerAuth(token()) }
        if (response.status.value >= 500)
            throw Exception("deleteMe: ${response.status.value} ${response.bodyAsText()}")
    }

    suspend fun getUserById(userId: String): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/$userId") { bearerAuth(token()) }.body()

    suspend fun getUserByPublicKey(key: String): UserProfileResponse =
        client.get("$baseUrl/api/v1/user/by-key/$key") { bearerAuth(token()) }.body()

// ── Contacts ──────────────────────────────────────────────────────────────

    suspend fun getContacts(limit: Int = 50, offset: Int = 0): ContactsListResponse =
        client.get("$baseUrl/api/v1/contacts") {
            bearerAuth(token())
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun addContact(request: AddContactRequest): ContactEntry {
        val response = client.post("$baseUrl/api/v1/contacts") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess())
            throw Exception("addContact: ${response.status.value} — ${response.bodyAsText()}")
        return response.body<Map<String, ContactEntry>>()["contact"]
            ?: throw Exception("addContact: empty response")
    }

    suspend fun removeContact(contactUserId: String): RemoveContactResponse =
        client.delete("$baseUrl/api/v1/contacts/$contactUserId") { bearerAuth(token()) }.body()

    suspend fun updateContact(contactUserId: String, request: UpdateContactRequest): ContactEntry {
        val response = client.patch("$baseUrl/api/v1/contacts/$contactUserId") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess())
            throw Exception("updateContact: ${response.status.value} — ${response.bodyAsText()}")
        return response.body<Map<String, ContactEntry>>()["contact"]
            ?: throw Exception("updateContact: empty response")
    }

    suspend fun getBlockedUsers(limit: Int = 50, offset: Int = 0): BlockedUsersListResponse =
        client.get("$baseUrl/api/v1/contacts/blocked") {
            bearerAuth(token())
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()

    suspend fun blockUser(request: BlockUserRequest): BlockUserResponse {
        val response = client.post("$baseUrl/api/v1/contacts/blocked") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess())
            throw Exception("blockUser: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun unblockUser(blockedUserId: String): UnblockUserResponse =
        client.delete("$baseUrl/api/v1/contacts/blocked/$blockedUserId") { bearerAuth(token()) }.body()

// ── Messaging ─────────────────────────────────────────────────────────────

    suspend fun getConversations(limit: Int = 50, cursor: String = ""): GetConversationsResponse =
        client.get("$baseUrl/api/v1/messaging/conversations") {
            bearerAuth(token())
            parameter("limit", limit)
            parameter("cursor", cursor)
        }.body()

    suspend fun getConversation(id: String): ConversationResponse =
        client.get("$baseUrl/api/v1/messaging/conversations/$id") { bearerAuth(token()) }.body()

    suspend fun getMessages(conversationId: String, limit: Int = 50): List<MessageResponse> {
        val response = client.get("$baseUrl/api/v1/messaging/conversations/$conversationId/messages") {
            header("Authorization", "Bearer ${sessionManager.getAccessToken()}")
            parameter("limit", limit)
        }.body<GetMessagesResponse>()
        return response.messages
    }

    suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequest
    ): SendMessageResponse =
        client.post("$baseUrl/api/v1/messaging/conversations/$conversationId/messages") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun markAsRead(conversationId: String, request: MarkAsReadRequest) {
        client.post("$baseUrl/api/v1/messaging/conversations/$conversationId/read") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun getPendingWelcomes(): List<WelcomeResponse> =
        client.get("$baseUrl/api/v1/messaging/welcomes") {
            bearerAuth(token())
        }.body<WelcomesEnvelope>().items

    suspend fun ackWelcome(welcomeId: String) {
        client.post("$baseUrl/api/v1/messaging/welcomes/$welcomeId/ack") { bearerAuth(token()) }
    }

    suspend fun getPendingCommits(conversationId: String, sinceEpoch: Long? = null): List<CommitResponse> =
        client.get("$baseUrl/api/v1/messaging/conversations/$conversationId/mls/commits") {
            bearerAuth(token())
            if (sinceEpoch != null) parameter("since_epoch", sinceEpoch)
        }.body<CommitsEnvelope>().commits

    suspend fun getKeyPackagesCount(): Int =
        client.get("$baseUrl/api/v1/messaging/key-packages/count") {
            bearerAuth(token())
        }.body<Map<String, Int>>()["count"] ?: 0

    suspend fun uploadKeyPackages(packagesB64: List<String>) {
        client.post("$baseUrl/api/v1/messaging/key-packages") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(mapOf("key_packages" to packagesB64))
        }
    }

    suspend fun setTyping(conversationId: String) {
        client.post("$baseUrl/api/v1/messaging/typing") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(mapOf("conversation_id" to conversationId, "is_typing" to true))
        }
    }

    fun subscribeToConversation(conversationIds: String): Flow<SseEvent> = flow {
        client.prepareGet("$baseUrl/api/v1/messaging/events") {
            bearerAuth(token())
            parameter("conversation_ids", conversationIds)
            accept(ContentType.Text.EventStream)

            timeout {
                socketTimeoutMillis  = Long.MAX_VALUE
                requestTimeoutMillis = Long.MAX_VALUE
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val eventBuffer = StringBuilder()

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                when {
                    line.isEmpty() -> {
                        val buffered = eventBuffer.toString().trim()
                        eventBuffer.clear()
                        if (buffered.isNotEmpty()) {
                            val dataLine = buffered.lines()
                                .firstOrNull { it.startsWith("data:") }
                                ?: continue
                            val data = dataLine.removePrefix("data:").trim()
                            if (data.isNotEmpty()) {
                                try { emit(Json.decodeFromString<SseEvent>(data)) }
                                catch (_: Exception) {}
                            }
                        }
                    }

                    line.startsWith("data:") && eventBuffer.isEmpty() -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty()) {
                            try { emit(Json.decodeFromString<SseEvent>(data)) }
                            catch (_: Exception) { eventBuffer.appendLine(line) }
                        }
                    }

                    line.startsWith(":") -> { /* keep-alive, ignore */ }

                    else -> eventBuffer.appendLine(line)
                }
            }
        }
    }

    suspend fun createDirectConversation(
        request: CreateDirectConversationRequest
    ): ConversationResponse {
        val response = client.post("$baseUrl/api/v1/messaging/conversations/direct") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw Exception("createDirect failed ${response.status.value}: ${response.bodyAsText()}")
        }
        return try {
            response.body<ConversationEnvelope>().conversation
        } catch (_: Exception) {
            response.body()
        }
    }

    suspend fun getKeyPackage(userId: String): KeyPackageResponse {
        val response = client.get("$baseUrl/api/v1/messaging/key-packages/by-user/$userId") {
            bearerAuth(token())
        }
        if (!response.status.isSuccess()) {
            throw Exception("getKeyPackage failed ${response.status.value}: ${response.bodyAsText()}")
        }
        val packages = response.body<KeyPackagesForUserEnvelope>().keyPackages
        return packages.firstOrNull()
            ?: throw Exception("No key packages available for user $userId")
    }

    suspend fun commitGroupChange(
        conversationId: String,
        request: CommitGroupChangeRequest
    ) {
        val response = client.post("$baseUrl/api/v1/messaging/conversations/$conversationId/mls/commit") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            println("MemegramDebug 🚨 Backend вернул ошибку ${response.status.value} в commitGroupChange: $errorBody")
            throw Exception("Ошибка commitGroupChange: $errorBody")
        }
    }

    suspend fun createGroupConversation(
        request: CreateGroupConversationRequest
    ): ConversationResponse {
        val response = client.post("$baseUrl/api/v1/messaging/conversations/group") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw Exception("createGroup failed ${response.status.value}: ${response.bodyAsText()}")
        }
        return try {
            response.body<ConversationEnvelope>().conversation
        } catch (_: Exception) {
            response.body()
        }
    }

// ── Media ─────────────────────────────────────────────────────────────────

    suspend fun initiateMediaUpload(
        request: InitiateMediaUploadRequest
    ): InitiateMediaUploadResponse {
        val response = client.post("$baseUrl/api/v1/messaging/media/upload") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess())
            throw Exception("initiateMediaUpload: ${response.status.value} — ${response.bodyAsText()}")
        return response.body<InitiateMediaUploadResponse>()
    }

    suspend fun uploadEncryptedBytesToUrl(
        uploadUrl: String,
        encryptedBytes: ByteArray,
        mimeType: String
    ) {
        val response = client.put(uploadUrl) {
            header("Content-Type", mimeType)
            setBody(encryptedBytes)
        }
        if (!response.status.isSuccess())
            throw Exception("upload failed: ${response.status.value}")
    }

    suspend fun confirmMediaUpload(mediaId: String): ConfirmMediaUploadResponse {
        val response = client.post("$baseUrl/api/v1/messaging/media/$mediaId/confirm") {
            bearerAuth(token())
        }
        if (!response.status.isSuccess())
            throw Exception("confirmMediaUpload: ${response.status.value} — ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun downloadBytesFromUrl(url: String): ByteArray {
        val response = client.get(url)
        if (!response.status.isSuccess())
            throw Exception("downloadBytes: ${response.status.value}")
        return response.body()
    }

    suspend fun getMediaDownloadUrl(mediaId: String): GetMediaDownloadUrlResponse =
        client.get("$baseUrl/api/v1/messaging/media/$mediaId/download") {
            bearerAuth(token())
        }.body<GetMediaDownloadUrlResponse>()

    // ── Устройства ────────────────────────────────────────────────────

    suspend fun submitDeviceData(
        registrationId: String,
        request: SubmitDeviceDataRequest
    ): SubmitDeviceDataResponse =
        client.post("$baseUrl/api/v1/devices/$registrationId/submit") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getDevices(): List<DeviceInfoResponse> =
        client.get("$baseUrl/api/v1/devices") { bearerAuth(token()) }.body()

    suspend fun initDeviceAddition(): InitDeviceAdditionResponse =
        client.post("$baseUrl/api/v1/devices/init-addition") { bearerAuth(token()) }.body()

    suspend fun getPendingDeviceAdditions(): List<PendingDeviceRegistration> =
        client.get("$baseUrl/api/v1/devices/addition/pending") { bearerAuth(token()) }.body()

    suspend fun confirmDeviceAddition(registrationId: String, request: ConfirmDeviceAdditionRequest): ConfirmDeviceAdditionResponse =
        client.post("$baseUrl/api/v1/devices/addition/$registrationId/confirm") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody(request)
        }.body()

    suspend fun revokeDevice(deviceId: String, request: RevokeDeviceRequest): RevokeDeviceResponse =
        client.delete("$baseUrl/api/v1/devices/$deviceId") {
            bearerAuth(token()); contentType(ContentType.Application.Json); setBody(request)
        }.body()

    suspend fun getDeviceAdditionStatus(registrationId: String): DeviceAdditionStatusResponse =
        client.get("$baseUrl/api/v1/devices/addition/$registrationId/status") { bearerAuth(token()) }.body()

    suspend fun getKeyPackagesForUser(userId: String): List<UserDeviceKeyPackage> =
        client.get("$baseUrl/api/v1/messaging/key-packages/by-user/$userId") {
            bearerAuth(token())
        }.body<GetKeyPackagesForUserResponse>().keyPackages

    suspend fun updateDeviceKeys(deviceId: String, request: UpdateDeviceKeysRequest): UpdateDeviceKeysResponse =
        client.put("$baseUrl/api/v1/devices/$deviceId/update-keys") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
}