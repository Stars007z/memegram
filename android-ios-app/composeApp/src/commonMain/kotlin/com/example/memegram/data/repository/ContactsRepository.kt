package com.example.memegram.data.repository

import com.example.memegram.data.models.ContactEntry
import com.example.memegram.data.models.BlockedEntry

interface ContactsRepository {
    suspend fun getContacts(limit: Int = 50, offset: Int = 0): Result<List<ContactEntry>>
    suspend fun addContact(userPublicKey: String): Result<ContactEntry>
    suspend fun removeContact(contactUserId: String): Result<Unit>
    suspend fun updateContact(contactUserId: String, isFavorite: Boolean): Result<ContactEntry>
    suspend fun getBlockedUsers(limit: Int = 50, offset: Int = 0): Result<List<BlockedEntry>>
    suspend fun blockUser(blockedUserId: String): Result<Unit>
    suspend fun unblockUser(blockedUserId: String): Result<Unit>
}