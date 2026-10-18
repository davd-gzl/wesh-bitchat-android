package com.bitchat.android.ui

import com.bitchat.android.services.ConversationListPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun clearPanicMetadata(
    dataManager: DataManager,
    conversationPreferences: ConversationListPreferences
): Boolean {
    val plainCleared = checkedMetadataWrite { dataManager.clearAllData() }
    val organizationCleared = withContext(NonCancellable + Dispatchers.IO) {
        checkedMetadataWrite { conversationPreferences.clearAll() }
    }
    return plainCleared && organizationCleared
}

internal suspend fun savePanicNickname(dataManager: DataManager, nickname: String): Boolean =
    withContext(NonCancellable + Dispatchers.IO) {
        checkedMetadataWrite { dataManager.saveNicknameForPanic(nickname) }
    }

private inline fun checkedMetadataWrite(write: () -> Boolean): Boolean = try {
    write()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
