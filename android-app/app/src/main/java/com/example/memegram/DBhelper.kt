package com.example.memegram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class DBhelper private constructor(context: Context) :
    SQLiteOpenHelper(context, "memeDB", null, 1) {

    companion object {
        @Volatile
        private var instance: DBhelper? = null

        fun getInstance(context: Context): DBhelper {
            return instance ?: synchronized(this) {
                instance ?: DBhelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Users
        db.execSQL("""
            CREATE TABLE "users" (
                "id" UUID PRIMARY KEY,
                "username" VARCHAR NOT NULL,
                "avatar_media_id" UUID,
                "bio" VARCHAR,
                "created_at" TIMESTAMP,
                "account_auto_delete_after_days" INTEGER,
                "last_active" TIMESTAMP
            )
        """)

        // 2. Devices
        db.execSQL("""
            CREATE TABLE "devices" (
                "id" UUID PRIMARY KEY,
                "user_id" UUID,
                "device_id" VARCHAR UNIQUE NOT NULL,
                "created_at" TIMESTAMP,
                "last_seen" TIMESTAMP,
                "is_active" BOOL DEFAULT true,
                "identity_key_pub" BLOB NOT NULL,
                "init_key_pub" BLOB NOT NULL,
                "credential_data" BLOB NOT NULL,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE
            )
        """)

        // 3. Groups
        db.execSQL("""
            CREATE TABLE "groups" (
                "id" UUID PRIMARY KEY,
                "title" VARCHAR NOT NULL,
                "description" VARCHAR NOT NULL,
                "creator_user_id" UUID,
                "is_private_dialogue" BOOL,
                "created_at" TIMESTAMP,
                FOREIGN KEY("creator_user_id") REFERENCES "users"("id")
            )
        """)

        // 4. Group Members
        db.execSQL("""
            CREATE TABLE "group_members" (
                "id" UUID PRIMARY KEY,
                "group_id" UUID,
                "user_id" UUID,
                "role" VARCHAR,
                "leaf_index" INTEGER,
                "epoch_joined" INTEGER,
                "is_active" BOOL,
                FOREIGN KEY("group_id") REFERENCES "groups"("id") ON DELETE CASCADE,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE
            )
        """)

        // 5. Group State
        db.execSQL("""
            CREATE TABLE "group_state" (
                "id" UUID PRIMARY KEY,
                "group_id" UUID,
                "epoch" INTEGER,
                "ratchet_tree" BLOB,
                "confirmed_transcript_hash" BLOB,
                "interim_transcript_hash" BLOB,
                "extensions" BLOB,
                "created_at" TIMESTAMP,
                "updated_at" TIMESTAMP,
                FOREIGN KEY("group_id") REFERENCES "groups"("id") ON DELETE CASCADE
            )
        """)

        // 6. Commits
        db.execSQL("""
            CREATE TABLE "commits" (
                "id" UUID PRIMARY KEY,
                "group_id" UUID,
                "epoch" INTEGER,
                "sender_device" UUID,
                "commit_message" BLOB,
                "welcome_message" BLOB,
                "created_at" TIMESTAMP,
                FOREIGN KEY("group_id") REFERENCES "groups"("id") ON DELETE CASCADE,
                FOREIGN KEY("sender_device") REFERENCES "devices"("id")
            )
        """)

        // 7. Messages
        db.execSQL("""
            CREATE TABLE "messages" (
                "id" UUID PRIMARY KEY,
                "group_id" UUID,
                "epoch" INTEGER,
                "sender_device" UUID,
                "content_ciphertext" BLOB,
                "content_type" INTEGER,
                "is_edited" BOOL,
                "reply_message_id" UUID,
                "created_at" TIMESTAMP,
                "updated_at" TIMESTAMP,
                FOREIGN KEY("group_id") REFERENCES "groups"("id") ON DELETE CASCADE,
                FOREIGN KEY("sender_device") REFERENCES "devices"("id"),
                FOREIGN KEY("reply_message_id") REFERENCES "messages"("id")
            )
        """)

        // 8. Attachments
        db.execSQL("""
            CREATE TABLE "attachments" (
                "id" UUID PRIMARY KEY,
                "message_id" UUID,
                "media_id" UUID,
                "created_at" TIMESTAMP,
                FOREIGN KEY("message_id") REFERENCES "messages"("id") ON DELETE CASCADE,
                FOREIGN KEY("media_id") REFERENCES "media"("id")
            )
        """)

        // 9. Invites
        db.execSQL("""
            CREATE TABLE "invites" (
                "id" UUID PRIMARY KEY,
                "code" VARCHAR UNIQUE NOT NULL,
                "created_at" TIMESTAMP,
                "expires_at" TIMESTAMP,
                "is_used" BOOL DEFAULT false,
                "used_by" UUID,
                "used_at" TIMESTAMP,
                FOREIGN KEY("used_by") REFERENCES "users"("id")
            )
        """)

        // 10. Media
        db.execSQL("""
            CREATE TABLE "media" (
                "id" UUID PRIMARY KEY,
                "encrypted_blob" BLOB,
                "mime_type" VARCHAR,
                "size" INTEGER,
                "created_at" TIMESTAMP
            )
        """)

        // 11. Sticker Packs
        db.execSQL("""
            CREATE TABLE "sticker_packs" (
                "id" UUID PRIMARY KEY,
                "title" VARCHAR,
                "created_at" TIMESTAMP,
                "updated_at" TIMESTAMP
            )
        """)

        // 12. Stickers
        db.execSQL("""
            CREATE TABLE "stickers" (
                "id" UUID PRIMARY KEY,
                "pack_id" UUID,
                "media_id" UUID,
                "emoji" VARCHAR,
                "created_at" TIMESTAMP,
                FOREIGN KEY("pack_id") REFERENCES "sticker_packs"("id") ON DELETE CASCADE,
                FOREIGN KEY("media_id") REFERENCES "media"("id")
            )
        """)

        // 13. Message Reactions
        db.execSQL("""
            CREATE TABLE "message_reactions" (
                "id" UUID PRIMARY KEY,
                "message_id" UUID,
                "user_id" UUID,
                "emoji" VARCHAR NOT NULL,
                "created_at" TIMESTAMP,
                FOREIGN KEY("message_id") REFERENCES "messages"("id") ON DELETE CASCADE,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE
            )
        """)

        // 14. Message Reads
        db.execSQL("""
            CREATE TABLE "message_reads" (
                "id" UUID PRIMARY KEY,
                "message_id" UUID,
                "user_id" UUID,
                "read_at" TIMESTAMP,
                FOREIGN KEY("message_id") REFERENCES "messages"("id") ON DELETE CASCADE,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE
            )
        """)

        // 15. Contacts
        db.execSQL("""
            CREATE TABLE "contacts" (
                "id" UUID PRIMARY KEY,
                "user_id" UUID,
                "contact_user_id" UUID,
                "created_at" TIMESTAMP,
                "is_favorite" BOOL,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE,
                FOREIGN KEY("contact_user_id") REFERENCES "users"("id")
            )
        """)

        // 16. Group Settings
        db.execSQL("""
            CREATE TABLE "group_settings" (
                "id" UUID PRIMARY KEY,
                "user_id" UUID,
                "group_id" UUID,
                "message_auto_delete_hours" INTEGER,
                "is_archived" BOOL DEFAULT false,
                "is_muted" BOOL DEFAULT false,
                "mute_until" TIMESTAMP,
                "created_at" TIMESTAMP,
                "updated_at" TIMESTAMP,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE,
                FOREIGN KEY("group_id") REFERENCES "groups"("id") ON DELETE CASCADE
            )
        """)

        // 17. Blocked Users
        db.execSQL("""
            CREATE TABLE "blocked_users" (
                "id" UUID PRIMARY KEY,
                "user_id" UUID,
                "blocked_user_id" UUID,
                "created_at" TIMESTAMP,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE,
                FOREIGN KEY("blocked_user_id") REFERENCES "users"("id")
            )
        """)

        // 18. User Settings
        db.execSQL("""
            CREATE TABLE "user_settings" (
                "id" UUID PRIMARY KEY,
                "user_id" UUID,
                "theme" VARCHAR,
                "language" VARCHAR,
                "font_size" INT,
                "animations_enabled" BOOL,
                "created_at" TIMESTAMP,
                "updated_at" TIMESTAMP,
                FOREIGN KEY("user_id") REFERENCES "users"("id") ON DELETE CASCADE
            )
        """)

        // 19. Sessions
        db.execSQL("""
            CREATE TABLE "sessions" (
                "id" UUID PRIMARY KEY,
                "device_id" UUID,
                "access_token" VARCHAR NOT NULL,
                "refresh_token" VARCHAR NOT NULL,
                "created_at" TIMESTAMP,
                "expires_at" TIMESTAMP,
                "last_used" TIMESTAMP,
                "ip_address" VARCHAR,
                "user_agent" VARCHAR,
                "is_revoked" BOOL DEFAULT false,
                FOREIGN KEY("device_id") REFERENCES "devices"("id") ON DELETE CASCADE
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val tables = listOf(
            "sessions", "user_settings", "blocked_users", "group_settings", "contacts",
            "message_reads", "message_reactions", "stickers", "sticker_packs", "invites",
            "attachments", "media", "messages", "commits", "group_state", "group_members",
            "groups", "devices", "users"
        )
        for (table in tables) {
            db.execSQL("DROP TABLE IF EXISTS \"$table\"")
        }
        onCreate(db)
    }

    fun addUser(username: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("username", username)
            put("created_at", System.currentTimeMillis().toString())
        }
        db.insert("users", null, values)
    }

    fun updateUserProfile(userId: String, newNickname: String, newBio: String?, avatarUri: String?) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("username", newNickname)
            put("bio", newBio)
            put("avatar_media_id", avatarUri)
        }
        db.update("users", values, "id = ?", arrayOf(userId))
    }

}