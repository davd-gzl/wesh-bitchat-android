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
    val product: Any,
    val gossipCollector: BoundGossipCollector,
    val processorCollector: BoundProcessorCollector
) : HolderPanicBinding
private class UnconfirmedHolderPanicBinding(val gap: HolderPanicCaptureGap) : HolderPanicBinding

internal fun captureHolderPanicBinding(actor: HolderPanicAdmissionActor, exactUnified: Any): HolderPanicBinding =
    when (val captured = actor.captureUnifiedPanicSubject(exactUnified)) {
        is HolderPanicSubjectCapture.Captured -> CapturedHolderPanicBinding(actor, captured.subject, exactUnified, BoundGossipCollector.Missing, BoundProcessorCollector.Missing)
        is HolderPanicSubjectCapture.Unconfirmed -> UnconfirmedHolderPanicBinding(captured.gap)
    }

internal fun closeHolderPanicBinding(binding: HolderPanicBinding, exactCurrentMesh: Any): HolderConstructionCloseOutcome =
    when (binding) {
        is UnconfirmedHolderPanicBinding -> HolderConstructionCloseOutcome.Unconfirmed(binding.gap)
        is CapturedHolderPanicBinding -> if (binding.product !== exactCurrentMesh) {
            HolderConstructionCloseOutcome.Unconfirmed(HolderPanicCaptureGap.ProductMismatch)
        } else binding.actor.closeConstructionAdmission(binding.subject, binding.product)
    }

/** A coherent partial observation, never a closed census or drain acknowledgment. */
internal data class HolderGossipSubsetSnapshot<G : Any>(
    val holder: HolderProvenanceSnapshot<G>,
    val children: List<HolderGossipChildSnapshot<G>>
)
internal sealed interface HolderGossipCaptureGap {
    data object MissingCollector : HolderGossipCaptureGap
    data object NotClosed : HolderGossipCaptureGap
    class InvalidSubject(val gap: HolderPanicCaptureGap) : HolderGossipCaptureGap
}
internal sealed interface HolderGossipSubsetCapture<out G : Any> {
    class Observed<G : Any>(val snapshot: HolderGossipSubsetSnapshot<G>) : HolderGossipSubsetCapture<G>
    class Unconfirmed(val gap: HolderGossipCaptureGap) : HolderGossipSubsetCapture<Nothing>
}

internal enum class HolderContextOperationKind { ManagerCreator, BluetoothEnsure, ManagerRegistration, ExternalAttach, PacketProcessorCreator }
internal enum class HolderContextOperationGap { ForeignContext, WrongContextKind, EndedContextAfterClose, UnattributedAfterClose }
internal sealed class HolderContextOperationRefusal protected constructor(
    val operation: HolderContextOperationKind,
    val gap: HolderContextOperationGap
) : IllegalArgumentException("Holder context operation refused: $operation/$gap")
internal enum class HolderOperationFailureKind { OrdinaryThrow, CancellationThrown, PolicyRefusal, Protocol }
internal enum class HolderRegistrationMatchGap { ExternalProvenance, ParentUnavailable, NoReturnedChild, ContextOrEntryMismatch, SourceKindMismatch }
internal class HolderRegistrationMismatch<G : Any>(val registration: HolderManagerRegistration<G>, val gap: HolderRegistrationMatchGap)
internal enum class HolderContextOperationState { Admitted, Completed, Unknown, DeniedBeforeHelperEffects }
internal sealed interface HolderContextOperationIdentity
internal class HolderContextOperationSnapshot(
    val identity: HolderContextOperationIdentity,
    val kind: HolderContextOperationKind,
    val context: HolderConstructionContext?,
    val entry: HolderEntryOrigin?,
    val state: HolderContextOperationState, val failure: HolderOperationFailureKind?
)
internal class HolderRetainedInputSnapshot<G : Any>(
    val manager: G?, val product: Any?, val context: HolderConstructionContext?,
    val state: HolderContextOperationState, val assignedRegistration: HolderManagerRegistration<G>?,
    val operation: HolderContextOperationIdentity?, val entry: HolderEntryOrigin?, val refusal: HolderContextOperationGap?
)
internal enum class HolderCensusGap {
    InvalidSubject, NotClosed, ActiveEntry, DispatchedConstruction, UnknownConstruction,
    PendingOperation, UnknownOperation, UnattributedChild, UnknownChild, ExternalInput,
    RefusedAllocatedInput, UnmatchedConstructedRegistration, PropagationUnverified,
    MissingCollector, WitnessChanged
}
/** Original admitted identities only; never a writer/drain acknowledgment or admission permit. */
internal sealed interface HolderAdmittedManagerFrontier<out G : Any> { val managers: List<G> }
internal sealed interface HolderGossipCensusCapture<out G : Any> {
    class Sealed<G : Any>(val frontier: HolderAdmittedManagerFrontier<G>) : HolderGossipCensusCapture<G>
    class Partial<G : Any>(
        val snapshot: HolderGossipSubsetSnapshot<G>,
        val gaps: List<HolderCensusGap>, val operations: List<HolderContextOperationSnapshot>,
        val retainedInputs: List<HolderRetainedInputSnapshot<G>>, val registrationGaps: List<HolderRegistrationMismatch<G>>
    ) : HolderGossipCensusCapture<G>
    class Invalid(val gap: HolderCensusGap) : HolderGossipCensusCapture<Nothing>
}

