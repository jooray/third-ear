package app.thirdear.whitenoise

import android.content.Context
import android.util.Log
import dev.ipf.marmotkit.AccountSummaryFfi
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
 * Third Ear's own White Noise (Marmot) identity, through MarmotKit 0.14.0 (from I Hear You): it is
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

    data class Group(val id: String, val name: String, val pending: Boolean, val member: Boolean)

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
                val acct = ensureAccount(m)
                account = acct.label
                accountHex = acct.accountIdHex
                npub.value = m.npub(acct.accountIdHex)
                refreshGroups()
                events = scope.launch { pump(m) }
                phase.value = Phase.READY
                Log.i(TAG, "ready as ${npub.value}")
                val label = acct.label
                scope.launch { ensureKeyPackage(context.applicationContext, m, label) }
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
                prefs.getString(KEY_PACKAGE_BUILD, null) != built -> {
                    Log.i(TAG, "MarmotKit changed, rotating the key package")
                    m.rotateKeyPackage(label)
                }
            }
            prefs.edit().putString(KEY_PACKAGE_BUILD, built).apply()
            m.runDueMaintenance(label)
        }.onFailure { Log.w(TAG, "key package check failed", it) }
    }

    private const val KEY_PACKAGE_BUILD = "key_package_marmotkit"

    /**
     * MDK migrates its store forward only. When an older MarmotKit finds a store a newer one wrote
     * (here: back from a 0.15 nightly to 0.14.0), it cannot open it: White Noise starts over with a
     * fresh identity, which is fine while it is in no group.
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

    private suspend fun ensureAccount(m: Marmot): AccountSummaryFfi {
        val existing = m.listAccounts().firstOrNull()
        return when {
            existing == null -> {
                Log.i(TAG, "creating identity")
                // publishes the relay lists (10002, 10050 inbox), the profile and the first key package
                val profile = UserProfileMetadataFfi("Third Ear", "Third Ear", "Voice recorder", null, null, null, null)
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
            while (scope.isActive) {
                when (val e = sub.next() ?: break) {
                    is MarmotEventFfi.MessageReceived -> onMessage(m, e.received.message)
                    is MarmotEventFfi.GroupJoined, is MarmotEventFfi.GroupStateUpdated,
                    is MarmotEventFfi.WelcomeDeliveryPending, is MarmotEventFfi.ProjectionUpdated -> refreshGroups()

                    is MarmotEventFfi.AccountError -> Log.w(TAG, "account error")
                    else -> {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "event stream ended: ${t.javaClass.simpleName}")
        } finally {
            runCatching { sub.close() }
        }
    }

    private suspend fun onMessage(m: Marmot, msg: dev.ipf.marmotkit.ReceivedMessageFfi) {
        val self = accountHex ?: return
        if (msg.sender == self || msg.kind != 9uL) return
        val acct = account ?: return
        // media references come resolved on the timeline record, not on the raw message
        val media = runCatching {
            m.timelineMessages(acct, TimelineMessageQueryFfi(msg.groupIdHex, null, null, null, null, null, 30u))
                .messages.firstOrNull { it.messageIdHex == msg.messageIdHex }
                ?.media?.filterIsInstance<MediaAttachmentOutcomeFfi.Accepted>()?.map { it.reference }
        }.getOrNull().orEmpty()
        val at = msg.recordedAt.toLong().let { if (it > MILLIS_THRESHOLD) it else it * 1000 }
        val name = msg.senderDisplayName ?: runCatching { m.displayName(msg.sender) }.getOrNull()
        onIncoming?.invoke(Incoming(msg.groupIdHex, msg.messageIdHex, msg.sender, name, msg.plaintext, media, at))
    }

    fun refreshGroups() {
        val m = marmot ?: return
        val acct = account ?: return
        groups.value = runCatching {
            m.chatList(acct, false).map {
                Group(it.groupIdHex, it.groupName.ifBlank { it.title }, it.pendingConfirmation, it.selfMembership == SelfMembershipFfi.MEMBER)
            }
        }.getOrDefault(groups.value)
    }

    suspend fun accept(groupId: String) = io { m, acct ->
        m.acceptGroupInvite(acct, groupId)
        refreshGroups()
    }

    suspend fun decline(groupId: String) = io { m, acct ->
        m.declineGroupInvite(acct, groupId)
        refreshGroups()
    }

    /** An MLS leave, sent to the group: the others see Third Ear go; it can come back only by a new invite. */
    suspend fun leave(groupId: String) = io { m, acct ->
        m.leaveGroup(acct, groupId)
        refreshGroups()
    }

    /** A group with only Third Ear in it (to try sending without bothering anyone); others can be invited later. */
    suspend fun createGroup(name: String): String = io { m, acct ->
        m.createGroup(acct, name, emptyList(), null).also { refreshGroups() }
    }

    /** Uploads (encrypted, to the group's media servers) and sends in one message. */
    suspend fun sendVoice(groupId: String, file: File, fileName: String, mime: String, caption: String?) = io { m, acct ->
        val request = MediaFileUploadRequestFfi(
            attachments = listOf(MediaFileUploadAttachmentRequestFfi(file.path, file.length().toULong(), fileName, mime, null, null)),
            caption = caption,
            send = true,
            blossomServer = null,
        )
        val result = m.uploadMediaFiles(acct, groupId, request, MediaFileTransferControlFfi())
        if (result.sent == null) error("uploaded but not sent")
    }

    suspend fun sendText(groupId: String, text: String) = io { m, acct ->
        m.sendText(acct, groupId, text)
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
    )

    /** The group's latest chat messages, oldest first (system events and deleted messages left out). */
    suspend fun timeline(groupId: String, limit: Int = 100): List<TimelineMessage> = io { m, acct ->
        val self = accountHex
        m.timelineMessages(acct, TimelineMessageQueryFfi(groupId, null, null, null, null, null, limit.toUInt()))
            .messages
            .filter { it.kind == 9uL && !it.deleted && it.groupSystem == null }
            .map { r ->
                TimelineMessage(
                    id = r.messageIdHex,
                    mine = r.sender == self,
                    sender = r.sender,
                    text = r.plaintext,
                    audio = r.media.filterIsInstance<MediaAttachmentOutcomeFfi.Accepted>().map { it.reference }.filter { it.mediaType.startsWith("audio/") },
                    at = r.timelineAt.toLong().let { if (it > MILLIS_THRESHOLD) it else it * 1000 },
                )
            }
            .sortedBy { it.at }
    }

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
