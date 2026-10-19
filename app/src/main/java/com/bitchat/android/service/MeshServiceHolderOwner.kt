package com.bitchat.android.service

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.wifiaware.WifiAwareFactoryResult

internal enum class HolderLogLevel { DEBUG, INFO, WARN, ERROR }

internal enum class HolderGossipSource { BLE_SERVICE, CORE_FALLBACK }
internal sealed interface HolderGossipParent {
    class Constructed(val context: HolderConstructionContext) : HolderGossipParent
    data object Unattributed : HolderGossipParent
}
internal sealed interface HolderGossipProvenance {
    class Constructed(val context: HolderConstructionContext, val entry: HolderEntryOrigin) : HolderGossipProvenance
    data object Unattributed : HolderGossipProvenance
}
internal sealed interface HolderGossipChildTicket

/** Exact original identity and provenance, never admission or a drain acknowledgment. */
internal sealed interface HolderGossipManagerReference<out G : Any> {
    val ticket: HolderGossipChildTicket
    val source: HolderGossipSource
    val provenance: HolderGossipProvenance
    val manager: G
}
internal sealed interface HolderGossipChildOutcome<out G : Any> {
    data object Dispatched : HolderGossipChildOutcome<Nothing>
    class Returned<G : Any>(val original: HolderGossipManagerReference<G>) : HolderGossipChildOutcome<G>
    data object Unknown : HolderGossipChildOutcome<Nothing>
}
internal data class HolderGossipChildSnapshot<G : Any>(
    val ticket: HolderGossipChildTicket,
    val source: HolderGossipSource,
    val provenance: HolderGossipProvenance,
    val outcome: HolderGossipChildOutcome<G>
)

internal sealed interface HolderAcquisitionResult<out T : Any> {
    class Available<T : Any>(val product: T) : HolderAcquisitionResult<T>
    data object RefusedBeforeAcquisition : HolderAcquisitionResult<Nothing>
}

/** Opaque provenance, not admission or permission to open a new runtime. */
internal sealed interface HolderConstructionContext
internal sealed interface HolderWifiConstructionContext : HolderConstructionContext
internal class HolderEpoch internal constructor()
/** Original process provenance is independent of the legacy construction epoch. */
internal sealed interface HolderOrigin
internal sealed interface HolderProcessPreparation { val origin: HolderOrigin }
internal sealed interface HolderEntryProvenance {
    class Original(val origin: HolderOrigin) : HolderEntryProvenance
    data object LegacyUnattributed : HolderEntryProvenance
}
internal class HolderEntryOrigin internal constructor(val epoch: HolderEpoch, val provenance: HolderEntryProvenance)
internal enum class HolderConstructionKind { BLUETOOTH, UNIFIED, WIFI }
internal sealed interface HolderConstructionOutcome {
    data object Dispatched : HolderConstructionOutcome
    class Returned(val product: Any) : HolderConstructionOutcome
    class Unknown(val kind: HolderConstructionKind) : HolderConstructionOutcome
}
internal data class HolderConstructionReceipt(
    val context: HolderConstructionContext,
    val entry: HolderEntryOrigin,
    val kind: HolderConstructionKind,
    val suppliedBluetooth: Any?,
    val outcome: HolderConstructionOutcome
)

/** Restrictive ownership identity; never allocation authority or a checked drain. */
internal sealed interface HolderPanicSubject
internal enum class HolderPanicCaptureGap {
    NoUniqueReturnedUnified, AmbiguousReturnedUnified, ForeignSubject, InvalidReturnedUnified, ProductMismatch
}
internal sealed interface HolderPanicSubjectCapture {
    class Captured(val subject: HolderPanicSubject) : HolderPanicSubjectCapture
    class Unconfirmed(val gap: HolderPanicCaptureGap) : HolderPanicSubjectCapture
}
internal sealed interface HolderConstructionCloseOutcome {
    data object ClosedForConstruction : HolderConstructionCloseOutcome
    class Unconfirmed(val gap: HolderPanicCaptureGap) : HolderConstructionCloseOutcome
}
internal interface HolderPanicAdmissionActor {
    fun captureUnifiedPanicSubject(exactUnified: Any): HolderPanicSubjectCapture
    fun closeConstructionAdmission(subject: HolderPanicSubject, exactUnified: Any): HolderConstructionCloseOutcome
}

/** The actor and exact original product are supplied before a ViewModel can request panic. */
internal sealed interface HolderPanicBinding
private class CapturedHolderPanicBinding(
    val actor: HolderPanicAdmissionActor,
    val subject: HolderPanicSubject,
    val product: Any
) : HolderPanicBinding
private class UnconfirmedHolderPanicBinding(val gap: HolderPanicCaptureGap) : HolderPanicBinding