private interface OriginalGossipCollector {
    fun observe(subject: HolderPanicSubject, exactUnified: Any): HolderGossipSubsetCapture<GossipSyncManager>
    fun frontier(subject: HolderPanicSubject, exactUnified: Any): HolderGossipCensusCapture<GossipSyncManager>
    fun validate(subject: HolderPanicSubject, exactUnified: Any, frontier: HolderAdmittedManagerFrontier<GossipSyncManager>): Boolean
}
private class TypedOriginalGossipCollector<B : Any, U : Any>(
    private val original: MeshServiceHolderOwner<B, U, GossipSyncManager>
) : OriginalGossipCollector {
    override fun observe(subject: HolderPanicSubject, exactUnified: Any) =
        original.observeOriginalGossipSubset(subject, exactUnified)
    override fun frontier(subject: HolderPanicSubject, exactUnified: Any) = original.captureAdmittedManagerFrontier(subject, exactUnified)
    override fun validate(subject: HolderPanicSubject, exactUnified: Any, frontier: HolderAdmittedManagerFrontier<GossipSyncManager>) =
        original.validateAdmittedManagerFrontier(subject, exactUnified, frontier)
}
private sealed interface BoundGossipCollector {
    data object Missing : BoundGossipCollector
    class Installed(val original: OriginalGossipCollector) : BoundGossipCollector
}

/** The collector and admission actor are the same original concrete owner. */
internal fun <B : Any, U : Any> captureHolderPanicBindingWithGossip(
    original: MeshServiceHolderOwner<B, U, GossipSyncManager>,
    exactUnified: Any
): HolderPanicBinding = when (val captured = original.captureUnifiedPanicSubject(exactUnified)) {
    is HolderPanicSubjectCapture.Captured -> CapturedHolderPanicBinding(
        original, captured.subject, exactUnified,
        BoundGossipCollector.Installed(TypedOriginalGossipCollector(original)),
        BoundProcessorCollector.Installed(TypedOriginalProcessorCollector(original))
    )
    is HolderPanicSubjectCapture.Unconfirmed -> UnconfirmedHolderPanicBinding(captured.gap)
}

internal fun observeHolderOriginalGossipSubset(
    binding: HolderPanicBinding,
    exactOriginalMesh: Any
): HolderGossipSubsetCapture<GossipSyncManager> = when (binding) {
    is UnconfirmedHolderPanicBinding -> HolderGossipSubsetCapture.Unconfirmed(
        HolderGossipCaptureGap.InvalidSubject(binding.gap)
    )
    is CapturedHolderPanicBinding -> if (binding.product !== exactOriginalMesh) {
        HolderGossipSubsetCapture.Unconfirmed(HolderGossipCaptureGap.InvalidSubject(HolderPanicCaptureGap.ProductMismatch))
    } else when (val collector = binding.gossipCollector) {
        BoundGossipCollector.Missing -> HolderGossipSubsetCapture.Unconfirmed(HolderGossipCaptureGap.MissingCollector)
        is BoundGossipCollector.Installed -> collector.original.observe(binding.subject, binding.product)
    }
}

internal fun captureHolderAdmittedManagerFrontier(binding: HolderPanicBinding, exactOriginalMesh: Any): HolderGossipCensusCapture<GossipSyncManager> =
    when (binding) {
        is UnconfirmedHolderPanicBinding -> HolderGossipCensusCapture.Invalid(HolderCensusGap.InvalidSubject)
        is CapturedHolderPanicBinding -> if (binding.product !== exactOriginalMesh) {
            HolderGossipCensusCapture.Invalid(HolderCensusGap.InvalidSubject)
        } else when (val collector = binding.gossipCollector) {
            BoundGossipCollector.Missing -> HolderGossipCensusCapture.Invalid(HolderCensusGap.MissingCollector)
            is BoundGossipCollector.Installed -> collector.original.frontier(binding.subject, binding.product)
        }
    }

internal fun validateHolderAdmittedManagerFrontier(
    binding: HolderPanicBinding, exactOriginalMesh: Any, frontier: HolderAdmittedManagerFrontier<GossipSyncManager>
): Boolean = when (binding) {
    is UnconfirmedHolderPanicBinding -> false
    is CapturedHolderPanicBinding -> binding.product === exactOriginalMesh && when (val collector = binding.gossipCollector) {
        BoundGossipCollector.Missing -> false
        is BoundGossipCollector.Installed -> collector.original.validate(binding.subject, binding.product, frontier)
    }
}

