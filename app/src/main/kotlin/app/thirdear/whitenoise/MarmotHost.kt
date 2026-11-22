package app.thirdear.whitenoise

import android.content.Context
import android.util.Log
import app.thirdear.extensions.config
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.DiscardFailedSendOutcomeFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.SecretStore
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Third Ear's own White Noise (Marmot) identity, through MarmotKit 0.18.0 (from I Hear You): it is
 * invited into a group like any contact, sends recordings there, and hands incoming messages on.
 * One runtime per process (MDK owns its root directory), opened when White Noise is turned on.
 */
object MarmotHost {
    private const val TAG = "ThirdEarWN"
    private const val OPEN_ATTEMPTS = 20
    private const val OPEN_RETRY_MS = 250L

    /** Timestamps above this are milliseconds; below, seconds. */
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    /** Public relays for the relay lists and key package; White Noise users reach us through these. */
    /**
     * White Noise's own relays come first: White Noise looks people up there, and an identity
     * whose relay lists and key packages are only on the public relays could not be invited
     * ("couldn't find a usable chat key"). The public ones keep it reachable for other clients.
     */
    val RELAYS = listOf(
        "wss://relay.eu.whitenoise.chat",
        "wss://relay.us.whitenoise.chat",
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nos.lol",
    )

    /**
     * A group as the chat list has it. [member] is MLS membership; [leaving] means a leave was
     * asked for and waits for the group's commit; [left] and [removed] are groups this account is
     * no longer in, kept until the user removes them from the list.
     */
    data class Group(
        val id: String,
        val name: String,
        val pending: Boolean,
        val member: Boolean,
        val leaving: Boolean = false,
        val left: Boolean = false,
        val removed: Boolean = false,
    ) {
        /** In the group and staying: can be sent to and shows its settings. */
        val joined get() = member && !pending && !leaving
    }

    /** What a Leave did. */
    enum class LeaveResult { STARTED, ALREADY_LEAVING, LAST_ADMIN }

    /** A message from someone else in a joined group. */
    data class Incoming(
        val groupId: String,
        val messageId: String,
        val sender: String,
        val senderName: String?,
        val text: String,
        val media: List<MediaAttachmentReferenceFfi>,
        val at: Long,
    )

    enum class Phase { OFF, STARTING, READY, FAILED }

    val phase = MutableStateFlow(Phase.OFF)
    val error = MutableStateFlow<String?>(null)
    val npub = MutableStateFlow<String?>(null)
    val groups = MutableStateFlow<List<Group>>(emptyList())

    /** The display name others see (kind:0), as last read or published. */
    val profileName = MutableStateFlow<String?>(null)

    /** Groups with a leave in flight (MDK keeps the request durably; this mirrors it for the list). */
    private val leavingIds = MutableStateFlow<Set<String>>(emptySet())

