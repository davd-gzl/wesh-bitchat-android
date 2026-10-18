package com.bitchat.android.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded process-owned drafts. No transport, identity creation or enrollment side effect. */
internal class WeshDraftStore(
    private val backend: WeshDraftBackend? = null,
    private val scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    enum class Persistence { Loading, Unconfirmed, Confirmed, Error, WipeRequired, Wiped }

    private val lock = Any()
    private val io = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts = _drafts.asStateFlow()
    private val _persistence = MutableStateFlow(Persistence.Loading)
    val persistence = _persistence.asStateFlow()
    private var owner: String? = null
    private var generation = 0L
    private var revision = 0L
    private var confirmedRevision = -1L
    private var loaded = backend == null
    private var fenced = false
    private var wipeVerified = false
    private val beforeLoad = linkedMapOf<String, String>()
    private val destinationVersions = linkedMapOf<String, Long>()
    val epoch: Long get() = synchronized(lock) { generation }

    init {
        scope?.launch {
            for (ignored in writes) persistCurrent()
        }
    }

    fun save(expectedEpoch: Long, destination: String, value: String): Boolean = synchronized(lock) {
        if (fenced || generation != expectedEpoch || !validDestination(destination) ||
            value.toByteArray(Charsets.UTF_8).size > MAX_DRAFT_BYTES
        ) {
            return@synchronized false
        }
        if (!loaded && destination !in beforeLoad && beforeLoad.size >= MAX_DRAFTS) return@synchronized false
        val previousKeys = _drafts.value.keys
        val next = LinkedHashMap(_drafts.value)
        next.remove(destination)
        if (value.isNotEmpty()) next[destination] = value
        bound(next)
        _drafts.value = next
        revision++
        destinationVersions.keys.retainAll(next.keys)
        if (destination in next) destinationVersions[destination] = revision
        if (!loaded) {
            beforeLoad.remove(destination)
            beforeLoad[destination] = if (destination in next) value else ""
            // Keep deletion/eviction tombstones until restore succeeds; never forget an edit.
            (previousKeys - next.keys).forEach { beforeLoad[it] = "" }
        }
        if (_persistence.value != Persistence.Error) _persistence.value = Persistence.Unconfirmed
        if (loaded) writes.trySend(Unit)
        true
    }

    class Capture internal constructor(val destination: String, val text: String, internal val version: Long, internal val epoch: Long)

    fun capture(destination: String): Capture? = synchronized(lock) {
        val text = _drafts.value[destination] ?: return@synchronized null
        Capture(destination, text, destinationVersions[destination] ?: return@synchronized null, generation)
    }

    fun isCurrent(capture: Capture): Boolean = synchronized(lock) {
        !fenced && generation == capture.epoch && destinationVersions[capture.destination] == capture.version &&
            _drafts.value[capture.destination] == capture.text
    }

    fun clearIfUnchanged(capture: Capture) {
        synchronized(lock) { if (isCurrent(capture)) save(capture.epoch, capture.destination, "") }
    }

    /** A different local full Noise key fences pending work and discards the previous RAM owner. */
    suspend fun load(identity: String) = loadFor(identity, null)

    private suspend fun loadFor(identity: String, expectedGeneration: Long?) {
        require(validOwner(identity))
        val token = synchronized(lock) {
            if (expectedGeneration != null && (generation != expectedGeneration || owner != identity)) return
            if (fenced && !wipeVerified) return
            if (owner != null && owner != identity) {
                generation++
                revision++
                _drafts.value = emptyMap()
                beforeLoad.clear()
                destinationVersions.clear()
                loaded = false
                confirmedRevision = -1
            }
            owner = identity
            if (loaded) return
            _persistence.value = Persistence.Loading
            generation
        }
        withContext(ioDispatcher) {
            io.withLock {
                if (!isCurrent(token, identity) || synchronized(lock) { loaded }) return@withLock
                try {
                    if (synchronized(lock) { wipeVerified }) {
                        backend?.finishVerifiedWipe()
                        synchronized(lock) { if (generation == token) { wipeVerified = false; fenced = false } }
                    }
                    val stored = backend?.load(identity).orEmpty()
                    validate(stored)
                    synchronized(lock) {
                        if (!isCurrent(token, identity)) return@synchronized
                        val merged = LinkedHashMap(stored)
                        beforeLoad.forEach { (key, text) ->
                            merged.remove(key)
                            if (text.isNotEmpty()) merged[key] = text
                        }
                        bound(merged)
                        val changed = beforeLoad.isNotEmpty()
                        beforeLoad.clear()
                        _drafts.value = merged
                        destinationVersions.keys.retainAll(merged.keys)
                        merged.keys.filterNot(destinationVersions::containsKey).forEach { destinationVersions[it] = ++revision }
                        loaded = true
                        if (!changed) confirmedRevision = revision
                        _persistence.value = if (changed) Persistence.Unconfirmed else Persistence.Confirmed
                        if (changed) writes.trySend(Unit)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (isCurrent(token, identity)) {
                            if (error is DraftWipePending) fenced = true
                            _persistence.value = if (fenced) Persistence.WipeRequired else Persistence.Error
                        }
                    }
                }
            }
        }
    }

    fun retry() {
        val request = synchronized(lock) {
            val identity = owner
            if (fenced || identity == null) null else identity to generation
        } ?: return
        scope?.launch {
            val restore = synchronized(lock) {
                if (!isCurrent(request.second, request.first) || fenced) return@launch
                if (loaded) { writes.trySend(Unit); false } else true
            }
            if (restore) loadFor(request.first, request.second)
        }
    }

    private suspend fun persistCurrent() = withContext(ioDispatcher) {
        io.withLock {
            val snapshot = synchronized(lock) {
                val identity = owner
                if (fenced || !loaded || identity == null || confirmedRevision == revision) return@withLock
                Snapshot(identity, generation, revision, _drafts.value.toMap())
            }
            try {
                backend?.write(snapshot.owner, snapshot.drafts)
                val readback = backend?.load(snapshot.owner) ?: snapshot.drafts
                check(readback == snapshot.drafts) { "Draft readback unavailable" }
                synchronized(lock) {
                    if (!isCurrent(snapshot.generation, snapshot.owner) || fenced) return@synchronized
                    confirmedRevision = snapshot.revision
                    _persistence.value = if (confirmedRevision == revision) Persistence.Confirmed else Persistence.Unconfirmed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (isCurrent(snapshot.generation, snapshot.owner)) {
                        if (error is DraftWipePending) fenced = true
                        _persistence.value = if (fenced) Persistence.WipeRequired else Persistence.Error
                    }
                }
            }
        }
    }

    /** RAM is fenced synchronously before awaiting IO; only a verified wipe permits reactivation. */
    fun fenceForPanic() {
        synchronized(lock) {
            generation++
            revision++
            fenced = true
            loaded = false
            wipeVerified = false
            _drafts.value = emptyMap()
            beforeLoad.clear()
            destinationVersions.clear()
            _persistence.value = Persistence.WipeRequired
        }
    }

    suspend fun panicClear(): Boolean {
        fenceForPanic()
        return withContext(NonCancellable + ioDispatcher) {
            io.withLock {
                try {
                    backend?.wipe()
                    synchronized(lock) {
                        owner = null
                        wipeVerified = true
                        _persistence.value = Persistence.Wiped
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun isCurrent(token: Long, identity: String): Boolean = synchronized(lock) {
        generation == token && owner == identity
    }

    private data class Snapshot(val owner: String, val generation: Long, val revision: Long, val drafts: Map<String, String>)

    companion object {
        const val MAX_DRAFT_BYTES = 8192
        const val MAX_DRAFTS = 64
        const val MAX_TOTAL_BYTES = 131072
        private val hex = Regex("[0-9a-f]{64}")
        fun validOwner(owner: String): Boolean = hex.matches(owner)
        fun privateDestination(noiseHex: String): String? = noiseHex.takeIf(hex::matches)?.let { "private:$it" }
        fun validDestination(destination: String): Boolean =
            destination == "public" || destination.startsWith("private:") && hex.matches(destination.removePrefix("private:"))

        private fun validate(drafts: Map<String, String>) {
            require(drafts.size <= MAX_DRAFTS)
            require(drafts.all { (key, text) -> validDestination(key) && text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES })
            require(drafts.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MAX_TOTAL_BYTES)
        }

        private fun bound(drafts: LinkedHashMap<String, String>) {
            while (drafts.size > MAX_DRAFTS || drafts.values.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_TOTAL_BYTES) {
                drafts.remove(drafts.keys.first())
            }
        }

        private fun lazyBackend(context: Context): WeshDraftBackend = object : WeshDraftBackend {
            private val delegate by lazy { EncryptedWeshDraftBackend(context) }
            override fun load(owner: String) = delegate.load(owner)
            override fun write(owner: String, drafts: Map<String, String>) = delegate.write(owner, drafts)
            override fun wipe() = delegate.wipe()
            override fun finishVerifiedWipe() = delegate.finishVerifiedWipe()
        }

        @Volatile private var instance: WeshDraftStore? = null
        fun get(context: Context): WeshDraftStore = instance ?: synchronized(this) {
            instance ?: WeshDraftStore(lazyBackend(context.applicationContext), CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { instance = it }
        }
    }
}