/** Scalar evidence has no participant, caller, Job, product or preparation capability. */
internal sealed interface HolderProcessorChildIdentity
internal sealed interface HolderProcessorParentIdentity
private enum class HolderProcessorScope { BluetoothOnly, BluetoothAndCore }
internal enum class HolderProcessorSource { BluetoothService, WifiCore }
internal enum class HolderProcessorState { Dispatched, Returned, Unknown }
internal enum class HolderProcessorGap {
    InvalidSubject, MissingCollector, NotClosed, ActiveEntry, DispatchedParent, UnknownParent,
    PendingOperation, UnknownOperation, PropagationUnverified, ExternalInput, RefusedAllocatedInput,
    MissingProcessor, UnknownProcessor, DuplicateProcessor, ParentMismatch, WitnessChanged
}
internal data class HolderProcessorRow(
    val child: HolderProcessorChildIdentity, val parent: HolderProcessorParentIdentity,
    val root: com.bitchat.android.mesh.OriginalProcessorRootIdentity?, val state: HolderProcessorState,
    val source: HolderProcessorSource
)
/** Call-scoped authority; never stored in a retirement report or restored from scalar rows. */
internal sealed interface HolderProcessorBatch {
    val roots: List<com.bitchat.android.mesh.OriginalProcessorBinding>
    val rows: List<HolderProcessorRow>
    val gaps: List<HolderProcessorGap>
}
internal sealed interface HolderProcessorCapture {
    class Observed(val batch: HolderProcessorBatch) : HolderProcessorCapture
    class Invalid(val gap: HolderProcessorGap) : HolderProcessorCapture
}
private interface OriginalProcessorCollector {
    fun capture(subject: HolderPanicSubject, product: Any): HolderProcessorCapture
    fun validate(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch): Boolean
    fun captureBluetoothAndCore(subject: HolderPanicSubject, product: Any): HolderProcessorCapture
    fun validateBluetoothAndCore(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch): Boolean
}
private class TypedOriginalProcessorCollector<B : Any, U : Any, G : Any>(
    private val original: MeshServiceHolderOwner<B, U, G>
) : OriginalProcessorCollector {
    override fun capture(subject: HolderPanicSubject, product: Any) = original.captureOriginalBluetoothProcessors(subject, product)
    override fun validate(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch) =
        original.validateOriginalBluetoothProcessors(subject, product, batch)
    override fun captureBluetoothAndCore(subject: HolderPanicSubject, product: Any) =
        original.captureOriginalBluetoothAndCoreProcessors(subject, product)
    override fun validateBluetoothAndCore(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch) =
        original.validateOriginalBluetoothAndCoreProcessors(subject, product, batch)
}
private sealed interface BoundProcessorCollector {
    data object Missing : BoundProcessorCollector
    class Installed(val original: OriginalProcessorCollector) : BoundProcessorCollector
}
internal fun captureHolderOriginalBluetoothProcessors(binding: HolderPanicBinding, product: Any): HolderProcessorCapture =
    when (binding) {
        is UnconfirmedHolderPanicBinding -> HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        is CapturedHolderPanicBinding -> if (binding.product !== product) HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        else when (val collector = binding.processorCollector) {
            BoundProcessorCollector.Missing -> HolderProcessorCapture.Invalid(HolderProcessorGap.MissingCollector)
            is BoundProcessorCollector.Installed -> collector.original.capture(binding.subject, product)
        }
    }
internal fun validateHolderOriginalBluetoothProcessors(
    binding: HolderPanicBinding, product: Any, batch: HolderProcessorBatch
): Boolean = when (binding) {
    is UnconfirmedHolderPanicBinding -> false
    is CapturedHolderPanicBinding -> binding.product === product && when (val collector = binding.processorCollector) {
        BoundProcessorCollector.Missing -> false
        is BoundProcessorCollector.Installed -> collector.original.validate(binding.subject, product, batch)
    }
}

internal fun captureHolderOriginalBluetoothAndCoreProcessors(binding: HolderPanicBinding, product: Any): HolderProcessorCapture =
    when (binding) {
        is UnconfirmedHolderPanicBinding -> HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        is CapturedHolderPanicBinding -> if (binding.product !== product) HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        else when (val collector = binding.processorCollector) {
            BoundProcessorCollector.Missing -> HolderProcessorCapture.Invalid(HolderProcessorGap.MissingCollector)
            is BoundProcessorCollector.Installed -> collector.original.captureBluetoothAndCore(binding.subject, product)
        }
    }
