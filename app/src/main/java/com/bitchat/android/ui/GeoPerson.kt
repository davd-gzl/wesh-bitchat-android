package com.bitchat.android.ui

import java.util.*

data class GeoPerson(
    val id: String,           // pubkey hex (lowercased) - matches iOS
    val displayName: String,  // nickname with #suffix - matches iOS
    val lastSeen: Date        // activity timestamp - matches iOS
)


internal data class GeohashPeopleSections(
    val onLocation: List<GeoPerson>,
    val teleportedIn: List<GeoPerson>
)

/**
 * Names that require a short identity suffix, calculated across both people sections.
 *
 * Matching is case-insensitive to mirror geohash chat's nickname collision handling.
 */
internal fun duplicateGeohashBaseNames(people: List<GeoPerson>): Set<String> =
    people
        .groupingBy { splitSuffix(it.displayName).first.lowercase(Locale.ROOT) }
        .eachCount()
        .filterValues { it > 1 }
        .keys

/**
 * The same `#abcd` disambiguator used by geohash chat.
 *
 * Presence rows normally carry only a base nickname, so derive the suffix from the full Nostr
 * public key when a collision exists. Preserve an already-announced suffix for compatibility.
 */
internal fun geohashIdentitySuffix(person: GeoPerson, showHashSuffix: Boolean): String {
    if (!showHashSuffix) return ""
    val announcedSuffix = splitSuffix(person.displayName).second
    return announcedSuffix.ifEmpty { "#${person.id.takeLast(4)}" }
}

internal fun disambiguatedGeohashDisplayName(
    person: GeoPerson,
    duplicateBaseNames: Set<String>,
): String {
    val baseName = splitSuffix(person.displayName).first
    val showSuffix = baseName.lowercase(Locale.ROOT) in duplicateBaseNames
    return baseName + geohashIdentitySuffix(person, showSuffix)
}

/**
 * Split announced identities by how they entered this geohash. Bare `anon` heartbeat identities
 * are omitted, while announced names such as `anon1234` remain ordinary participants. Self is
 * retained even before a nickname announcement and is always first in the matching section.
 */
internal fun sectionGeohashPeople(
    people: List<GeoPerson>,
    myId: String?,
    selfIsTeleported: Boolean,
    teleportedIds: Set<String>
): GeohashPeopleSections {
    val normalizedMyId = myId?.lowercase(Locale.ROOT)
    val normalizedTeleportedIds = teleportedIds
        .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    fun isSelf(person: GeoPerson): Boolean =
        normalizedMyId != null && person.id.lowercase(Locale.ROOT) == normalizedMyId
    fun isTeleported(person: GeoPerson): Boolean =
        if (isSelf(person)) selfIsTeleported
        else person.id.lowercase(Locale.ROOT) in normalizedTeleportedIds

    val displayedPeople = people.filter { person ->
        isSelf(person) || !isUnannouncedNickname(person.displayName)
    }
    val ordered = displayedPeople.sortedWith(
        compareByDescending<GeoPerson>(::isSelf)
            .thenByDescending { it.lastSeen }
    )
    return GeohashPeopleSections(
        onLocation = ordered.filterNot(::isTeleported),
        teleportedIn = ordered.filter(::isTeleported)
    )
}