internal fun captureHolderPanicBinding(actor: HolderPanicAdmissionActor, exactUnified: Any): HolderPanicBinding =
    when (val captured = actor.captureUnifiedPanicSubject(exactUnified)) {
        is HolderPanicSubjectCapture.Captured -> CapturedHolderPanicBinding(actor, captured.subject, exactUnified)
        is HolderPanicSubjectCapture.Unconfirmed -> UnconfirmedHolderPanicBinding(captured.gap)
    }

internal fun closeHolderPanicBinding(binding: HolderPanicBinding, exactCurrentMesh: Any): HolderConstructionCloseOutcome =
    when (binding) {
        is UnconfirmedHolderPanicBinding -> HolderConstructionCloseOutcome.Unconfirmed(binding.gap)
        is CapturedHolderPanicBinding -> if (binding.product !== exactCurrentMesh) {
            HolderConstructionCloseOutcome.Unconfirmed(HolderPanicCaptureGap.ProductMismatch)
        } else binding.actor.closeConstructionAdmission(binding.subject, binding.product)
    }

/** External registration never inherits a constructor or its epoch. */
internal sealed interface HolderManagerProvenance {
    class Constructed(val context: HolderConstructionContext) : HolderManagerProvenance
    data object UnattributedExternal : HolderManagerProvenance
}

/** Its identity distinguishes even repeated registrations of the same manager. */
internal class HolderManagerRegistration<G : Any> internal constructor(
    val manager: G,
    val provenance: HolderManagerProvenance,
    val entry: HolderEntryOrigin?,
    internal val signer: (BitchatPacket) -> BitchatPacket?
)

/** Selection is recorded at the constructor's separate manager-read step, including known absence. */
internal sealed interface HolderWifiManagerSelection<out G : Any> {
    data object NotSelected : HolderWifiManagerSelection<Nothing>
    class Selected<G : Any>(val registration: HolderManagerRegistration<G>?) : HolderWifiManagerSelection<G>
}

internal data class HolderWifiConstructionReceipt<G : Any>(
    val context: HolderWifiConstructionContext,
    val entry: HolderEntryOrigin,
    val controllerOrigin: Any,
    val selection: HolderWifiManagerSelection<G>,
    val outcome: HolderConstructionOutcome
)

/** Retained records remain unconfirmed; these observations are not a drain acknowledgment. */
internal data class HolderProvenanceSnapshot<G : Any>(
    val activeEntryCount: Int,
    val constructions: List<HolderConstructionReceipt>,
    val registrations: List<HolderManagerRegistration<G>>,
    val externalProducts: List<Any>,
    val wifiConstructions: List<HolderWifiConstructionReceipt<G>>
)

/** Required actions; creating the owner does not open a runtime or a platform service. */
internal data class MeshServiceHolderPorts<B : Any, U : Any, G : Any>(
    val isReusable: (B) -> Boolean,
    val stopBluetooth: (B) -> Unit,
    val refreshUnified: (U) -> Unit,
    val startGossip: (G) -> Unit,
    val stopGossip: (G) -> Unit,
    val clearGossip: (G) -> Unit,
    val installGossipDelegate: (G, GossipSyncManager.Delegate) -> Unit,
    val broadcast: (BitchatPacket) -> Unit,
    val sendToPeer: (String, BitchatPacket) -> Unit,
    val log: (HolderLogLevel, String) -> Unit
)

