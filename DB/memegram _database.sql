CREATE TABLE "users" (
  "id" uuid,
  "username" varchar NOT NULL,
  "avatar_media_id" uuid,
  "bio" varchar,
  "created_at" timestamp,
  "account_auto_delete_after_days" integer,
  "last_active" timestamp
);

CREATE TABLE "devices" (
  "id" uuid,
  "user_id" uuid,
  "device_id" varchar UNIQUE NOT NULL,
  "created_at" timestamp,
  "last_seen" timestamp,
  "is_active" bool DEFAULT true,
  "identity_key_pub" blob NOT NULL,
  "init_key_pub" blob NOT NULL,
  "credential_data" blob NOT NULL
);

CREATE TABLE "groups" (
  "id" uuid,
  "title" varchar NOT NULL,
  "description" varchar NOT NULL,
  "creator_user_id" uuid,
  "is_private_dialogue" bool,
  "created_at" timestamp
);

CREATE TABLE "group_members" (
  "id" uuid,
  "group_id" uuid,
  "user_id" uuid,
  "role" varchar,
  "leaf_index" integer,
  "epoch_joined" integer,
  "is_active" bool
);

CREATE TABLE "group_state" (
  "id" uuid,
  "group_id" uuid,
  "epoch" integer,
  "ratchet_tree" blob,
  "confirmed_transcript_hash" blob,
  "interim_transcript_hash" blob,
  "extensions" blob,
  "created_at" timestamp,
  "updated_at" timestamp
);

CREATE TABLE "commits" (
  "id" uuid,
  "group_id" uuid,
  "epoch" integer,
  "sender_device" uuid,
  "commit_message" blob,
  "welcome_message" blob,
  "created_at" timestamp
);

CREATE TABLE "messages" (
  "id" uuid,
  "group_id" uuid,
  "epoch" integer,
  "sender_device" uuid,
  "content_ciphertext" blob,
  "content_type" integer,
  "is_edited" bool,
  "reply_message_id" uuid,
  "created_at" timestamp,
  "updated_at" timestamp
);

CREATE TABLE "attachments" (
  "id" uuid,
  "message_id" uuid,
  "media_id" uuid,
  "created_at" timestamp
);

CREATE TABLE "invites" (
  "id" uuid,
  "code" varchar UNIQUE NOT NULL,
  "created_at" timestamp,
  "expires_at" timestamp,
  "is_used" bool DEFAULT false,
  "used_by" uuid,
  "used_at" timestamp
);

CREATE TABLE "media" (
  "id" uuid,
  "encrypted_blob" blob,
  "mime_type" varchar,
  "size" integer,
  "created_at" timestamp
);

CREATE TABLE "sticker_packs" (
  "id" uuid,
  "title" varchar,
  "created_at" timestamp,
  "updated_at" timestamp
);

CREATE TABLE "stickers" (
  "id" uuid,
  "pack_id" uuid,
  "media_id" uuid,
  "emoji" varchar,
  "created_at" timestamp
);

CREATE TABLE "message_reactions" (
  "id" uuid,
  "message_id" uuid,
  "user_id" uuid,
  "emoji" varchar NOT NULL,
  "created_at" timestamp
);

CREATE TABLE "message_reads" (
  "id" uuid,
  "message_id" uuid,
  "user_id" uuid,
  "read_at" timestamp
);

CREATE TABLE "contacts" (
  "id" uuid,
  "user_id" uuid,
  "contact_user_id" uuid,
  "created_at" timestamp,
  "is_favorite" bool
);

CREATE TABLE "group_settings" (
  "id" uuid,
  "user_id" uuid,
  "group_id" uuid,
  "message_auto_delete_hours" integer,
  "is_archived" bool DEFAULT false,
  "is_muted" bool DEFAULT false,
  "mute_until" timestamp,
  "created_at" timestamp,
  "updated_at" timestamp
);

CREATE TABLE "blocked_users" (
  "id" uuid,
  "user_id" uuid,
  "blocked_user_id" uuid,
  "created_at" timestamp
);

CREATE TABLE "user_settings" (
  "id" uuid,
  "user_id" uuid,
  "theme" varchar,
  "language" varchar,
  "font_size" int,
  "animations_enabled" bool,
  "created_at" timestamp,
  "updated_at" timestamp
);

CREATE TABLE "sessions" (
  "id" uuid,
  "device_id" uuid,
  "access_token" varchar NOT NULL,
  "refresh_token" varchar NOT NULL,
  "created_at" timestamp,
  "expires_at" timestamp,
  "last_used" timestamp,
  "ip_address" varchar,
  "user_agent" varchar,
  "is_revoked" bool DEFAULT false
);

ALTER TABLE "sessions" ADD FOREIGN KEY ("device_id") REFERENCES "devices" ("id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "user_settings" ("user_id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "blocked_users" ("user_id");

ALTER TABLE "blocked_users" ADD FOREIGN KEY ("blocked_user_id") REFERENCES "users" ("id");

ALTER TABLE "group_settings" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id");

ALTER TABLE "groups" ADD FOREIGN KEY ("id") REFERENCES "group_settings" ("group_id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "contacts" ("user_id");

ALTER TABLE "contacts" ADD FOREIGN KEY ("contact_user_id") REFERENCES "users" ("id");

ALTER TABLE "messages" ADD FOREIGN KEY ("id") REFERENCES "message_reads" ("message_id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "message_reads" ("user_id");

ALTER TABLE "stickers" ADD FOREIGN KEY ("pack_id") REFERENCES "sticker_packs" ("id");

ALTER TABLE "media" ADD FOREIGN KEY ("id") REFERENCES "stickers" ("media_id");

ALTER TABLE "messages" ADD FOREIGN KEY ("id") REFERENCES "message_reactions" ("message_id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "message_reactions" ("user_id");

ALTER TABLE "media" ADD FOREIGN KEY ("id") REFERENCES "users" ("avatar_media_id");

ALTER TABLE "devices" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "groups" ("creator_user_id");

ALTER TABLE "group_members" ADD FOREIGN KEY ("group_id") REFERENCES "groups" ("id");

ALTER TABLE "group_members" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id");

ALTER TABLE "groups" ADD FOREIGN KEY ("id") REFERENCES "group_state" ("group_id");

ALTER TABLE "groups" ADD FOREIGN KEY ("id") REFERENCES "commits" ("group_id");

ALTER TABLE "devices" ADD FOREIGN KEY ("id") REFERENCES "commits" ("sender_device");

ALTER TABLE "messages" ADD FOREIGN KEY ("group_id") REFERENCES "groups" ("id");

ALTER TABLE "devices" ADD FOREIGN KEY ("id") REFERENCES "messages" ("sender_device");

ALTER TABLE "messages" ADD FOREIGN KEY ("id") REFERENCES "messages" ("reply_message_id");

ALTER TABLE "attachments" ADD FOREIGN KEY ("message_id") REFERENCES "messages" ("id");

ALTER TABLE "users" ADD FOREIGN KEY ("id") REFERENCES "invites" ("used_by");

ALTER TABLE "media" ADD FOREIGN KEY ("id") REFERENCES "attachments" ("media_id");
