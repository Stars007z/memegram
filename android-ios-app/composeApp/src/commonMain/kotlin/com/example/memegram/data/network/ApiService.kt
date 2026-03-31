package com.example.memegram.data.network

import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.models.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
            if (sinceEpoch != null) {
                parameter("since_epoch", sinceEpoch)
            }
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

    fun subscribeToConversation(conversationId: String): Flow<SseEvent> = flow {
        client.prepareGet("$baseUrl/api/v1/messaging/events") {
            bearerAuth(token())
            parameter("conversation_ids", conversationId)
            accept(ContentType.Text.EventStream)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    try { emit(Json.decodeFromString<SseEvent>(data)) }
                    catch (_: Exception) {}
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
        client.post("$baseUrl/api/v1/messaging/conversations/$conversationId/mls/commit") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}