internal fun validateHolderOriginalBluetoothAndCoreProcessors(
    binding: HolderPanicBinding, product: Any, batch: HolderProcessorBatch
): Boolean = when (binding) {
    is UnconfirmedHolderPanicBinding -> false
    is CapturedHolderPanicBinding -> binding.product === product && when (val collector = binding.processorCollector) {
        BoundProcessorCollector.Missing -> false
        is BoundProcessorCollector.Installed -> collector.original.validateBluetoothAndCore(binding.subject, product, batch)
    }
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
    private var censusRevision = Any()
    private var propagationUnverified = false
    private class OperationIdentity : HolderContextOperationIdentity
    private class OperationRecord(
        val identity: OperationIdentity, val kind: HolderContextOperationKind,
        val context: HolderConstructionContext?, val entry: HolderEntryOrigin?,
        var state: HolderContextOperationState = HolderContextOperationState.Admitted,
        var failure: HolderOperationFailureKind? = null
    )
    private class InputRecord<G : Any>(
        val manager: G?, val product: Any?, val context: HolderConstructionContext?,
        var state: HolderContextOperationState = HolderContextOperationState.Admitted,
        var registration: HolderManagerRegistration<G>? = null,
        var operation: HolderContextOperationIdentity? = null,
        var entry: HolderEntryOrigin? = null,
        var refusal: HolderContextOperationGap? = null
    )
    private class ContextRefusal(kind: HolderContextOperationKind, gap: HolderContextOperationGap) : HolderContextOperationRefusal(kind, gap)
    private val operations = mutableListOf<OperationRecord>()
    private val retainedInputs = mutableListOf<InputRecord<G>>()
    private class AdmittedFrontier<G : Any>(
        val anchor: Any, val subject: HolderPanicSubject, val product: Any, val revision: Any,
        val rows: List<Any>, managers: List<G>
    ) : HolderAdmittedManagerFrontier<G> {
        private val originalManagers = managers.toList()
        override val managers: List<G> get() = originalManagers.toList()
    }
    // All identities originate in private stored records; no generic equals/getter is invoked.
    private fun censusRows(): List<Any> = buildList {
        constructions.values.forEach { add(it.context) }
        gossipChildren.values.forEach { add(it.ticket) }
        registrations.forEach { add(it) }
        operations.forEach { add(it.identity) }
        retainedInputs.forEach { add(it) }
    }
    private fun changed() { censusRevision = Any() }
    private fun failureKind(failure: Throwable) = when (failure) {
        is HolderContextOperationRefusal -> HolderOperationFailureKind.PolicyRefusal
        is java.util.concurrent.CancellationException -> HolderOperationFailureKind.CancellationThrown
        else -> HolderOperationFailureKind.OrdinaryThrow
    }
    private fun constructionFor(context: HolderConstructionContext): ConstructionRecord<G>? =
        constructions.values.firstOrNull { it.context === context }
    private fun notePolicySignal() = synchronized(registryMonitor) { propagationUnverified = true; changed() }
    // Called only under the RAM monitor, before any helper effect or legacy wait.
    private fun refuse(kind: HolderContextOperationKind, gap: HolderContextOperationGap, context: HolderConstructionContext?): Nothing {
        if (activeEntries.isNotEmpty() || constructions.values.any { it.outcome === HolderConstructionOutcome.Dispatched } ||
            operations.any { it.state == HolderContextOperationState.Admitted }) propagationUnverified = true
        operations += OperationRecord(OperationIdentity(), kind, context, null, HolderContextOperationState.DeniedBeforeHelperEffects)
        changed()
        throw ContextRefusal(kind, gap)
    }
    private fun admit(kind: HolderContextOperationKind, context: HolderConstructionContext?, expected: HolderConstructionKind? = null): OperationRecord {
        val construction = if (context == null) null else constructionFor(context)
        if (context != null && construction == null) refuse(kind, HolderContextOperationGap.ForeignContext, context)
        if (expected != null && construction?.kind != expected) refuse(kind, HolderContextOperationGap.WrongContextKind, context)
        if (context == null && constructionAdmissionClosed) refuse(kind, HolderContextOperationGap.UnattributedAfterClose, null)
        if (constructionAdmissionClosed && construction != null && construction.outcome !== HolderConstructionOutcome.Dispatched) {
            refuse(kind, HolderContextOperationGap.EndedContextAfterClose, context)
        }
        return OperationRecord(OperationIdentity(), kind, context, construction?.entry).also { operations += it; changed() }
    }
    private fun finish(operation: OperationRecord, completed: Boolean, input: InputRecord<G>? = null, failure: HolderOperationFailureKind? = null) = synchronized(registryMonitor) {
        if (operation.state != HolderContextOperationState.Admitted) {
            propagationUnverified = true
            operation.failure = HolderOperationFailureKind.Protocol
        } else {
            operation.state = if (completed) HolderContextOperationState.Completed else HolderContextOperationState.Unknown
            operation.failure = if (completed) null else failure ?: HolderOperationFailureKind.OrdinaryThrow
            input?.state = operation.state
        }
        changed()
    }
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
        val processorParent: HolderProcessorParentIdentity = ProcessorParentIdentity(),
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
        var outcome: HolderGossipChildOutcome<G> = HolderGossipChildOutcome.Dispatched,
        var returnedManager: G? = null
    )
    private class GossipManagerReference<G : Any>(
        override val ticket: HolderGossipChildTicket,
        override val source: HolderGossipSource,
        override val provenance: HolderGossipProvenance,
        override val manager: G
    ) : HolderGossipManagerReference<G>
    private val gossipChildren = linkedMapOf<HolderGossipChildTicket, GossipChildRecord<G>>()

    private class ProcessorChildIdentity : HolderProcessorChildIdentity
    private class ProcessorParentIdentity : HolderProcessorParentIdentity
    private class ProcessorChild(
        val identity: HolderProcessorChildIdentity, val context: HolderConstructionContext,
        val entry: HolderEntryOrigin, val parent: HolderProcessorParentIdentity,
        val source: HolderProcessorSource,
        var state: HolderProcessorState = HolderProcessorState.Dispatched,
        var allocated: com.bitchat.android.mesh.PacketProcessor? = null,
        var binding: com.bitchat.android.mesh.OriginalProcessorBinding? = null,
        var rootIdentity: com.bitchat.android.mesh.OriginalProcessorRootIdentity? = null
    )
    private val processorChildren = mutableListOf<ProcessorChild>()
    private class ProcessorBatch(
        val anchor: Any, val subject: HolderPanicSubject, val product: Any, val revision: Any,
        val scope: HolderProcessorScope,
        private val bindings: List<com.bitchat.android.mesh.OriginalProcessorBinding>,
        rows: List<HolderProcessorRow>, gaps: List<HolderProcessorGap>
    ) : HolderProcessorBatch {
        private val originalRows = rows.toList()
        private val originalGaps = gaps.toList()
        val eligible: Boolean = originalGaps.isEmpty()
        override val roots: List<com.bitchat.android.mesh.OriginalProcessorBinding> get() = bindings.toList()
        override val rows: List<HolderProcessorRow> get() = originalRows.toList()
        override val gaps: List<HolderProcessorGap> get() = originalGaps.toList()
    }

    /** Registry admission only; inherited constructor serialization remains unchanged. */
    fun constructBluetoothProcessor(
        context: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.PacketProcessor
    ): com.bitchat.android.mesh.PacketProcessor = constructProcessor(
        context, HolderConstructionKind.BLUETOOTH, HolderProcessorSource.BluetoothService, creator)

    fun constructWifiProcessor(
        context: HolderConstructionContext, creator: () -> com.bitchat.android.mesh.PacketProcessor
    ): com.bitchat.android.mesh.PacketProcessor = constructProcessor(
        context, HolderConstructionKind.WIFI, HolderProcessorSource.WifiCore, creator)

    private fun constructProcessor(
        context: HolderConstructionContext, expected: HolderConstructionKind, source: HolderProcessorSource,
        creator: () -> com.bitchat.android.mesh.PacketProcessor
    ): com.bitchat.android.mesh.PacketProcessor {
        val (operation, child) = synchronized(registryMonitor) {
            val operation = admit(HolderContextOperationKind.PacketProcessorCreator, context, expected)
            val parent = checkNotNull(constructionFor(context))
            val child = ProcessorChild(ProcessorChildIdentity(), context, checkNotNull(operation.entry), parent.processorParent, source)
            processorChildren += child
            changed()
            operation to child
        }
        // No registry lock surrounds allocation or the processor-owned opaque identity read.
        val allocated = try { creator() } catch (failure: Throwable) {
            synchronized(registryMonitor) { child.state = HolderProcessorState.Unknown; changed() }
            finish(operation, false, failure = failureKind(failure))
            throw failure
        }
        synchronized(registryMonitor) { child.allocated = allocated; changed() }
        val binding = try { allocated.captureOriginalProcessorBinding() } catch (failure: Throwable) {
            synchronized(registryMonitor) { child.state = HolderProcessorState.Unknown; changed() }
            finish(operation, false, failure = failureKind(failure))
            throw failure
        }
        val identity = binding.identity
        synchronized(registryMonitor) {
            child.binding = binding
            child.rootIdentity = identity
            child.state = HolderProcessorState.Returned
            operation.state = HolderContextOperationState.Completed
            changed()
        }
        return allocated
    }

    /** The old entry point observes only original Bluetooth processor children. */
    fun captureOriginalBluetoothProcessors(subject: HolderPanicSubject, product: Any): HolderProcessorCapture =
        captureOriginalProcessors(subject, product, HolderProcessorScope.BluetoothOnly)

    fun captureOriginalBluetoothAndCoreProcessors(subject: HolderPanicSubject, product: Any): HolderProcessorCapture =
        captureOriginalProcessors(subject, product, HolderProcessorScope.BluetoothAndCore)

    private fun captureOriginalProcessors(
        subject: HolderPanicSubject, product: Any, scope: HolderProcessorScope
    ): HolderProcessorCapture = synchronized(registryMonitor) {
        if (subject !is UnifiedPanicSubject || subject.ownerAnchor !== ownerAnchor) {
            return@synchronized HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        }
        val unified = constructionFor(subject.context)
        if (unified == null || unified.kind != HolderConstructionKind.UNIFIED ||
            (unified.outcome as? HolderConstructionOutcome.Returned)?.product !== product ||
            constructions.values.count { it.kind == HolderConstructionKind.UNIFIED &&
                (it.outcome as? HolderConstructionOutcome.Returned)?.product === product } != 1) {
            return@synchronized HolderProcessorCapture.Invalid(HolderProcessorGap.InvalidSubject)
        }
        if (!constructionAdmissionClosed) return@synchronized HolderProcessorCapture.Invalid(HolderProcessorGap.NotClosed)
        val gaps = mutableListOf<HolderProcessorGap>()
        if (activeEntries.isNotEmpty()) gaps += HolderProcessorGap.ActiveEntry
        if (constructions.values.any { it.outcome === HolderConstructionOutcome.Dispatched }) gaps += HolderProcessorGap.DispatchedParent
        if (constructions.values.any { it.outcome is HolderConstructionOutcome.Unknown }) gaps += HolderProcessorGap.UnknownParent
        if (operations.any { it.state == HolderContextOperationState.Admitted }) gaps += HolderProcessorGap.PendingOperation
        if (operations.any { it.state == HolderContextOperationState.Unknown }) gaps += HolderProcessorGap.UnknownOperation
        if (propagationUnverified) gaps += HolderProcessorGap.PropagationUnverified
        if (externalProducts.isNotEmpty() || retainedInputs.any { it.product != null }) gaps += HolderProcessorGap.ExternalInput
        if (retainedInputs.any { it.state == HolderContextOperationState.DeniedBeforeHelperEffects }) gaps += HolderProcessorGap.RefusedAllocatedInput
        val children = processorChildren.filter {
            scope == HolderProcessorScope.BluetoothAndCore || it.source == HolderProcessorSource.BluetoothService
        }
        val roots = mutableListOf<com.bitchat.android.mesh.OriginalProcessorBinding>()
        val seen = java.util.IdentityHashMap<com.bitchat.android.mesh.OriginalProcessorBinding, Unit>()
        children.forEach { child ->
            val parent = constructionFor(child.context)
            val expected = if (child.source == HolderProcessorSource.BluetoothService) HolderConstructionKind.BLUETOOTH else HolderConstructionKind.WIFI
            if (parent == null || parent.kind != expected || parent.entry !== child.entry ||
                parent.processorParent !== child.parent) gaps += HolderProcessorGap.ParentMismatch
            if (child.state != HolderProcessorState.Returned || child.binding == null || child.rootIdentity == null) gaps += HolderProcessorGap.UnknownProcessor
            child.binding?.let { binding ->
                if (seen.put(binding, Unit) == null) roots += binding else gaps += HolderProcessorGap.DuplicateProcessor
            }
        }
        val bluetooth = constructions.values.filter { it.kind == HolderConstructionKind.BLUETOOTH }
        val requiredParents = if (scope == HolderProcessorScope.BluetoothOnly) bluetooth else constructions.values.filter {
            it.kind == HolderConstructionKind.BLUETOOTH || it.kind == HolderConstructionKind.WIFI
        }
        requiredParents.forEach { parent ->
            val source = if (parent.kind == HolderConstructionKind.BLUETOOTH) HolderProcessorSource.BluetoothService else HolderProcessorSource.WifiCore
            if (children.count { it.context === parent.context && it.entry === parent.entry && it.source == source &&
                    it.state == HolderProcessorState.Returned && it.binding != null } != 1) gaps += HolderProcessorGap.MissingProcessor
        }
        if (bluetooth.none { (it.outcome as? HolderConstructionOutcome.Returned)?.product === unified.suppliedBluetooth }) {
            gaps += HolderProcessorGap.ParentMismatch
        }
        if (roots.isEmpty()) gaps += HolderProcessorGap.MissingProcessor
        HolderProcessorCapture.Observed(ProcessorBatch(ownerAnchor, subject, product, censusRevision, scope, roots.toList(),
            children.map { HolderProcessorRow(it.identity, it.parent, it.rootIdentity, it.state, it.source) }, gaps.distinct()))
    }

    fun validateOriginalBluetoothProcessors(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch): Boolean =
        validateOriginalProcessors(subject, product, batch, HolderProcessorScope.BluetoothOnly)

    fun validateOriginalBluetoothAndCoreProcessors(subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch): Boolean =
        validateOriginalProcessors(subject, product, batch, HolderProcessorScope.BluetoothAndCore)

    private fun validateOriginalProcessors(
        subject: HolderPanicSubject, product: Any, batch: HolderProcessorBatch, scope: HolderProcessorScope
    ): Boolean = synchronized(registryMonitor) {
            batch is ProcessorBatch && batch.scope == scope && batch.anchor === ownerAnchor && batch.subject === subject &&
                batch.product === product && batch.revision === censusRevision && batch.eligible && constructionAdmissionClosed
        }

    /** Records actual creator effects; operation admission remains independent of parent return. */
    fun constructGossipManager(parent: HolderGossipParent, source: HolderGossipSource, creator: () -> G): G {
        val (operation, record) = synchronized(registryMonitor) {
            val expected = if (source == HolderGossipSource.BLE_SERVICE) HolderConstructionKind.BLUETOOTH else HolderConstructionKind.WIFI
            val context = (parent as? HolderGossipParent.Constructed)?.context
            val operation = admit(HolderContextOperationKind.ManagerCreator, context, if (context == null) null else expected)
            val provenance = if (context == null) HolderGossipProvenance.Unattributed
                else HolderGossipProvenance.Constructed(context, checkNotNull(operation.entry))
            val ticket = GossipChildTicket()
            val child = GossipChildRecord<G>(ticket, source, provenance)
            gossipChildren[ticket] = child
            changed()
            operation to child
        }
        val manager = try { creator() } catch (failure: Throwable) {
            synchronized(registryMonitor) { record.outcome = HolderGossipChildOutcome.Unknown; changed() }
            finish(operation, false, failure = failureKind(failure))
            throw failure
        }
        synchronized(registryMonitor) {
            record.returnedManager = manager
            record.outcome = HolderGossipChildOutcome.Returned(GossipManagerReference(record.ticket, record.source, record.provenance, manager))
            operation.state = HolderContextOperationState.Completed
            changed()
        }
        return manager
    }

    fun gossipConstructionSnapshot(): List<HolderGossipChildSnapshot<G>> = synchronized(registryMonitor) {
        gossipChildren.values.map { HolderGossipChildSnapshot(it.ticket, it.source, it.provenance, it.outcome) }
    }

    /** One RAM-only O(n) view; accepted contexts can still publish later original managers. */
    fun observeOriginalGossipSubset(
        subject: HolderPanicSubject,
        exactUnified: Any
    ): HolderGossipSubsetCapture<G> = synchronized(registryMonitor) {
        if (subject !is UnifiedPanicSubject || subject.ownerAnchor !== ownerAnchor) {
            return@synchronized HolderGossipSubsetCapture.Unconfirmed(
                HolderGossipCaptureGap.InvalidSubject(HolderPanicCaptureGap.ForeignSubject)
            )
        }
        val record = constructionFor(subject.context)
        if (record == null || record.kind != HolderConstructionKind.UNIFIED ||
            (record.outcome as? HolderConstructionOutcome.Returned)?.product !== exactUnified) {
            return@synchronized HolderGossipSubsetCapture.Unconfirmed(
                HolderGossipCaptureGap.InvalidSubject(HolderPanicCaptureGap.InvalidReturnedUnified)
            )
        }
        if (!constructionAdmissionClosed) {
            return@synchronized HolderGossipSubsetCapture.Unconfirmed(HolderGossipCaptureGap.NotClosed)
        }
        // Both reentrant snapshot copies finish in this same transaction, without external calls.
        HolderGossipSubsetCapture.Observed(HolderGossipSubsetSnapshot(provenanceSnapshot(), gossipConstructionSnapshot()))
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
            val record = constructionFor(subject.context)
            if (record == null || record.kind != HolderConstructionKind.UNIFIED ||
                (record.outcome as? HolderConstructionOutcome.Returned)?.product !== exactUnified) {
                return@synchronized HolderConstructionCloseOutcome.Unconfirmed(HolderPanicCaptureGap.InvalidReturnedUnified)
            }
            if (!constructionAdmissionClosed) { constructionAdmissionClosed = true; changed() }
            HolderConstructionCloseOutcome.ClosedForConstruction
        }

    fun setGossipManager(provenance: HolderManagerProvenance, manager: G, signer: (BitchatPacket) -> BitchatPacket?) {
        val input = InputRecord(manager, null, (provenance as? HolderManagerProvenance.Constructed)?.context)
        val operation = synchronized(registryMonitor) {
            retainedInputs += input; changed()
            try { admit(HolderContextOperationKind.ManagerRegistration, input.context).also { input.operation = it.identity; input.entry = it.entry } }
            catch (failure: Throwable) { input.state = HolderContextOperationState.DeniedBeforeHelperEffects; input.refusal = (failure as? HolderContextOperationRefusal)?.gap; changed(); throw failure }
        }
        var completed = false
        var failureCategory: HolderOperationFailureKind? = null
        try {
            synchronized(legacyMonitor) {
                val previous = sharedGossipSyncManager
                if (previous !== manager) {
                    try { previous?.let(ports.stopGossip) }
                    catch (_: HolderContextOperationRefusal) { notePolicySignal() }
                    catch (_: Exception) { }
                }
                val registration = HolderManagerRegistration(manager, provenance, operation.entry, signer)
                synchronized(registryMonitor) {
                    registrations += registration
                    currentRegistration = registration
                    input.registration = registration
                    changed()
                }
                ports.installGossipDelegate(manager, TransportGossipDelegate(registration, ports.broadcast, ports.sendToPeer))
                if (activeGossipOwners.isNotEmpty()) ports.startGossip(manager)
            }
            completed = true
        } catch (failure: Throwable) {
            failureCategory = failureKind(failure)
            if (failure is HolderContextOperationRefusal) notePolicySignal()
            throw failure
        } finally { finish(operation, completed, input, failureCategory) }
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
                    try { ports.stopBluetooth(existing) }
                    catch (refused: HolderContextOperationRefusal) { notePolicySignal(); throw refused }
                    catch (e: Exception) {
                        log(HolderLogLevel.WARN, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = construct(entry, HolderConstructionKind.BLUETOOTH) { createBluetooth(it) }
                    log(HolderLogLevel.INFO, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (refused: HolderContextOperationRefusal) { notePolicySignal(); throw refused }
            catch (e: Exception) {
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
            changed()
        }
        try {
            val product = try {
                creator(context)
            } catch (failure: Throwable) {
                synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Unknown(HolderConstructionKind.WIFI); changed() }
                throw failure
            }
            synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Returned(product); changed() }
            return WifiAwareFactoryResult.Created(product)
        } finally {
            synchronized(registryMonitor) { activeEntries -= entry; changed() }
        }
    }

    /** Reuses the caller's entry even if Bluetooth initialization waits on a different factory. */
    fun ensureBluetoothForWifi(context: HolderWifiConstructionContext, createBluetooth: (HolderConstructionContext) -> B) {
        val operation = synchronized(registryMonitor) { admit(HolderContextOperationKind.BluetoothEnsure, context, HolderConstructionKind.WIFI) }
        var completed = false
        var failureCategory: HolderOperationFailureKind? = null
        try {
            synchronized(legacyMonitor) { getOrCreateLocked(checkNotNull(operation.entry), createBluetooth) }
            completed = true
        } catch (failure: Throwable) {
            failureCategory = failureKind(failure)
            if (failure is HolderContextOperationRefusal) notePolicySignal()
            throw failure
        } finally { finish(operation, completed, failure = failureCategory) }
    }

    /** One immutable registration read, kept separate from the preceding Bluetooth acquisition. */
    fun selectManagerForWifi(context: HolderWifiConstructionContext): HolderManagerRegistration<G>? = synchronized(registryMonitor) {
        val record = wifiRecord(context)
        check(record.selection === HolderWifiManagerSelection.NotSelected) { "Wi-Fi manager was already selected" }
        currentRegistration.also { record.selection = HolderWifiManagerSelection.Selected(it); changed() }
    }

    // Called only while registryMonitor is held; context identity is owner-issued and unforgeable.
    private fun wifiRecord(context: HolderWifiConstructionContext): ConstructionRecord<G> =
        requireNotNull(constructionFor(context)) { "Construction context does not belong to this holder" }.also {
            check(it.kind == HolderConstructionKind.WIFI) { "Construction context is not Wi-Fi" }
        }

    fun attach(service: B) {
        val input = InputRecord<G>(null, service, null)
        val operation = synchronized(registryMonitor) {
            retainedInputs += input; changed()
            try { admit(HolderContextOperationKind.ExternalAttach, null).also { input.operation = it.identity } }
            catch (failure: Throwable) { input.state = HolderContextOperationState.DeniedBeforeHelperEffects; input.refusal = (failure as? HolderContextOperationRefusal)?.gap; changed(); throw failure }
        }
        var completed = false
        var failureCategory: HolderOperationFailureKind? = null
        try {
            synchronized(legacyMonitor) {
                log(HolderLogLevel.DEBUG, "Attaching BluetoothMeshService to holder")
                synchronized(registryMonitor) { externalProducts[service] = Unit; changed() }
                meshService = service
                unifiedMeshService = null
            }
            completed = true
        } catch (failure: Throwable) {
            failureCategory = failureKind(failure)
            if (failure is HolderContextOperationRefusal) notePolicySignal()
            throw failure
        } finally { finish(operation, completed, input, failureCategory) }
    }

    fun clear(): Unit = synchronized(legacyMonitor) {
        log(HolderLogLevel.DEBUG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.let(ports.clearGossip) } catch (_: HolderContextOperationRefusal) { notePolicySignal() } catch (_: Exception) { }
        try { sharedGossipSyncManager?.let(ports.stopGossip) } catch (_: HolderContextOperationRefusal) { notePolicySignal() } catch (_: Exception) { }
        synchronized(registryMonitor) { currentRegistration = null; changed() }
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }

    private fun <T : Any> withEntry(action: (HolderEntryOrigin) -> T): HolderAcquisitionResult<T> {
        val entry = HolderEntryOrigin(epoch, HolderEntryProvenance.LegacyUnattributed)
        synchronized(registryMonitor) {
            if (constructionAdmissionClosed) return HolderAcquisitionResult.RefusedBeforeAcquisition
            activeEntries += entry
            changed()
        }
        return try {
            synchronized(legacyMonitor) { HolderAcquisitionResult.Available(action(entry)) }
        } finally {
            synchronized(registryMonitor) { activeEntries -= entry; changed() }
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
        synchronized(registryMonitor) { constructions[context] = record; changed() }
        val product = try {
            factory(context)
        } catch (failure: Throwable) {
            synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Unknown(kind); changed() }
            throw failure
        }
        // Record the return before caller logging can trigger the legacy retry.
        synchronized(registryMonitor) { record.outcome = HolderConstructionOutcome.Returned(product); changed() }
        return product
    }

    /** One atomic, fail-fast view of admitted identities, not all writers or a drain. */
    fun captureAdmittedManagerFrontier(subject: HolderPanicSubject, exactUnified: Any): HolderGossipCensusCapture<G> = synchronized(registryMonitor) {
        if (subject !is UnifiedPanicSubject || subject.ownerAnchor !== ownerAnchor) {
            return@synchronized HolderGossipCensusCapture.Invalid(HolderCensusGap.InvalidSubject)
        }
        val original = constructionFor(subject.context)
        if (original == null || original.kind != HolderConstructionKind.UNIFIED ||
            (original.outcome as? HolderConstructionOutcome.Returned)?.product !== exactUnified ||
            constructions.values.count { it.kind == HolderConstructionKind.UNIFIED &&
                (it.outcome as? HolderConstructionOutcome.Returned)?.product === exactUnified } != 1) {
            return@synchronized HolderGossipCensusCapture.Invalid(HolderCensusGap.InvalidSubject)
        }
        val gaps = mutableListOf<HolderCensusGap>()
        if (!constructionAdmissionClosed) gaps += HolderCensusGap.NotClosed
        if (activeEntries.isNotEmpty()) gaps += HolderCensusGap.ActiveEntry
        if (constructions.values.any { it.outcome === HolderConstructionOutcome.Dispatched }) gaps += HolderCensusGap.DispatchedConstruction
        if (constructions.values.any { it.outcome is HolderConstructionOutcome.Unknown }) gaps += HolderCensusGap.UnknownConstruction
        if (operations.any { it.state == HolderContextOperationState.Admitted }) gaps += HolderCensusGap.PendingOperation
        if (operations.any { it.state == HolderContextOperationState.Unknown }) gaps += HolderCensusGap.UnknownOperation
        if (propagationUnverified) gaps += HolderCensusGap.PropagationUnverified
        if (externalProducts.isNotEmpty() || retainedInputs.any { it.product != null }) gaps += HolderCensusGap.ExternalInput
        if (retainedInputs.any { it.state == HolderContextOperationState.DeniedBeforeHelperEffects }) gaps += HolderCensusGap.RefusedAllocatedInput
        if (gossipChildren.values.any { it.provenance === HolderGossipProvenance.Unattributed }) gaps += HolderCensusGap.UnattributedChild
        if (gossipChildren.values.any { it.outcome !is HolderGossipChildOutcome.Returned }) gaps += HolderCensusGap.UnknownChild
        val seen = java.util.IdentityHashMap<G, Unit>()
        val managers = mutableListOf<G>()
        gossipChildren.values.forEach { child -> child.returnedManager?.let { if (seen.put(it, Unit) == null) managers += it } }
        val registrationGaps = mutableListOf<HolderRegistrationMismatch<G>>()
        registrations.forEach { registration ->
            if (seen.put(registration.manager, Unit) == null) managers += registration.manager
            val provenance = registration.provenance as? HolderManagerProvenance.Constructed
            val parent = provenance?.let { constructionFor(it.context) }
            val parentKind = parent?.kind
            val children = gossipChildren.values.filter { it.returnedManager === registration.manager && it.outcome is HolderGossipChildOutcome.Returned }
            val origins = if (provenance == null || parent == null) emptyList() else children.filter {
                val origin = it.provenance as? HolderGossipProvenance.Constructed
                origin != null && origin.context === provenance.context && origin.entry === parent.entry && registration.entry === parent.entry
            }
            val mismatch = when {
                provenance == null -> HolderRegistrationMatchGap.ExternalProvenance
                parent?.outcome !is HolderConstructionOutcome.Returned -> HolderRegistrationMatchGap.ParentUnavailable
                children.isEmpty() -> HolderRegistrationMatchGap.NoReturnedChild
                origins.isEmpty() -> HolderRegistrationMatchGap.ContextOrEntryMismatch
                origins.none { (parentKind == HolderConstructionKind.BLUETOOTH && it.source == HolderGossipSource.BLE_SERVICE) ||
                    (parentKind == HolderConstructionKind.WIFI && it.source == HolderGossipSource.CORE_FALLBACK) } -> HolderRegistrationMatchGap.SourceKindMismatch
                else -> null
            }
            if (mismatch != null) { gaps += HolderCensusGap.UnmatchedConstructedRegistration; registrationGaps += HolderRegistrationMismatch(registration, mismatch) }
        }
        if (gaps.isNotEmpty()) HolderGossipCensusCapture.Partial(
            HolderGossipSubsetSnapshot(provenanceSnapshot(), gossipConstructionSnapshot()), gaps.toList(),
            operations.map { HolderContextOperationSnapshot(it.identity, it.kind, it.context, it.entry, it.state, it.failure) },
            retainedInputs.map { HolderRetainedInputSnapshot(it.manager, it.product, it.context, it.state, it.registration, it.operation, it.entry, it.refusal) }, registrationGaps.toList())
        else HolderGossipCensusCapture.Sealed(AdmittedFrontier(ownerAnchor, subject, exactUnified, censusRevision, censusRows(), managers.toList()))
    }

    fun validateAdmittedManagerFrontier(subject: HolderPanicSubject, exactUnified: Any, frontier: HolderAdmittedManagerFrontier<G>): Boolean = synchronized(registryMonitor) {
        if (frontier !is AdmittedFrontier<*> || frontier.anchor !== ownerAnchor || frontier.subject !== subject ||
            frontier.product !== exactUnified || frontier.revision !== censusRevision || !constructionAdmissionClosed) false
        else {
            val rows = censusRows()
            rows.size == frontier.rows.size && rows.indices.all { rows[it] === frontier.rows[it] }
        }
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
