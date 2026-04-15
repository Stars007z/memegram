package com.example.memegram.mls


data class WelcomeBundleKt(
    val commit: ByteArray,
    val welcome: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (other !is WelcomeBundleKt) return false
        return commit.contentEquals(other.commit) && welcome.contentEquals(other.welcome)
    }
    override fun hashCode(): Int =
        31 * commit.contentHashCode() + welcome.contentHashCode()
}

sealed class IncomingMessageKt {
    data class Application(val data: ByteArray) : IncomingMessageKt() {
        override fun equals(other: Any?): Boolean =
            other is Application && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
    }
    object CommitApplied : IncomingMessageKt()
    object Proposal : IncomingMessageKt()
    object Other : IncomingMessageKt()
}

interface MlsPlatformClient {
    fun exportProviderState(): ByteArray
    fun exportSigningKey(): ByteArray
    fun generateKeyPackage(): ByteArray
    fun createGroupWithId(groupId: ByteArray)
    fun addMember(groupId: ByteArray, keyPackageBytes: ByteArray): WelcomeBundleKt
    fun joinFromWelcome(welcomeBytes: ByteArray): ByteArray
    fun encryptMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray
    fun processMessage(groupId: ByteArray, msgBytes: ByteArray): IncomingMessageKt
    fun leaveGroup(groupId: ByteArray): ByteArray
    fun deleteGroup(groupId: ByteArray)
    fun removeMemberByIdentity(groupId: ByteArray, identity: String): ByteArray
    fun getGroupEpoch(groupId: ByteArray): ULong
    fun memberCount(groupId: ByteArray): ULong
    @Throws(Exception::class)
    fun mergePendingCommit(groupId: ByteArray)
    @Throws(Exception::class)
    fun clearPendingCommit(groupId: ByteArray)
    @Throws(Exception::class)
    fun clearPendingProposals(groupId: ByteArray)
}

expect fun createMlsClient(identity: String): MlsPlatformClient

expect fun restoreMlsClient(
    identity: String,
    providerState: ByteArray,
    signingKey: ByteArray
): MlsPlatformClient