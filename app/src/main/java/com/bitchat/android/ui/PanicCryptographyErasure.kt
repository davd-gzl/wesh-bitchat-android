package com.bitchat.android.ui

internal data class PanicCryptographyErasure(
    val mesh: Boolean,
    val identity: Boolean,
    val favoriteKeys: Boolean,
    val favorites: Boolean
) {
    val confirmed: Boolean get() = mesh && identity && favoriteKeys && favorites
}

/** Each independent owner is attempted; cancellation keeps the outer panic pending. */
internal fun clearPanicCryptography(
    clearMesh: () -> Unit,
    clearIdentity: () -> Boolean,
    clearFavoriteKeys: () -> Boolean,
    clearFavorites: () -> Boolean
): PanicCryptographyErasure {
    val mesh = checkedErasure { clearMesh(); true }
    val identity = checkedErasure(clearIdentity)
    val favoriteKeys = checkedErasure(clearFavoriteKeys)
    val favorites = checkedErasure(clearFavorites)
    return PanicCryptographyErasure(mesh, identity, favoriteKeys, favorites)
}

private inline fun checkedErasure(erase: () -> Boolean): Boolean = try {
    erase()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
