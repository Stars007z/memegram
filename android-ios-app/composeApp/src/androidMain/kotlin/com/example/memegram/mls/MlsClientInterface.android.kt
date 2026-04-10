package com.example.memegram.mls

import uniffi.mls_core.*

private class AndroidMlsClient(
    private val handle: MlsClientHandle
) : MlsPlatformClient {

    override fun exportProviderState(): ByteArray = handle.exportProviderState()
    override fun exportSigningKey(): ByteArray = handle.exportSigningKey()
    override fun generateKeyPackage(): ByteArray = handle.generateKeyPackage()
    override fun createGroupWithId(groupId: ByteArray) = handle.createGroupWithId(groupId)

    override fun addMember(
        groupId: ByteArray,
        keyPackageBytes: ByteArray
    ): WelcomeBundleKt {
        val bundle = handle.addMember(groupId, keyPackageBytes)
        return WelcomeBundleKt(commit = bundle.commit, welcome = bundle.welcome)
    }

    override fun joinFromWelcome(welcomeBytes: ByteArray): ByteArray =
        handle.joinFromWelcome(welcomeBytes)

    override fun encryptMessage(groupId: ByteArray, plaintext: ByteArray): ByteArray =
        handle.encryptMessage(groupId, plaintext)

    override fun processMessage(
        groupId: ByteArray,
        msgBytes: ByteArray
    ): IncomingMessageKt =
        when (val result = handle.processMessage(groupId, msgBytes)) {
            is IncomingMessage.Application  -> IncomingMessageKt.Application(result.data)
            is IncomingMessage.CommitApplied -> IncomingMessageKt.CommitApplied
            is IncomingMessage.Proposal     -> IncomingMessageKt.Proposal
            else                            -> IncomingMessageKt.Other
        }

    override fun leaveGroup(groupId: ByteArray): ByteArray =
        handle.leaveGroup(groupId)

    override fun getGroupEpoch(groupId: ByteArray): ULong =
        handle.getGroupEpoch(groupId)

    override fun memberCount(groupId: ByteArray): ULong =
        handle.memberCount(groupId)

    override fun mergePendingCommit(groupId: ByteArray) {
        handle.mergePendingCommit(groupId)
    }

    override fun clearPendingCommit(groupId: ByteArray) {
        handle.clearPendingCommit(groupId)
    }

    override fun clearPendingProposals(groupId: ByteArray) {
        handle.clearPendingProposals(groupId)
    }
}

actual fun createMlsClient(identity: String): MlsPlatformClient =
    AndroidMlsClient(MlsClientHandle(identity))

actual fun restoreMlsClient(
    identity: String,
    providerState: ByteArray,
    signingKey: ByteArray
): MlsPlatformClient =
    AndroidMlsClient(MlsClientHandle.newFromState(identity, providerState, signingKey))