package com.bitchat.android.startup

internal object StartupRecoveryPolicy {
    const val IDENTITY_RESET_AFTER_FAILURES = 3

    fun offersIdentityReset(failures: Int, state: StartupCoordinator.State<*>, draftWipeRequired: Boolean): Boolean =
        !draftWipeRequired && state is StartupCoordinator.State.Failed && failures >= IDENTITY_RESET_AFTER_FAILURES
}