    @Volatile var onIncoming: ((Incoming) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var marmot: Marmot? = null
    private var account: String? = null
    private var accountHex: String? = null
    private var events: Job? = null
    private var nativeReady = false

    /** MarmotKit ships arm64 only; elsewhere White Noise is unavailable. */
    fun supported(): Boolean = android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /**
     * Opens the runtime. Not cancellable: a caller's scope going away mid-start (a screen closed)
     * must not leave a runtime open but unreferenced, which holds MDK's root and makes every
     * later start fail with RuntimeBusy until the process ends.
     */
    suspend fun start(context: Context) = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            if (marmot != null) return@withLock
            phase.value = Phase.STARTING
            error.value = null
            var opened: Marmot? = null
            try {
                check(supported()) { "White Noise needs a 64-bit ARM phone" }
                if (!nativeReady) {
                    MarmotAndroid.initialize(context.applicationContext)
                    nativeReady = true
                }
                val root = File(context.noBackupFilesDir, "marmot")
                startOverAfterDowngrade(context, root)
                root.mkdirs()
                val options = MarmotOptions(
                    clientName = "third-ear-android",
                    secretStore = KvSecretStore(SecureKv(context)),
                    // White Noise's preset: its media servers, recovery relays and key-package preferences
                    deployment = marmotDeploymentWhiteNoise(),
                )
                var attempt = 0
                while (opened == null) {
                    try {
                        opened = Marmot.newWithConfiguration(root.path, RELAYS, options)
                    } catch (e: MarmotKitException.RuntimeBusy) {
                        // a previous runtime of this process is still closing its root
                        if (++attempt > OPEN_ATTEMPTS) throw e
                        delay(OPEN_RETRY_MS)
                    }
                }
                val m = opened!!
                m.start()
                marmot = m
                val acct = ensureAccount(m, context.config.whiteNoiseName ?: DEFAULT_NAME)
                account = acct.label
                accountHex = acct.accountIdHex
                npub.value = m.npub(acct.accountIdHex)
                profileName.value = runCatching { m.userProfile(acct.accountIdHex) }.getOrNull()?.let { it.displayName ?: it.name }
                refreshGroups()
                events = scope.launch { pump(m) }
                phase.value = Phase.READY
                Log.i(TAG, "ready as ${npub.value}")
                val label = acct.label
                scope.launch { ensureKeyPackage(context.applicationContext, m, label) }
                scope.launch { findPendingLeaves(m, label) }
            } catch (t: Throwable) {
                phase.value = Phase.FAILED
                // some MarmotKit errors carry an empty message: name the error rather than show nothing
                error.value = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                Log.w(TAG, "start failed: ${error.value}", t)
                // close whatever was opened, also one that never became `marmot`
                (marmot ?: opened)?.let { r ->
                    runCatching { r.shutdownAndClose() }
                    runCatching { r.close() }
                }
                marmot = null
                events?.cancel()
                events = null
            }
        }
    }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            val m = marmot ?: return@withLock
            marmot = null
            events?.cancel()
            events = null
            runCatching { m.shutdownAndClose() }
            runCatching { m.close() }
            phase.value = Phase.OFF
        }
    }

    /** The identity's private key, for a backup the user keeps; plain nsec, no passphrase. */
    fun revealNsec(): String {
        val m = marmot ?: error("White Noise is not running")
        return m.revealNsec(account ?: error("no White Noise identity"))
    }

    /**
     * Replaces the identity with [nsec]. Group membership is MLS state on the device, not part of
     * the key: the old identity's groups are dropped, and groups have to invite the npub again.
     */
    suspend fun importNsec(context: Context, nsec: String) = withContext(Dispatchers.IO) {
        val key = nsec.trim()
        require(key.startsWith("nsec1")) { "not an nsec" }
        lock.withLock {
            val m = marmot ?: error("White Noise is not running")
            val old = m.listAccounts()
            val acct = m.login(key, RELAYS, RELAYS, RELAYS)
            for (a in old) if (a.accountIdHex != acct.accountIdHex) runCatching { m.removeAccount(a.label) }
            Log.i(TAG, "identity replaced by import")
        }
        stop()
        start(context)
    }

    /**
     * Others can invite Third Ear only when a key package of its npub is on the relays. An
     * identity whose first start broke off after it was created (or whose package expired) has
     * none, and White Noise then cannot check it ("couldn't finish checking whether this person can
     * be added"). Publish one whenever the relays show none; let MarmotKit's maintenance rotate it.
     */
    private suspend fun ensureKeyPackage(context: Context, m: Marmot, label: String) {
        runCatching {
            val prefs = context.getSharedPreferences("third_ear_marmot", Context.MODE_PRIVATE)
            val built = app.thirdear.BuildConfig.MARMOTKIT_VERSION
            // identities made before White Noise's relays were in the list: publish the lists again
            val lists = runCatching { m.accountRelayLists(label) }.getOrNull()
            if (lists != null && !lists.nip65.relays.toSet().containsAll(RELAYS)) {
                Log.i(TAG, "publishing relay lists with White Noise's relays")
                m.publishRelayLists(label, RELAYS, RELAYS, RELAYS)
                // and a key package where White Noise looks for it
                m.rotateKeyPackage(label)
                prefs.edit().putString(KEY_PACKAGE_BUILD, built).apply()
            }
            val onRelays = m.accountKeyPackages(label, RELAYS)
            Log.i(TAG, "key packages on the relays: ${onRelays.size} (MarmotKit $built)")
            when {
                onRelays.isEmpty() -> {
                    Log.i(TAG, "no key package on the relays, publishing one")
                    m.publishNewKeyPackage(label)
                }
                // a package from an older MDK may not be one newer White Noise apps can invite
                // (0.14.0's lack the account-devices component that groups made by 0.15+ require;
                // 0.18.0's, with device groups always on, also advertise the device-roster component)
                prefs.getString(KEY_PACKAGE_BUILD, null) != built -> {
                    Log.i(TAG, "MarmotKit changed, rotating the key package")
                    m.rotateKeyPackage(label)
                }
            }
            prefs.edit().putString(KEY_PACKAGE_BUILD, built).apply()
            // MDK (0.15+) also replaces its key package every 6 to 7 days while it runs
            m.runDueMaintenance(label)
        }.onFailure { Log.w(TAG, "key package check failed", it) }
    }

    private const val KEY_PACKAGE_BUILD = "key_package_marmotkit"

    /**
     * MDK migrates its store forward only (0.17.0 took a 0.14.0 account from schema 116 to 207,
     * 0.18.0 takes it on to 216 on first open). When an older MarmotKit finds a store a newer one
     * wrote (say a 0.17.0 build installed over a 0.18.0 one), it refuses to open it
     * (UnsupportedSchemaVersion): White Noise starts over with a fresh identity, which is fine
     * while it is in no group.
     */
    private fun startOverAfterDowngrade(context: Context, root: File) {
        val prefs = context.getSharedPreferences("third_ear_marmot", Context.MODE_PRIVATE)
        val wrote = prefs.getString(KEY_PACKAGE_BUILD, null) ?: return
        val built = app.thirdear.BuildConfig.MARMOTKIT_VERSION
        if (!isOlder(built, wrote)) return
        Log.w(TAG, "MarmotKit went back from $wrote to $built: starting White Noise over with a fresh identity")
        root.deleteRecursively()
        SecureKv(context).clear()
        prefs.edit().clear().commit()
    }

    /** "0.14.0" is older than "0.15.0-nightly.20261029"; pre-release suffixes are ignored. */
    internal fun isOlder(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d < 0
        }
        return false
    }

    /** The name a new identity starts with; Setup can change it. */
    const val DEFAULT_NAME = "Third Ear"

    /** Leaves asked for before this start (MDK keeps them across restarts): show them as leaving. */
    private suspend fun findPendingLeaves(m: Marmot, acct: String) {
        val pending = groups.value.filter { it.member }.mapNotNull { g ->
            runCatching { m.groupManagementState(acct, g.id) }.getOrNull()?.takeIf { it.leaveRequestPending }?.let { g.id }
        }
        if (pending.isNotEmpty()) {
            leavingIds.value = leavingIds.value + pending
            refreshGroups()
        }
        settleBlockedLeaves(m, acct)
        logChangesNotDelivered(m, acct)
    }

    /**
     * A leave accepted while another admin left too can leave Third Ear the last admin: the leave
     * never finishes (MDK neither retries nor reverses it) and the row would read "leaving" for
     * good. Cancel it, so the group is joined again; the next Leave then offers removing it from
     * this phone, as for any last admin. A leave MDK no longer holds (0.18 gives up a group change
     * that reached no relay after ten minutes) stops reading "leaving" too.
     */
    private suspend fun settleBlockedLeaves(m: Marmot, acct: String) {
        val settled = leavingIds.value.filter { id ->
            val state = runCatching { m.groupManagementState(acct, id) }.getOrNull()
            when {
                state == null -> false
                state.leaveBlocked && state.canCancelLeave && runCatching { m.cancelLeave(acct, id) }.isSuccess -> {
                    Log.i(TAG, "leave blocked (last admin): cancelled")
                    true
                }

                !state.leaveRequestPending && !state.leaveBlocked -> {
                    Log.i(TAG, "leave no longer pending")
                    true
                }

                else -> false
            }
        }
        if (settled.isNotEmpty()) {
            leavingIds.value = leavingIds.value - settled.toSet()
            refreshGroups()
        }
    }

    /**
     * Group changes MDK gave up after their call returned (0.18: no relay took them). Third Ear
     * has no screen for them; logged for diagnosis, and kept by MDK until acknowledged.
     */
    private suspend fun logChangesNotDelivered(m: Marmot, acct: String) {
        for (g in groups.value.filter { it.member }) {
            runCatching { m.groupChangesNotDelivered(acct, g.id) }.getOrNull().orEmpty().forEach {
                Log.w(TAG, "group change not delivered: ${it.intentKind} (${it.reason})")
            }
        }
    }

    /**
     * Publishes a new display name (kind:0), keeping the rest of the profile. Members of the
     * groups see it from then on; their apps may take a while to fetch it.
     */
    suspend fun rename(name: String) = io { m, acct ->
        val n = name.trim()
        require(n.isNotEmpty()) { "empty name" }
        val hex = accountHex ?: error("no White Noise identity")
        val current = runCatching { m.userProfile(hex) }.getOrNull()
            ?: UserProfileMetadataFfi(null, null, ABOUT, null, null, null, null)
        val published = m.publishUserProfileUsingAccountRelays(acct, current.copy(name = n, displayName = n))
        profileName.value = published.displayName ?: published.name ?: n
    }

    private const val ABOUT = "Voice recorder"

    private suspend fun ensureAccount(m: Marmot, name: String): AccountSummaryFfi {
        val existing = m.listAccounts().firstOrNull()
        return when {
            existing == null -> {
                Log.i(TAG, "creating identity")
                // publishes the relay lists (10002, 10050 inbox), the profile and the first key package
                val profile = UserProfileMetadataFfi(name, name, ABOUT, null, null, null, null)
                runCatching { m.createIdentityWithInitialProfile(profile, RELAYS, RELAYS, RELAYS).account }
                    .getOrElse { m.createIdentity(RELAYS, RELAYS, RELAYS) }
            }

            existing.signedOut -> m.signInAccount(existing.label)
            else -> existing
        }
    }

    private suspend fun pump(m: Marmot) {
        val sub = m.subscribeEvents()
        try {
            while (scope.isActive) onEvent(m, sub.next() ?: break)
        } catch (t: Throwable) {
            Log.w(TAG, "event stream ended: ${t.javaClass.simpleName}")
        } finally {
            runCatching { sub.close() }
        }
    }

    private suspend fun onEvent(m: Marmot, e: MarmotEventFfi) {
        when (e) {
            is MarmotEventFfi.MessageReceived -> onMessage(m, e.received.message)
            is MarmotEventFfi.GroupStateUpdated -> {
                refreshGroups()
                account?.takeIf { leavingIds.value.isNotEmpty() }?.let { settleBlockedLeaves(m, it) }
            }

            is MarmotEventFfi.GroupJoined, is MarmotEventFfi.WelcomeDeliveryPending,
            is MarmotEventFfi.ProjectionUpdated -> refreshGroups()

            is MarmotEventFfi.AccountError -> Log.w(TAG, "account error")
            else -> {}
        }
    }

    private suspend fun onMessage(m: Marmot, msg: dev.ipf.marmotkit.ReceivedMessageFfi) {
        val self = accountHex ?: return
        if (msg.sender == self || msg.kind != 9uL) return
        val acct = account ?: return
        // media references come resolved on the timeline record, not on the raw message
        val media = runCatching {
            m.timelineMessages(acct, latest(msg.groupIdHex, 30u))
                .messages.firstOrNull { it.messageIdHex == msg.messageIdHex }
                ?.media?.filterIsInstance<MediaAttachmentOutcomeFfi.Accepted>()?.map { it.reference }
        }.getOrNull().orEmpty()
        val at = msg.recordedAt.toLong().let { if (it > MILLIS_THRESHOLD) it else it * 1000 }
        val name = msg.senderDisplayName ?: runCatching { m.displayName(msg.sender) }.getOrNull()
        onIncoming?.invoke(Incoming(msg.groupIdHex, msg.messageIdHex, msg.sender, name, readable(m, msg.plaintext), media, at))
    }

    fun refreshGroups() {
        val m = marmot ?: return
        val acct = account ?: return
        groups.value = runCatching {
            // archived too: a group this account left may be archived, and must stay visible until removed
            val rows = m.chatList(acct, true)
            val member = rows.filter { it.selfMembership == SelfMembershipFfi.MEMBER }.map { it.groupIdHex }.toSet()
            // a leave is over once the membership flips
            leavingIds.value = leavingIds.value.intersect(member)
            rows.map {
                Group(
                    id = it.groupIdHex,
                    name = it.groupName.ifBlank { it.title },
                    pending = it.pendingConfirmation,
                    member = it.selfMembership == SelfMembershipFfi.MEMBER,
                    leaving = it.groupIdHex in leavingIds.value,
                    left = it.selfMembership == SelfMembershipFfi.LEFT,
                    removed = it.selfMembership == SelfMembershipFfi.REMOVED,
                )
            }
        }.getOrDefault(groups.value)
    }

    suspend fun accept(groupId: String) = io { m, acct ->
        m.acceptGroupInvite(acct, groupId)
        refreshGroups()
    }

    /**
     * An invite that makes Third Ear an admin (a White Noise direct chat makes both sides admins)
     * can be declined only after stepping down (MarmotKit 0.15+, else AdminCannotSelfRemove).
     * With device groups on (always, since 0.18) MDK steps a nonlast admin down itself and
     * reports no demote as required; should a decline still refuse, step down and decline again.
     * While the group's own last commit is unconfirmed a decline fails with GroupCommitPending.
     */
    suspend fun decline(groupId: String) = io { m, acct ->
        try {
            val state = runCatching { m.groupManagementState(acct, groupId) }.getOrNull()
            if (state?.requiresSelfDemoteBeforeLeave == true) m.selfDemoteAdmin(acct, groupId)
            try {
                m.declineGroupInvite(acct, groupId)
            } catch (_: MarmotKitException.AdminCannotSelfRemove) {
                m.selfDemoteAdmin(acct, groupId)
                m.declineGroupInvite(acct, groupId)
            }
        } finally {
            // a decline that timed out may still complete: show the state MDK has, not the old row
            refreshGroups()
        }
        Unit
    }

    /**
     * An MLS leave, sent to the group: the others see Third Ear go; it can come back only by a new
     * invite. MDK queues it until a commit removes this member, so the row turns "leaving" first.
     * An admin has to give up admin rights before leaving (MarmotKit 0.18 does it inside the leave
     * and no longer asks for it; the flag is still honoured); the last admin cannot leave at all, which
     * the caller turns into the choice of removing the group from this phone. A member's leave
     * fails with GroupCommitPending while the group's own last commit is unconfirmed.
     */
    suspend fun leave(groupId: String): LeaveResult = io { m, acct ->
        val state = runCatching { m.groupManagementState(acct, groupId) }.getOrNull()
        when {
            // an earlier leave that cannot finish (last admin): take it back, offer removing locally
            state?.leaveBlocked == true -> {
                if (state.canCancelLeave) runCatching { m.cancelLeave(acct, groupId) }
                leavingIds.value = leavingIds.value - groupId
                refreshGroups()
                LeaveResult.LAST_ADMIN
            }
            state?.leaveRequestPending == true -> {
                markLeaving(groupId)
                LeaveResult.ALREADY_LEAVING
            }
            state?.isLastAdmin == true -> LeaveResult.LAST_ADMIN
            else -> {
                if (state?.requiresSelfDemoteBeforeLeave == true) m.selfDemoteAdmin(acct, groupId)
                try {
                    m.leaveGroup(acct, groupId)
                } catch (_: MarmotKitException.LeaveAlreadyRequested) {
                    // the durable request from before: the same as having just asked
                }
                markLeaving(groupId)
                LeaveResult.STARTED
            }
        }
    }

    private fun markLeaving(groupId: String) {
        leavingIds.value = leavingIds.value + groupId
        refreshGroups()
    }

    /**
     * Drops the group from this phone without an MLS leave: protocol state is reset (old invites
     * to it are rejected, a new one works) and the chat row is deleted. For a group already left,
     * or one Third Ear cannot leave (last admin); the other members are not told.
     */
    suspend fun removeLocal(groupId: String) = io { m, acct ->
        val forgot = runCatching { m.forgetGroupLocal(acct, groupId) }
        val deleted = runCatching { m.deleteGroupLocal(acct, groupId) }
        leavingIds.value = leavingIds.value - groupId
        refreshGroups()
        if (forgot.isFailure && deleted.isFailure) throw deleted.exceptionOrNull()!!
    }

    /** A group with only Third Ear in it (to try sending without bothering anyone); others can be invited later. */
    suspend fun createGroup(name: String): String = io { m, acct ->
        // MarmotKit 0.17+ reports per-member outcomes; with no members a creator-only group is always made
        val created = m.createGroup(acct, name, emptyList(), null)
        refreshGroups()
        created.groupIdHex ?: error("no group created")
    }

    /**
     * Uploads (encrypted, to the group's media servers) and sends in one message. An upload is
     * encrypted for the group's current epoch: when the group moved on before the send
     * (MediaReferenceStaleEpoch), it is uploaded once more.
     */
    /**
     * [mention]: a member's pubkey hex every message to the group mentions with a `["p", hex]` tag
     * (GroupSettings.mention), for agents that act only when mentioned; null sends as before.
     */
    suspend fun sendVoice(groupId: String, file: File, fileName: String, mime: String, caption: String?, mention: String? = null) = io { m, acct ->
        // with a mention: upload only, then send the references with the tag (an upload that sends carries no tags)
        val request = MediaFileUploadRequestFfi(
            attachments = listOf(MediaFileUploadAttachmentRequestFfi(file.path, file.length().toULong(), fileName, mime, null, null)),
            caption = caption,
            send = mention == null,
            blossomServer = null,
        )
        suspend fun once(): SendSummaryFfi {
            val result = m.uploadMediaFiles(acct, groupId, request, MediaFileTransferControlFfi())
            if (mention == null) return result.sent ?: error("uploaded but not sent")
            return m.sendTaggedMedia(acct, groupId, result.attachments.map { it.reference }, caption, mentionTags(mention))
        }
        val sent = try {
            once()
        } catch (_: MarmotKitException.MediaReferenceStaleEpoch) {
            Log.i(TAG, "group moved on during the upload, uploading again")
            once()
        }
        logQueued(sent)
    }

    private fun mentionTags(pubkey: String) = listOf(listOf("p", pubkey))

    suspend fun sendText(groupId: String, text: String, mention: String? = null) = io { m, acct ->
        logQueued(if (mention == null) m.sendText(acct, groupId, text) else m.sendTaggedText(acct, groupId, text, mentionTags(mention)))
    }

    /** A group member as the mention picker lists them. */
    data class Member(val pubkey: String, val name: String?, val self: Boolean) {
        val label: String get() = name ?: short(pubkey)

        companion object {
            private const val SHORT_HEAD = 12
            private const val SHORT_TAIL = 6

            fun short(pubkey: String): String {
                val npub = marmot?.let { m -> runCatching { m.npub(pubkey) }.getOrNull() } ?: pubkey
                return npub.take(SHORT_HEAD) + "…" + npub.takeLast(SHORT_TAIL)
            }
        }
    }

    suspend fun members(groupId: String): List<Member> = io { m, acct ->
        val self = accountHex
        m.groupMembers(acct, groupId).map { r ->
            Member(r.memberIdHex, runCatching { m.displayName(r.memberIdHex) }.getOrNull()?.takeIf { it.isNotBlank() }, r.memberIdHex == self)
        }.sortedWith(compareBy({ it.self }, { it.label.lowercase() }))
    }

    /**
     * Takes back an own message for every member (a kind-5 deletion; their apps show it as deleted).
     * A send that never left this phone is only discarded here: announcing it would tell the group
     * about a message nobody received.
     */
    suspend fun deleteForEveryone(groupId: String, message: TimelineMessage) = io { m, acct ->
        if (message.failed) {
            val r = m.discardFailedSend(acct, groupId, message.id)
            if (r.outcome == DiscardFailedSendOutcomeFfi.STILL_SENDING) error("still sending")
        } else {
            logQueued(m.deleteMessage(acct, groupId, message.id))
        }
        Unit
    }

    /**
     * An accepted send that did not publish yet (MarmotKit 0.18 holds messages while the group's
     * own last commit is unconfirmed, and sends them once it resolves): not a failure.
     */
    private fun logQueued(sent: SendSummaryFfi) {
        if (sent.acceptDisposition != SendAcceptDispositionFfi.PUBLISHED) Log.i(TAG, "send accepted, ${sent.acceptDisposition}")
    }

    suspend fun download(groupId: String, ref: MediaAttachmentReferenceFfi): ByteArray = io { m, acct ->
        m.downloadMedia(acct, groupId, ref).plaintext
    }

    /** One chat message of a group's conversation, Third Ear's own included. */
    data class TimelineMessage(
        val id: String,
        val mine: Boolean,
        val sender: String,
        val text: String,
        val audio: List<MediaAttachmentReferenceFfi>,
        val at: Long,
        /** An own send that never reached a relay (MarmotKit: local_publish_failed). */
        val failed: Boolean = false,
    )

    /** The group's latest chat messages, oldest first (system events and deleted messages left out). */
    suspend fun timeline(groupId: String, limit: Int = 100): List<TimelineMessage> = io { m, acct ->
        val self = accountHex
        m.timelineMessages(acct, latest(groupId, limit.toUInt()))
            .messages
            .filter { it.kind == 9uL && !it.deleted && it.groupSystem == null }
            .map { r ->
                TimelineMessage(
                    id = r.messageIdHex,
                    mine = r.sender == self,
                    sender = r.sender,
                    text = readable(m, r.plaintext),
                    audio = r.media.filterIsInstance<MediaAttachmentOutcomeFfi.Accepted>().map { it.reference }.filter { it.mediaType.startsWith("audio/") },
                    at = r.timelineAt.toLong().let { if (it > MILLIS_THRESHOLD) it else it * 1000 },
                    failed = r.invalidationStatus == "local_publish_failed",
                )
            }
            .sortedBy { it.at }
    }

    /** The group's newest [limit] timeline rows, unfiltered (MarmotKit 0.15+ added search filters, all left off). */
    private fun latest(groupId: String, limit: UInt) = TimelineMessageQueryFfi(
        groupIdHex = groupId,
        search = null,
        sender = null,
        since = null,
        until = null,
        cursorGroupId = null,
        before = null,
        beforeMessageId = null,
        after = null,
        afterMessageId = null,
        limit = limit,
    )

    /** Mentions in a message's text as `@Name` (Third Ear itself by its own profile name). */
    private fun readable(m: Marmot, text: String): String =
        Mentions.readable(text) { hex -> runCatching { m.displayName(hex) }.getOrNull() }

    fun displayName(pubkey: String): String? = marmot?.let { m -> runCatching { m.displayName(pubkey) }.getOrNull() }

    suspend fun markRead(groupId: String, messageId: String) = io { m, acct ->
        runCatching { m.markTimelineMessageRead(acct, groupId, messageId) }
        Unit
    }

    private suspend fun <T> io(block: suspend (Marmot, String) -> T): T = withContext(Dispatchers.IO) {
        val m = marmot ?: error("White Noise is not running")
        val acct = account ?: error("no White Noise identity")
        block(m, acct)
    }

    private class KvSecretStore(private val kv: SecureKv) : SecretStore {
        private fun name(account: String) = "mk:$account"
        override fun hasSecretForLabel(label: String): Boolean = kv.get("mklabel:$label") != null
        override fun hasSecretForAccountId(accountIdHex: String): Boolean = kv.get(name(accountIdHex)) != null
        override fun writeSecret(label: String, accountIdHex: String, secretKeyHex: String) {
            kv.put(name(accountIdHex), secretKeyHex)
            kv.put("mklabel:$label", accountIdHex)
        }

        override fun loadSecret(label: String, accountIdHex: String): String =
            kv.get(name(accountIdHex)) ?: throw MarmotKitException.SecretNotFound("no secret for this account")

        override fun removeSecret(label: String, accountIdHex: String) {
            kv.put(name(accountIdHex), null)
            kv.put("mklabel:$label", null)
        }
    }
}