/** Existing holder serialization and cache behavior, independent of platform construction. */
internal class MeshServiceHolderOwner<B : Any, U : Any, G : Any>(
    private val ports: MeshServiceHolderPorts<B, U, G>
) : HolderPanicAdmissionActor {
    // Preserve reentrant constructor -> manager registration under one legacy monitor.
    private val legacyMonitor = Any()
    // Legacy operations may enter this RAM-only monitor. Never acquire legacy or invoke ports here.
    private val registryMonitor = Any()
    private val ownerAnchor = Any()
    private class InitialOrigin(val ownerAnchor: Any) : HolderOrigin
    private class InitialPreparation(override val origin: HolderOrigin) : HolderProcessPreparation
    private var preparation: HolderProcessPreparation? = null
    private class UnifiedPanicSubject(val ownerAnchor: Any, val context: HolderConstructionContext) : HolderPanicSubject
    private var constructionAdmissionClosed = false
    @Volatile var meshService: B? = null
        private set
    @Volatile var unifiedMeshService: U? = null
        private set
    @Volatile private var currentRegistration: HolderManagerRegistration<G>? = null
    val sharedGossipSyncManager: G? get() = currentRegistration?.manager
    private val epoch = HolderEpoch()
    private val activeEntries = mutableSetOf<HolderEntryOrigin>()
    private class ConstructionContext : HolderConstructionContext
    private class WifiConstructionContext : HolderWifiConstructionContext
    private class ConstructionRecord<G : Any>(
        val context: HolderConstructionContext,
        val entry: HolderEntryOrigin,
        val kind: HolderConstructionKind,
        val suppliedBluetooth: Any?,
        val controllerOrigin: Any? = null,
        var selection: HolderWifiManagerSelection<G> = HolderWifiManagerSelection.NotSelected,
        var outcome: HolderConstructionOutcome = HolderConstructionOutcome.Dispatched
    )
    private val constructions = linkedMapOf<HolderConstructionContext, ConstructionRecord<G>>()
    private val registrations = mutableListOf<HolderManagerRegistration<G>>()
    private val externalProducts = java.util.IdentityHashMap<B, Unit>()
    private val activeGossipOwners = mutableSetOf<String>()

    private class GossipChildTicket : HolderGossipChildTicket
    private class GossipChildRecord<G : Any>(
        val ticket: HolderGossipChildTicket,
        val source: HolderGossipSource,
        val provenance: HolderGossipProvenance,
        var outcome: HolderGossipChildOutcome<G> = HolderGossipChildOutcome.Dispatched
    )
    private class GossipManagerReference<G : Any>(
        override val ticket: HolderGossipChildTicket,
        override val source: HolderGossipSource,
        override val provenance: HolderGossipProvenance,
        override val manager: G
    ) : HolderGossipManagerReference<G>
    private val gossipChildren = linkedMapOf<HolderGossipChildTicket, GossipChildRecord<G>>()

    /** Records the creator only; accepted original contexts remain usable after admission closes. */
    fun constructGossipManager(
        parent: HolderGossipParent,
        source: HolderGossipSource,
        creator: () -> G
    ): G {
        val record = synchronized(registryMonitor) {
            val provenance = when (parent) {
                is HolderGossipParent.Constructed -> {
                    val construction = requireNotNull(constructions[parent.context]) {
                        "Construction context does not belong to this holder"
                    }
                    val expected = when (source) {
                        HolderGossipSource.BLE_SERVICE -> HolderConstructionKind.BLUETOOTH
                        HolderGossipSource.CORE_FALLBACK -> HolderConstructionKind.WIFI
                    }
                    require(construction.kind == expected) { "Manager source does not match its original construction" }
                    HolderGossipProvenance.Constructed(parent.context, construction.entry)
                }
                HolderGossipParent.Unattributed -> HolderGossipProvenance.Unattributed
            }
            val ticket = GossipChildTicket()
            GossipChildRecord<G>(ticket, source, provenance).also { gossipChildren[ticket] = it }
        }
        val manager = try {
            creator()
        } catch (failure: Throwable) {
            synchronized(registryMonitor) { record.outcome = HolderGossipChildOutcome.Unknown }
            throw failure
        }
        synchronized(registryMonitor) {
            record.outcome = HolderGossipChildOutcome.Returned(
                GossipManagerReference(record.ticket, record.source, record.provenance, manager)
            )
        }
        return manager
    }

    fun gossipConstructionSnapshot(): List<HolderGossipChildSnapshot<G>> = synchronized(registryMonitor) {
        gossipChildren.values.map { HolderGossipChildSnapshot(it.ticket, it.source, it.provenance, it.outcome) }
    }

    /** Pure initial-process provenance; ordinary clear/retry never replaces this value. */
    fun initialProcessPreparation(): HolderProcessPreparation = synchronized(registryMonitor) {
        preparation ?: InitialPreparation(InitialOrigin(ownerAnchor)).also { preparation = it }
    }

    /** RAM-only O(n) scan over retained, unbounded records; no cache or current-origin inference. */
    override fun captureUnifiedPanicSubject(exactUnified: Any): HolderPanicSubjectCapture = synchronized(registryMonitor) {
        val matches = constructions.values.filter {
            it.kind == HolderConstructionKind.UNIFIED &&
                (it.outcome as? HolderConstructionOutcome.Returned)?.product === exactUnified
        }
        when (matches.size) {
            0 -> HolderPanicSubjectCapture.Unconfirmed(HolderPanicCaptureGap.NoUniqueReturnedUnified)
            1 -> HolderPanicSubjectCapture.Captured(UnifiedPanicSubject(ownerAnchor, matches.single().context))
            else -> HolderPanicSubjectCapture.Unconfirmed(HolderPanicCaptureGap.AmbiguousReturnedUnified)
        }
    }

    /** Partial process admission closure only; accepted calls, callbacks and all drain proofs remain separate. */
    override fun closeConstructionAdmission(subject: HolderPanicSubject, exactUnified: Any): HolderConstructionCloseOutcome =
        synchronized(registryMonitor) {
            if (subject !is UnifiedPanicSubject || subject.ownerAnchor !== ownerAnchor) {
                return@synchronized HolderConstructionCloseOutcome.Unconfirmed(HolderPanicCaptureGap.ForeignSubject)
            }
            val record = constructions[subject.context]
            if (record == null || record.kind != HolderConstructionKind.UNIFIED ||
                (record.outcome as? HolderConstructionOutcome.Returned)?.product !== exactUnified) {
                return@synchronized HolderConstructionCloseOutcome.Unconfirmed(HolderPanicCaptureGap.InvalidReturnedUnified)
            }
            if (!constructionAdmissionClosed) constructionAdmissionClosed = true
            HolderConstructionCloseOutcome.ClosedForConstruction
        }

    fun setGossipManager(
        provenance: HolderManagerProvenance,
        manager: G,
        signer: (BitchatPacket) -> BitchatPacket?
    ): Unit = synchronized(legacyMonitor) {
        val entry = synchronized(registryMonitor) {
            when (provenance) {
                is HolderManagerProvenance.Constructed -> requireNotNull(constructions[provenance.context]) {
                    "Construction context does not belong to this holder"
                }.entry
                HolderManagerProvenance.UnattributedExternal -> null
            }
        }
        val previous = sharedGossipSyncManager
        if (previous !== manager) {
            try { previous?.let(ports.stopGossip) } catch (_: Exception) { }
        }
        val registration = HolderManagerRegistration(manager, provenance, entry, signer)
        synchronized(registryMonitor) {
            registrations += registration
            currentRegistration = registration
        }
        ports.installGossipDelegate(manager, TransportGossipDelegate(registration, ports.broadcast, ports.sendToPeer))
        if (activeGossipOwners.isNotEmpty()) ports.startGossip(manager)
    }

    fun startSharedGossip(owner: String): Unit = synchronized(legacyMonitor) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) sharedGossipSyncManager?.let(ports.startGossip)
    }

    fun stopSharedGossip(owner: String): Unit = synchronized(legacyMonitor) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) sharedGossipSyncManager?.let(ports.stopGossip)
    }

    private class TransportGossipDelegate<G : Any>(
        private val registration: HolderManagerRegistration<G>,
        private val broadcast: (BitchatPacket) -> Unit,
        private val sendToPeer: (String, BitchatPacket) -> Unit
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) = broadcast(packet)
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) = sendToPeer(peerID, packet)
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket? = registration.signer(packet)
    }

    /** Factory closures are call-scoped so prepared dependencies cannot become singleton state. */
    fun getOrCreate(createBluetooth: (HolderConstructionContext) -> B): HolderAcquisitionResult<B> = withEntry { entry ->
        getOrCreateLocked(entry, createBluetooth)
    }

    private fun getOrCreateLocked(entry: HolderEntryOrigin, createBluetooth: (HolderConstructionContext) -> B): B {
        val existing = meshService
        if (existing != null) {
            return try {
                if (ports.isReusable(existing)) {
                    log(HolderLogLevel.DEBUG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    log(HolderLogLevel.WARN, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    try { ports.stopBluetooth(existing) } catch (e: Exception) {
                        log(HolderLogLevel.WARN, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
                    log(HolderLogLevel.INFO, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                log(HolderLogLevel.ERROR, "Error checking service reusability; creating new instance: ${e.message}")
                val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
        log(HolderLogLevel.INFO, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        return created
    }

    fun getUnifiedOrCreate(createBluetooth: (HolderConstructionContext) -> B, createUnified: (B) -> U): HolderAcquisitionResult<U> = withEntry { entry ->
        val bluetooth = getOrCreateLocked(entry, createBluetooth)
        val existing = unifiedMeshService
        if (existing != null) {
            ports.refreshUnified(existing)
            return@withEntry existing
        }
        val created = construct(entry, HolderConstructionKind.UNIFIED, bluetooth) { createUnified(bluetooth) }
        unifiedMeshService = created
        log(HolderLogLevel.INFO, "Created new UnifiedMeshService")
        created
    }

    /** Wi-Fi's original constructor runs without either holder monitor. */
    fun <W : Any> constructWifi(original: HolderOrigin, controllerOrigin: Any, creator: (HolderWifiConstructionContext) -> W): WifiAwareFactoryResult<W> {
        val entry = HolderEntryOrigin(epoch, HolderEntryProvenance.Original(original))
        val context = WifiConstructionContext()
        val record = ConstructionRecord<G>(context, entry, HolderConstructionKind.WIFI, null, controllerOrigin)
        synchronized(registryMonitor) {
            require(original is InitialOrigin && original.ownerAnchor === ownerAnchor) {
                "Preparation origin does not belong to this holder"
            }
            if (constructionAdmissionClosed) return WifiAwareFactoryResult.RefusedBeforeAllocation
            activeEntries += entry
            constructions[context] = record
        }
        try {
            val product = try {
                creator(context)
            } catch (failure: Throwable) {
                synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Unknown(HolderConstructionKind.WIFI) }
                throw failure
            }
            synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Returned(product) }
            return WifiAwareFactoryResult.Created(product)
        } finally {
            synchronized(registryMonitor) { activeEntries -= entry }
        }
    }

    /** Reuses the caller's entry even if Bluetooth initialization waits on a different factory. */
    fun ensureBluetoothForWifi(context: HolderWifiConstructionContext, createBluetooth: (HolderConstructionContext) -> B) {
        val entry = synchronized(registryMonitor) { wifiRecord(context).entry }
        synchronized(legacyMonitor) { getOrCreateLocked(entry, createBluetooth) }
    }

    /** One immutable registration read, kept separate from the preceding Bluetooth acquisition. */
    fun selectManagerForWifi(context: HolderWifiConstructionContext): HolderManagerRegistration<G>? = synchronized(registryMonitor) {
        val record = wifiRecord(context)
        check(record.selection === HolderWifiManagerSelection.NotSelected) { "Wi-Fi manager was already selected" }
        currentRegistration.also { record.selection = HolderWifiManagerSelection.Selected(it) }
    }

    // Called only while registryMonitor is held; context identity is owner-issued and unforgeable.
    private fun wifiRecord(context: HolderWifiConstructionContext): ConstructionRecord<G> =
        requireNotNull(constructions[context]) { "Construction context does not belong to this holder" }.also {
            check(it.kind == HolderConstructionKind.WIFI) { "Construction context is not Wi-Fi" }
        }

    fun attach(service: B): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Attaching BluetoothMeshService to holder")
        synchronized(registryMonitor) { externalProducts[service] = Unit }
        meshService = service
        unifiedMeshService = null
    }

    fun clear(): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.let(ports.clearGossip) } catch (_: Exception) { }
        try { sharedGossipSyncManager?.let(ports.stopGossip) } catch (_: Exception) { }
        synchronized(registryMonitor) { currentRegistration = null }
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }

    private fun <T : Any> withEntry(action: (HolderEntryOrigin) -> T): HolderAcquisitionResult<T> {
        val entry = HolderEntryOrigin(epoch, HolderEntryProvenance.LegacyUnattributed)
        synchronized(registryMonitor) {
            if (constructionAdmissionClosed) return HolderAcquisitionResult.RefusedBeforeAcquisition
            activeEntries += entry
        }
        return try {
            synchronized(legacyMonitor) { HolderAcquisitionResult.Available(action(entry)) }
        } finally {
            synchronized(registryMonitor) { activeEntries -= entry }
        }
    }

    private fun <T : Any> construct(
        entry: HolderEntryOrigin,
        kind: HolderConstructionKind,
        suppliedBluetooth: Any? = null,
        factory: (HolderConstructionContext) -> T
    ): T {
        val context = ConstructionContext()
        val record = ConstructionRecord<G>(context, entry, kind, suppliedBluetooth)
        synchronized(registryMonitor) { constructions[context] = record }
        val product = try {
            factory(context)
        } catch (failure: Throwable) {
            synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Unknown(kind) }
            throw failure
        }
        // Record the return before caller logging can trigger the legacy retry.
        synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Returned(product) }
        return product
    }

    fun provenanceSnapshot(): HolderProvenanceSnapshot<G> = synchronized(registryMonitor) {
        HolderProvenanceSnapshot(
            activeEntries.size,
            constructions.values.map { HolderConstructionReceipt(it.context, it.entry, it.kind, it.suppliedBluetooth, it.outcome) },
            registrations.toList(),
            externalProducts.keys.toList(),
            constructions.values.filter { it.kind == HolderConstructionKind.WIFI }.map {
                HolderWifiConstructionReceipt(
                    it.context as HolderWifiConstructionContext, it.entry,
                    checkNotNull(it.controllerOrigin), it.selection, it.outcome
                )
            }
        )
    }

    private fun log(level: HolderLogLevel, message: String) = ports.log(level, message)
}
