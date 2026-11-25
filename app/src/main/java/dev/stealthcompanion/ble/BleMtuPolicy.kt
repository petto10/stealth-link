package dev.stealthcompanion.ble

import dev.stealthcompanion.core.protocol.SocialCodec

internal object BleMtuPolicy {
    const val MAXIMUM_ATT_MTU = 517
    const val REQUESTED_CLIENT_MTU = MAXIMUM_ATT_MTU

    fun canRequestClientMtu(
        featureEnabled: Boolean,
        configurationReady: Boolean,
        sameApprovedPeer: Boolean,
        socialSubscribed: Boolean,
    ): Boolean = featureEnabled && configurationReady && sameApprovedPeer && socialSubscribed

    fun validReportedMtu(value: Int): Int? = value.takeIf { it in SocialCodec.DEFAULT_MTU..MAXIMUM_ATT_MTU }

    fun socialMtu(serverMtu: Int?): Int = serverMtu?.let(::validReportedMtu) ?: SocialCodec.DEFAULT_MTU
}
