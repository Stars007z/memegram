package com.example.memegram.mls

private class IosMlsClient : MlsPlatformClient {
    private fun notImplemented(): Nothing =
        throw UnsupportedOperationException(
            "MLS не реализован на iOS. Подключи Swift UniFFI фреймворк (см. README)."
        )

    override fun exportProviderState(): ByteArray = notImplemented()
    override fun exportSigningKey(): ByteArray = notImplemented()
    override fun generateKeyPackage(): ByteArray = notImplemented()
    override fun createGroupWithId(groupId: ByteArray) = notImplemented()
    override fun addMember(groupId: ByteArray, keyPackageBytes: ByteArray): WelcomeBundleKt = notImplemented()
    override fun joinFromWelcome(welcomeBytes: ByteArray): ByteArray = notImplemented()
    override fun encryptMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray = notImplemented()
    override fun processMessage(groupId: ByteArray, msgBytes: ByteArray): IncomingMessageKt = notImplemented()
    override fun leaveGroup(groupId: ByteArray): ByteArray = notImplemented()
    override fun deleteGroup(groupId: ByteArray) = notImplemented()
    override fun removeMemberByIdentity(groupId: ByteArray, identity: String): ByteArray = notImplemented()
    override fun getGroupEpoch(groupId: ByteArray): ULong = notImplemented()
    override fun memberCount(groupId: ByteArray): ULong = notImplemented()
    override fun mergePendingCommit(groupId: ByteArray) {
        notImplemented()
    }

    override fun clearPendingCommit(groupId: ByteArray) {
        notImplemented()
    }

    override fun clearPendingProposals(groupId: ByteArray) {
        notImplemented()
    }
}

actual fun createMlsClient(identity: String): MlsPlatformClient = IosMlsClient()

actual fun restoreMlsClient(
    identity: String,
    providerState: ByteArray,
    signingKey: ByteArray
): MlsPlatformClient = IosMlsClient()