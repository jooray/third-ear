package app.thirdear.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.extensions.describe
import app.thirdear.extensions.inputLabel
import app.thirdear.extensions.recordActions
import app.thirdear.services.RecorderService
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MessageNotifier
import app.thirdear.whitenoise.WhiteNoiseService

/** Car list templates show this many rows while driving. */
private const val MAX_STOP_ROWS = 5

/**
 * Android Auto: a grid of the user's actions (tap = record with it), a recording screen with
 * "Stop → action" rows and the microphone in use, and the recent White Noise messages. The car's
 * microphone is the Bluetooth hands-free one, so recording uses the engine's Bluetooth path when
 * "Use a Bluetooth microphone" is on. Optional: the app works fully without it.
 */
class ThirdEarCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            if (RecorderService.isRunning) RecordingScreen(carContext) else ActionsScreen(carContext)
    }
}

private fun micIcon(c: CarContext) = CarIcon.Builder(IconCompat.createWithResource(c, R.drawable.ic_microphone_widget_icon)).build()

/** A line's cord colour as a car icon, so the car grid matches the keys on the phone. */
private fun lineIcon(c: CarContext, cord: Int): CarIcon {
    val k = app.thirdear.ui.Cords.color(cord)
    val argb = android.graphics.Color.argb((k.alpha * 255).toInt(), (k.red * 255).toInt(), (k.green * 255).toInt(), (k.blue * 255).toInt())
    return CarIcon.Builder(IconCompat.createWithResource(c, R.drawable.ic_car_line))
        .setTint(androidx.car.app.model.CarColor.createCustom(argb, argb))
        .build()
}

class ActionsScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch { ActionStore.changes.collect { invalidate() } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val actions = carContext.recordActions()
        if (actions.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.te_car_no_actions)).setTitle(carContext.getString(R.string.te_car_title)).build()
        }
        val list = ItemList.Builder()
        // one touch: record now, pick the line at the stop
        list.addItem(
            GridItem.Builder()
                .setTitle(carContext.getString(R.string.te_car_record))
                .setText(carContext.getString(R.string.te_car_record_desc))
                .setImage(micIcon(carContext), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { start(null) }
                .build(),
        )
        for ((i, tile) in CarModel.grid(actions).take(CarModel.MAX_GRID - 1).withIndex()) {
            val action = actions.first { it.id == tile.actionId }
            list.addItem(
                GridItem.Builder()
                    .setTitle(tile.title)
                    .setText(app.thirdear.actions.LineCode.of(action))
                    .setImage(lineIcon(carContext, app.thirdear.ui.cordOf(action, i)), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { start(tile.actionId) }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.te_car_title))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(carContext.getString(R.string.te_car_messages) + unreadSuffix()).setOnClickListener {
                        screenManager.push(MessagesScreen(carContext))
                    }.build())
                    .build(),
            )
            .build()
    }

    private fun start(actionId: String?) {
        if (RecorderService.isRunning || RecorderService.start(carContext, actionId)) {
            screenManager.push(RecordingScreen(carContext))
        } else {
            CarToast.makeText(carContext, R.string.te_car_open_phone, CarToast.LENGTH_LONG).show()
        }
    }
}

private fun Screen.unreadSuffix(): String {
    val n = IncomingStore(carContext).load().count { !it.read && it.ignoredBy == null }
    return if (n > 0) " ($n)" else ""
}

class RecordingScreen(carContext: CarContext) : Screen(carContext) {
    private var shown: app.thirdear.audio.EngineStatus? = null
    private var shownAt = 0L
    private var job: Job? = null
    private var sawRecording = false
    private val openedAt = System.currentTimeMillis()
    private var refused = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                job = lifecycleScope.launch {
                    // Android refused the microphone: say so instead of "starting" forever
                    launch {
                        RecorderService.refusedAt.collect { at ->
                            if (at >= openedAt && !refused) {
                                refused = true
                                invalidate()
                            }
                        }
                    }
                    RecorderService.live.collect { l ->
                        if (l == null) {
                            if (sawRecording) {
                                CarToast.makeText(carContext, R.string.te_step_saving, CarToast.LENGTH_SHORT).show()
                                if (screenManager.stackSize > 1) screenManager.pop() else screenManager.push(ActionsScreen(carContext))
                            }
                            return@collect
                        }
                        sawRecording = true
                        val now = System.currentTimeMillis()
                        if (CarModel.needsRefresh(shown, l.status, shownAt, now)) {
                            shown = l.status
                            shownAt = now
                            invalidate()
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val l = RecorderService.live.value
        val list = ItemList.Builder()
        if (l == null) {
            val text = if (refused) R.string.te_car_open_phone else R.string.te_input_starting
            return MessageTemplate.Builder(carContext.getString(text)).setTitle(carContext.getString(R.string.te_car_title))
                .setHeaderAction(Action.BACK).build()
        }
        val status = l.status
        list.addItem(
            Row.Builder()
                .setTitle(carContext.inputLabel(status))
                .addText(CarModel.duration(status.durationMs) + if (status.paused) " · " + carContext.getString(R.string.paused) else "")
                .setImage(micIcon(carContext))
                .build(),
        )
        val all = carContext.recordActions()
        val rows = CarModel.stopRows(all, l.action.id, { carContext.describe(it) }) {
            carContext.getString(R.string.te_car_stop, it)
        }
        for (r in rows.take(MAX_STOP_ROWS)) {
            val i = all.indexOfFirst { it.id == r.actionId }
            val action = all.getOrNull(i)
            list.addItem(
                Row.Builder()
                    .setTitle(r.title)
                    .addText(r.subtitle)
                    .apply { if (action != null) setImage(lineIcon(carContext, app.thirdear.ui.cordOf(action, i))) }
                    .setOnClickListener { RecorderService.stop(carContext, r.actionId) }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.te_car_recording, l.action.name))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(carContext.getString(if (status.paused) R.string.resume_recording else R.string.pause_recording))
                        .setOnClickListener { RecorderService.togglePause(carContext) }.build())
                    .build(),
            )
            .build()
    }
}

class MessagesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val messages = IncomingStore(carContext).load().filter { it.ignoredBy == null }.take(6)
        val list = ItemList.Builder()
        if (messages.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.te_no_messages))
        for (m in messages) {
            list.addItem(
                Row.Builder()
                    // a sender named like the group (an agent's own group) is said once
                    .setTitle(listOfNotNull(m.groupName, (m.senderName ?: m.sender.take(8)).takeIf { it != m.groupName }).joinToString(" · "))
                    .addText(MessageNotifier.messageLine(carContext, m))
                    .setOnClickListener { WhiteNoiseService.playMessage(carContext, m.id) }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.te_car_messages))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
