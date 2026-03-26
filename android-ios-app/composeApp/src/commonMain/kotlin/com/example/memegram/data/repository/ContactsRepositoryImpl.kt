package com.example.memegram.data.repository

import com.example.memegram.data.models.*
import com.example.memegram.data.network.ApiService

class ContactsRepositoryImpl(
    private val api: ApiService
) : ContactsRepository {

    override suspend fun getContacts(limit: Int, offset: Int): Result<List<ContactEntry>> =
        runCatching { api.getContacts(limit, offset).contacts }

    override suspend fun addContact(userPublicKey: String): Result<ContactEntry> =
        runCatching { api.addContact(AddContactRequest(userPublicKey)) }

    override suspend fun removeContact(contactUserId: String): Result<Unit> =
        runCatching { api.removeContact(contactUserId); Unit }

    override suspend fun updateContact(contactUserId: String, isFavorite: Boolean): Result<ContactEntry> =
        runCatching {
            api.updateContact(
                contactUserId = contactUserId,
                request = UpdateContactRequest(
                    contactUserId = contactUserId,
                    isFavorite = isFavorite
                )
            )
        }

    override suspend fun getBlockedUsers(limit: Int, offset: Int): Result<List<BlockedEntry>> =
        runCatching { api.getBlockedUsers(limit, offset).blockedUsers }

    override suspend fun blockUser(blockedUserId: String): Result<Unit> =
        runCatching { api.blockUser(BlockUserRequest(blockedUserId)); Unit }

    override suspend fun unblockUser(blockedUserId: String): Result<Unit> =
        runCatching { api.unblockUser(blockedUserId); Unit }
}