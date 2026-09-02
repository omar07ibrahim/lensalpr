package com.lensalpr.app.telegram

import android.util.Log
import com.lensalpr.app.data.AdminRow
import com.lensalpr.app.data.TrackingStore
import com.lensalpr.app.follow.AlertReason
import com.lensalpr.app.follow.FollowEvidence
import com.lensalpr.app.follow.ThreatLevel
import com.lensalpr.app.settings.RuntimeSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Everything the bot needs from the running scanner. */
interface BotHost {
    fun statusText(): String
    fun buildReport(sinceMs: Long): File?

    /** Everything known about one plate: map with its route, every encounter photo. */
    fun buildVehicleReport(plate: String): File?
    fun listVehicles(minLevel: Int): String
    fun vehicleCard(plate: String): Pair<String, List<File>>?
    fun setBlacklist(plate: String, blacklisted: Boolean): String
    fun blacklistText(): String
    fun currentTripStartMs(): Long

    /** A frame straight from the camera, as JPEG. */
    fun snapshot(): File?

    /** Pauses or resumes recognition; returns what to tell the operator. */
    fun setPaused(paused: Boolean): String
    fun isPaused(): Boolean

    /**
     * Stops or starts the whole session: unlike a pause this releases the camera entirely, so a
     * phone left on the glass stops heating and draining while nothing is being watched.
     */
    fun setSessionRunning(running: Boolean): String
    fun isSessionRunning(): Boolean

    /** Moves the rotation to the next lens. */
    fun nextLens(): String

    /** Starts or stops a clip the operator asked for by hand. */
    fun toggleRecording(): String

    /** Free-text search over everything seen so far. */
    fun findPlates(query: String): String

    /** Who is behind us right now and for how long — the question asked from the driver's seat. */
    fun companionsText(): String

    /** Pre-flight: everything that silently ruins a trip, checked in one go. */
    fun preflightText(): String

    /** Current tunable thresholds, rendered for the operator. */
    fun settingsText(): String

    /** Applies a threshold change coming from Telegram; returns what to answer. */
    fun applySetting(key: String, delta: Int): String

    /** How much disk the collected evidence occupies, rendered for the operator. */
    fun storageText(): String

    /** Erases the database and every file behind it; returns what was removed. */
    fun wipeAll(): String

    /** Marks a car as one of ours: no alerts, no video, no threat level. */
    fun setIgnored(plate: String, ignored: Boolean): String

    /** Marks a car as police: shouts like the blacklist, named as police. */
    fun setPolice(plate: String, police: Boolean): String

    /** The cars the operator marked as police. */
    fun policeText(): String

    /** The cars the operator dismissed. */
    fun ignoredText(): String

    /** Corrects a misread plate, or undoes a merge the engine should not have made. */
    fun renamePlate(from: String, to: String): String
}

data class BotSettings(
    val token: String,
    val ownerId: Long,
    val enabled: Boolean,
    val alertMinLevel: Int,
    val alertAfterEncounters: Int,
    /** Cropping strategy in force, so the panel can show which button is the current one. */
    val narrowCrops: Int,
)

/**
 * Telegram control room: alerts out, commands in, no server in between.
 *
 * The phone long-polls the Bot API itself. The owner is compiled in and may promote other admins by
 * Telegram id; anyone else gets a refusal while the owner receives a one-tap approval button.
 */
class TelegramBot(
    private val store: TrackingStore,
    private val host: BotHost,
    private val settings: () -> BotSettings,
) {

    /**
     * Three lanes on purpose.
     *
     * A tail alert and a twenty-megabyte clip used to queue behind each other on one thread, so the
     * warning that matters could arrive minutes after the video that caused it — and while either
     * was uploading, the poller could not fetch /stop. Text is small and urgent, media is large and
     * patient, and incoming commands must never wait for either.
     */
    private val sender = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tg-sender") }
    private val media = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tg-media") }
    private val commands = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tg-commands") }
    /**
     * Last level and moment each plate was alerted about, the duplicate guard for [alert].
     *
     * Concurrent because it is written from three threads that never coordinate: alerts arrive on
     * the scanner's io thread, `/rename` and `/wipe` clear it from the command poller. A plain
     * HashMap resized under that would not merely lose an entry — it can spin on a corrupted
     * bucket chain and take the alerting path down with it.
     */
    private val alertState = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /** When each car's full history was last sent, so it is not re-sent on every reappearance. */
    private val dossierSentMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Alarms raised while there was no way to send them.
     *
     * A tail is most likely to be confirmed exactly where the signal is worst — a tunnel, a
     * underpass, a stretch of road out of town. Losing that one message meant losing it for good,
     * because the engine deliberately rations repeats and the next chance could be many minutes
     * away. Guarded by its own monitor: filled from the text lane, drained from the retry lane.
     */
    private val pendingAlerts = ArrayList<PendingAlert>()

    private val retries = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tg-retry").apply { isDaemon = true }
    }

    /** Keeps exactly one retry pass in flight, however many alarms pile up. */
    private val retryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var client: TelegramClient? = null

    @Volatile
    private var poller: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var muted = false

    /** Volatile because a restarted poller is a different thread than the one that advanced it. */
    @Volatile
    private var offset = 0L

    /** When this bot was started; the baseline for "unreachable since". */
    @Volatile
    private var startedAtMs = 0L

    val isRunning: Boolean get() = running

    /**
     * Commands are answered whenever a valid token exists — the Telegram switch in the setup screen
     * only mutes outgoing alerts. A bot that silently ignores its owner is the worst failure mode
     * there is: the phone is on the rear glass and the owner is driving.
     */
    fun start() {
        if (running) return
        val config = settings()
        if (config.token.isBlank()) return
        val created = TelegramClient(config.token)
        if (!created.isConfigured) {
            Log.w(TAG, "bot token has an invalid format")
            return
        }
        client = created
        running = true
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        startPoller(created)
        sender.execute {
            val name = created.getMe()
            Log.i(TAG, "bot connected as @$name, alerts=${config.enabled}")
            val header = if (config.enabled) "🟢 <b>LensALPR</b> на связи\n" else "🔕 <b>LensALPR</b> на связи, алерты выключены\n"
            broadcastTo(created, config, header + host.statusText(), panel())
        }
    }

    private fun startPoller(active: TelegramClient) {
        poller = Thread(
            {
                try {
                    loop(active)
                } finally {
                    // The loop only ever exits by dying or by stop(). If it died, nothing else in
                    // the app is guaranteed to notice: the watchdog that calls ensureAlive lives
                    // in the activity, and a session that failed to start never had one. A bot
                    // that stops answering its owner from the road is the worst failure there is,
                    // so it puts itself back together.
                    if (running) {
                        Log.w(TAG, "poller exited unexpectedly; restarting")
                        runCatching {
                            retries.schedule(
                                { if (running) ensureAlive() },
                                RETRY_DELAY_MS,
                                TimeUnit.MILLISECONDS,
                            )
                        }
                    }
                }
            },
            "tg-poller",
        ).apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, error -> Log.e(TAG, "poller crashed", error) }
            start()
        }
    }

    /**
     * How long Telegram has been unreachable, or zero while it is answering.
     *
     * The bot cannot report its own silence — that is the whole problem — so the scanner asks and
     * says it out loud instead.
     */
    fun unreachableForMs(nowMs: Long): Long {
        val active = client ?: return 0L
        if (!running || active.isReachable) return 0L
        val since = maxOf(active.lastReachedAtMs, startedAtMs)
        return if (since == 0L) 0L else nowMs - since
    }

    /** Called from the session watchdog: a dead poller means a bot that never answers again. */
    fun ensureAlive() {
        if (!running) return
        val thread = poller
        if (thread != null && thread.isAlive) return
        val active = client ?: return
        Log.w(TAG, "poller was dead, restarting")
        startPoller(active)
    }

    fun stop() {
        running = false
        poller?.interrupt()
        poller = null
        val config = runCatching(settings).getOrNull()
        val active = client
        if (config != null && active != null) {
            runCatching {
                sender.execute { broadcastTo(active, config, "⚪️ LensALPR остановлен", null) }
            }
        }
        sender.shutdown()
        media.shutdown()
        commands.shutdown()
        retries.shutdownNow()
        runCatching { sender.awaitTermination(2, TimeUnit.SECONDS) }
        client = null
    }

    // ------------------------------------------------------------------ alerts

    /** Pushes a threat to every admin, with the best photo of the latest encounter. */
    fun alert(evidence: FollowEvidence, photo: File?, reason: AlertReason = AlertReason.RAISED) {
        val active = client ?: return
        val config = settings()
        if (!config.enabled || muted) return

        // Dismissed by the operator: never again, whatever it does.
        if (evidence.ignored) return
        if (reason == AlertReason.NONE) return

        // Both hand-made lists bypass the threshold: the operator put the car there on purpose,
        // and a setting meant to filter the engine's guesses must not silence their own decision.
        val qualifies = evidence.blacklisted || evidence.police ||
            evidence.level.rank >= config.alertMinLevel ||
            evidence.encounters >= config.alertAfterEncounters
        if (!qualifies) return

        val previous = alertState[evidence.plate]
        val now = System.currentTimeMillis()
        // "Still behind us" is a voice event, not a chat event. The speech channel says it every
        // few seconds because that is what a driver needs; repeating it here at the same rate
        // would pass the Bot API's per-chat rate limit within a minute, and the 429 that follows
        // stalls the retry queue that real alarms depend on.
        if (reason == AlertReason.PRESENT) {
            val last = previous?.second ?: 0L
            if (last != 0L && now - last < PRESENT_TELEGRAM_MS) return
        }
        // Second line of defence against duplicates only. The follow engine has already decided
        // that this car is worth a word — it rations repeats per stretch of company and per car —
        // so this window has to be shorter than the engine's, or it would silently undo that
        // decision and put the bot back to announcing each vehicle exactly once.
        if (previous != null &&
            previous.first >= evidence.level.rank &&
            now - previous.second < ALERT_COOLDOWN_MS
        ) {
            return
        }
        alertState[evidence.plate] = evidence.level.rank to now

        val text = alertText(evidence, reason)
        // Every alarm ends in a decision, so the decision travels with it: either this car matters
        // and goes on the list, or it is a neighbour and must never wake the driver again.
        // storeKey throughout: these buttons and lookups all end up in the database, and the
        // engine's own spelling of a plate is not guaranteed to be the one with rows behind it.
        val markup = TelegramClient.inlineKeyboard(
            listOf(
                listOf(
                    "⛔️ В чёрный список" to "bl:${evidence.storeKey}",
                    "🙈 Игнорировать" to "ign:${evidence.storeKey}",
                ),
                listOf(
                    (if (evidence.police) "🚔 Не полиция" else "🚔 Полиция") to
                        (if (evidence.police) "unpol:" else "pol:") + evidence.storeKey,
                ),
                listOf("📷 Кадр" to "photo", "🗺 Отчёт" to "report:1"),
            ),
        )
        // A blacklisted car earns the full package: the alert, every photo we have of it, and a map
        // of where it appeared - there is nothing to decide later, the evidence arrives at once.
        // A car coming back does not: the dossier has already been sent once, and re-sending it
        // every time would put a megabyte of history in front of a one-line warning.
        // The dossier — every photo of the car and a map of where it appeared — is worth sending
        // once. A car that keeps dropping out of frame and returning would otherwise pull a
        // megabyte of its own history into the chat on every reappearance, ahead of the one-line
        // warning that actually matters and straight into the API's rate limit.
        val worthDossier = reason != AlertReason.RETURNED &&
            reason != AlertReason.PRESENT &&
            (evidence.blacklisted || evidence.level == ThreatLevel.TAIL)
        // Only a candidacy here — the cooldown is claimed after the alarm is known to have landed.
        // Stamping it now would spend the one chance a tail ever gets at a dossier on an attempt
        // that failed in a tunnel: for a car that is not on the list, the only reason that carries
        // a dossier is the single RAISED event, and nothing re-raises a vehicle already at TAIL.
        val dossierDue = worthDossier && (dossierSentMs[evidence.plate]
            ?.let { now - it >= DOSSIER_COOLDOWN_MS }
            ?: true)
        sender.execute {
            val chats = recipients(config)
            val delivered = deliverAlert(active, chats, text, markup, photo)
            if (!delivered) {
                // A tunnel, a dead cell, a 429. The alarm was raised and nobody heard it — and
                // because the engine rations repeats, the next chance may be many minutes away or
                // may never come. Put it in the queue instead of losing it, and roll the cooldown
                // back so a genuine second attempt is not mistaken for a duplicate.
                if (previous != null) alertState[evidence.plate] = previous else alertState.remove(evidence.plate)
                queueAlert(PendingAlert(evidence.plate, text, markup, photo?.path, evidence.level.rank, now))
                return@execute
            }
            if (!dossierDue) return@execute
            // Claim the window atomically: two alarms about the same car may reach this point
            // together, and both would otherwise send the whole history.
            val claimed = dossierSentMs.compute(evidence.plate) { _, previousSend ->
                if (previousSend == null || now - previousSend >= DOSSIER_COOLDOWN_MS) now else previousSend
            } == now
            if (!claimed) return@execute
            media.execute {
                val card = host.vehicleCard(evidence.storeKey)
                // takeLast, not take: the card lists encounters oldest first, and a car met all
                // week would otherwise arrive as six pictures from Monday and nothing from today.
                val shots = card?.second?.takeLast(MAX_PHOTOS).orEmpty()
                var failed = 0
                shots.forEach { shot ->
                    val ok = chats.map { chatId ->
                        active.sendPhoto(chatId, shot, photoCaption(shot))
                    }.any { it }
                    if (!ok) failed += 1
                }
                // The count and the pictures must never disagree in silence. This is what "5
                // encounters, 3 photos" actually was: the files were all on the phone, Telegram
                // rate-limited the burst, and nothing said so.
                if (failed > 0) {
                    chats.forEach { chatId ->
                        active.sendMessage(
                            chatId,
                            "⚠️ $failed из ${shots.size} фото не ушли (Telegram отказал). " +
                                "Они на телефоне — запроси карточку ещё раз или смотри отчёт.",
                            null,
                        )
                    }
                }
                val report = host.buildVehicleReport(evidence.storeKey)
                if (report != null) {
                    chats.forEach { chatId ->
                        active.sendDocument(
                            chatId,
                            report,
                            "🗺 <code>${evidence.displayPlate}</code> — карта встреч и маршрута",
                        )
                    }
                }
            }
        }
    }

    /** One alarm that has not reached anybody yet. */
    private class PendingAlert(
        val plate: String,
        val text: String,
        val markup: String?,
        /** Kept as a path: the file may be pruned while the alarm waits. */
        val photoPath: String?,
        val levelRank: Int,
        val raisedAtMs: Long,
        var attempts: Int = 0,
    )

    /**
     * Sends one alarm to everybody and says whether it reached anybody at all.
     *
     * The photo is a bonus; the warning is the point. A rejected upload — too large, a 429, a
     * dropped connection — used to take the whole alarm down with it.
     */
    private fun deliverAlert(
        active: TelegramClient,
        chats: List<Long>,
        text: String,
        markup: String?,
        photo: File?,
    ): Boolean {
        // Nobody to tell is not a failure to retry; it is a bot with no admins.
        if (chats.isEmpty()) return true
        return chats.map { chatId ->
            val sent = if (photo != null && photo.exists()) {
                active.sendPhoto(chatId, photo, text, markup)
            } else {
                false
            }
            sent || active.sendMessage(chatId, text, markup)
        }.any { it }
    }

    /**
     * Parks an undelivered alarm and starts the retry loop if it is not already running.
     *
     * Bounded, and the eviction is not by age alone: under pressure a WATCH from ten minutes ago
     * is worth dropping, a TAIL never is.
     */
    private fun queueAlert(alert: PendingAlert) {
        synchronized(pendingAlerts) {
            if (pendingAlerts.size >= MAX_PENDING_ALERTS) {
                val victim = pendingAlerts
                    .filter { it.levelRank < ThreatLevel.TAIL.rank }
                    .minByOrNull { it.raisedAtMs }
                    ?: pendingAlerts.minByOrNull { it.raisedAtMs }
                pendingAlerts.remove(victim)
                Log.w(TAG, "alert queue full; dropped ${victim?.plate}")
            }
            pendingAlerts += alert
        }
        Log.w(TAG, "alert for ${alert.plate} queued for retry")
        scheduleAlertRetry(RETRY_DELAY_MS)
    }

    private fun scheduleAlertRetry(delayMs: Long) {
        if (!retryScheduled.compareAndSet(false, true)) return
        retries.schedule({ runCatching { flushPendingAlerts() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Tries every parked alarm once.
     *
     * Runs on its own lane: the text lane may be busy with the alarm that is arriving right now,
     * and a retry must never be what delays it.
     */
    private fun flushPendingAlerts() {
        retryScheduled.set(false)
        val active = client ?: return
        val config = settings()
        val batch = synchronized(pendingAlerts) { pendingAlerts.toList() }
        if (batch.isEmpty()) return
        val chats = recipients(config)
        var anyLeft = false
        // Local: this method only ever runs on the retry lane, one pass at a time.
        val lost = ArrayList<String>()
        batch.forEach { alert ->
            alert.attempts += 1
            val photo = alert.photoPath?.let(::File)?.takeIf { it.exists() }
            val prefix = "🔁 <i>с задержкой, связи не было</i>\n"
            val ok = config.enabled &&
                deliverAlert(active, chats, prefix + alert.text, alert.markup, photo)
            if (ok) {
                synchronized(pendingAlerts) { pendingAlerts.remove(alert) }
                Log.i(TAG, "queued alert for ${alert.plate} delivered on attempt ${alert.attempts}")
            } else if (alert.attempts >= MAX_ALERT_ATTEMPTS) {
                synchronized(pendingAlerts) { pendingAlerts.remove(alert) }
                Log.e(TAG, "giving up on the alert for ${alert.plate}")
                // Never silently: the driver has to learn that a warning existed and never
                // arrived, even if it is now history.
                lost += alert.plate
            } else {
                anyLeft = true
            }
        }
        if (lost.isNotEmpty()) {
            sender.execute {
                active.let { bot ->
                    recipients(config).forEach { chatId ->
                        bot.sendMessage(
                            chatId,
                            "⚠️ Тревог не доставлено: ${lost.joinToString(", ")}. " +
                                "Связи не было слишком долго — смотри отчёт.",
                            panel(),
                        )
                    }
                }
            }
        }
        if (anyLeft) {
            // Backoff by the most-tried entry, so one hopeless alarm cannot make the rest slow.
            val worst = synchronized(pendingAlerts) { pendingAlerts.minOfOrNull { it.attempts } ?: 0 }
            val delay = RETRY_BACKOFF_MS.getOrElse(worst) { RETRY_BACKOFF_MS.last() }
            scheduleAlertRetry(delay)
        }
    }

    /**
     * Labels a stored photo by when it was taken.
     *
     * Numbering the files 1..N was actively misleading: photos exist only for some encounters, so
     * "встреча 3" would sit under a picture of the fifth meeting while the card above it said the
     * car had been met five times. A timestamp cannot contradict the counter.
     */
    private fun photoCaption(file: File): String =
        "📷 " + PHOTO_TIME.format(Date(file.lastModified()))

    /**
     * Sends the clip recorded while a vehicle was tailing us and reports whether it arrived.
     *
     * Telegram refuses anything above 50 MB from a bot. Silently swallowing that would mean the one
     * piece of evidence worth having disappears without a word, so an oversized or rejected clip is
     * announced with its name and size and stays on the phone.
     */
    fun sendClip(file: File, plate: String, durationMs: Long, onDelivered: (Boolean) -> Unit = {}) {
        val active = client ?: return onDelivered(false)
        val config = settings()
        if (!config.enabled || muted) return onDelivered(false)
        // A vanished file reads as zero bytes, sails past the size check, and produces "Telegram
        // не принял (0.0 МБ)" — a refusal that never happened, about a clip that is not there.
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "clip ${file.name} is gone before upload")
            return onDelivered(false)
        }
        val megabytes = file.length() / (1024.0 * 1024.0)
        val seconds = durationMs / 1000
        val caption = buildString {
            append("🎥 <code>$plate</code> — запись преследования")
            if (seconds > 0) append(", $seconds с")
            append(String.format(Locale.US, ", %.1f МБ", megabytes))
        }
        runCatching { media.execute {
            val chats = recipients(config)
            val delivered = if (megabytes > MAX_UPLOAD_MB) {
                false
            } else {
                chats.map { chatId -> active.sendVideo(chatId, file, caption) }.any { it }
            }
            if (!delivered) {
                val reason = if (megabytes > MAX_UPLOAD_MB) "больше лимита Telegram" else "Telegram не принял"
                chats.forEach { chatId ->
                    active.sendMessage(
                        chatId,
                        "⚠️ Видео <code>$plate</code> не отправилось ($reason, " +
                            String.format(Locale.US, "%.1f МБ", megabytes) +
                            "). Файл на телефоне: <code>${file.name}</code>",
                        panel(),
                    )
                }
            }
            onDelivered(delivered)
        } }.onFailure { onDelivered(false) }
    }

    /** Sends a built report to every admin on the media lane. */
    fun sendReport(file: File, caption: String) {
        val active = client ?: return
        val config = settings()
        if (!config.enabled || muted || !file.exists()) return
        runCatching {
            media.execute {
                recipients(config).forEach { chatId -> active.sendDocument(chatId, file, caption) }
            }
        }
    }

    /**
     * @param urgent bypasses the mute. Reserved for things the operator would want even after
     *   asking for quiet — a crashed session, a blind camera — never for routine chatter.
     */
    fun broadcast(text: String, urgent: Boolean = false) {
        val active = client ?: return
        val config = settings()
        if (!config.enabled) return
        if (muted && !urgent) return
        runCatching { sender.execute { broadcastTo(active, config, text, null) } }
    }

    private fun broadcastTo(active: TelegramClient, config: BotSettings, text: String, markup: String?) {
        recipients(config).forEach { chatId -> active.sendMessage(chatId, text, markup) }
    }

    private fun recipients(config: BotSettings): List<Long> {
        val admins = runCatching { store.admins().map(AdminRow::chatId) }.getOrDefault(emptyList())
        return (admins + config.ownerId).filter { it != 0L }.distinct()
    }

    // ------------------------------------------------------------------- loop

    private fun loop(active: TelegramClient) {
        Log.i(TAG, "poller started")
        // Telegram replays unconfirmed updates for a day; without this the bot would re-execute
        // yesterday's commands on every restart. Inside its own guard: it is a network call, and
        // failing it must not be what kills the poller before it has fetched a single update.
        if (offset == 0L) {
            runCatching { active.getUpdates(-1L).lastOrNull()?.let { offset = it.updateId + 1 } }
                .onFailure { error -> Log.w(TAG, "could not skip the backlog", error) }
        }

        try {
            while (running) {
                val startedAt = System.currentTimeMillis()
                val updates = active.getUpdates(offset)
                if (updates.isEmpty()) {
                    if (!running) break
                    if (System.currentTimeMillis() - startedAt < 1_000L) {
                        runCatching { Thread.sleep(RETRY_DELAY_MS) }
                    }
                    continue
                }
                updates.forEach { update ->
                    offset = maxOf(offset, update.updateId + 1)
                    // Handled off the polling thread: building a report or uploading a photo takes
                    // seconds, and during those seconds the operator's /stop must still be fetched.
                    val dispatched = runCatching {
                        commands.execute {
                            runCatching { handle(active, update) }
                                .onFailure { error -> Log.w(TAG, "command failed", error) }
                        }
                    }.isSuccess
                    if (!dispatched) {
                        runCatching { handle(active, update) }
                            .onFailure { error -> Log.w(TAG, "command failed", error) }
                    }
                }
            }
        } catch (error: Throwable) {
            // The watchdog restarts us; without this catch one bad update kills the bot for good.
            Log.e(TAG, "poller crashed", error)
        } finally {
            Log.i(TAG, "poller stopped (running=$running)")
        }
    }

    private fun handle(active: TelegramClient, update: BotUpdate) {
        val config = settings()
        val isOwner = update.senderId == config.ownerId && config.ownerId != 0L
        val isAdmin = isOwner || store.admins().any { it.chatId == update.senderId }

        if (!isAdmin) {
            update.callbackId?.let { active.answerCallback(it, "Нет доступа") }
            active.sendMessage(update.chatId, "⛔️ Нет доступа. Твой id: <code>${update.senderId}</code>")
            if (config.ownerId != 0L) {
                active.sendMessage(
                    config.ownerId,
                    "Запрос доступа от ${escape(update.senderName)}, id <code>${update.senderId}</code>",
                    TelegramClient.inlineRow(listOf("✅ Добавить админом" to "approve:${update.senderId}")),
                )
            }
            return
        }

        update.callbackId?.let { callbackId ->
            val data = update.callbackData.orEmpty()
            active.answerCallback(callbackId, null)
            handleAction(active, update.chatId, data, isOwner)
            return
        }

        val text = update.text?.trim().orEmpty()
        if (text.isEmpty()) return
        val parts = text.split(Regex("\\s+"))
        val command = parts.first().lowercase().substringBefore('@')
        val argument = parts.drop(1).joinToString(" ").trim()

        when (command) {
            "/start", "/menu", "/help" -> active.sendMessage(update.chatId, HELP, panel())
            "/howto", "/тест", "/test" -> active.sendMessage(update.chatId, HOWTO, panel())
            "/status" -> handleAction(active, update.chatId, "status", isOwner)
            "/photo", "/кадр" -> handleAction(active, update.chatId, "photo", isOwner)
            "/report" -> {
                val hours = argument.toIntOrNull()
                handleAction(active, update.chatId, "report:${hours ?: 0}", isOwner)
            }

            "/who", "/кто" -> handleAction(active, update.chatId, "who", isOwner)
            "/list" -> active.sendMessage(update.chatId, host.listVehicles(ThreatLevel.WATCH.rank), panel())
            "/all" -> active.sendMessage(update.chatId, host.listVehicles(ThreatLevel.IGNORE.rank), panel())
            "/find" -> active.sendMessage(update.chatId, host.findPlates(argument), panel())
            "/plate" -> sendVehicleCard(active, update.chatId, argument)
            "/bl" -> active.sendMessage(update.chatId, host.setBlacklist(argument, true), panel())
            "/unbl" -> active.sendMessage(update.chatId, host.setBlacklist(argument, false), panel())
            "/blacklist" -> active.sendMessage(update.chatId, host.blacklistText(), panel())
            "/ignore", "/ign" -> active.sendMessage(update.chatId, host.setIgnored(argument, true), panel())
            "/unignore", "/unign" -> active.sendMessage(update.chatId, host.setIgnored(argument, false), panel())
            "/ignored" -> active.sendMessage(update.chatId, host.ignoredText(), panel())
            "/police", "/pol" -> active.sendMessage(update.chatId, host.setPolice(argument, true), panel())
            "/unpolice", "/unpol" ->
                active.sendMessage(update.chatId, host.setPolice(argument, false), panel())
            "/policelist" -> active.sendMessage(update.chatId, host.policeText(), panel())
            "/rename" -> {
                val from = parts.getOrNull(1).orEmpty()
                val to = parts.getOrNull(2).orEmpty()
                if (from.isBlank() || to.isBlank()) {
                    active.sendMessage(
                        update.chatId,
                        "Формат: <code>/rename СТАРЫЙ НОВЫЙ</code>",
                        panel(),
                    )
                } else {
                    // The cooldown is keyed by plate; leaving the old key behind would mute the
                    // corrected spelling for three minutes and keep a ghost entry forever.
                    alertState.remove(from.uppercase())
                    alertState.remove(to.uppercase())
                    active.sendMessage(update.chatId, host.renamePlate(from, to), panel())
                }
            }
            "/rec" -> active.sendMessage(update.chatId, host.toggleRecording(), panel())
            "/pause" -> handleAction(active, update.chatId, "pause", isOwner)
            "/stop" -> active.sendMessage(update.chatId, host.setSessionRunning(false), panel())
            "/go", "/run" -> active.sendMessage(update.chatId, host.setSessionRunning(true), panel())
            "/lens" -> handleAction(active, update.chatId, "lens", isOwner)
            "/check" -> active.sendMessage(update.chatId, host.preflightText(), panel())
            "/settings" -> active.sendMessage(update.chatId, host.settingsText(), settingsPanel())
            "/set" -> {
                val key = parts.getOrNull(1).orEmpty()
                val value = parts.getOrNull(2)?.toIntOrNull()
                if (value == null) {
                    active.sendMessage(
                        update.chatId,
                        "Формат: <code>/set tail 120</code>, <code>/set absent 60</code>, " +
                            "<code>/set level 2</code>, <code>/set meetings 2</code>, " +
                            "<code>/set narrow 0|1|2</code> — вырез по машине / по номеру / сравнить",
                        settingsPanel(),
                    )
                } else {
                    active.sendMessage(update.chatId, host.applySetting("=$key", value), settingsPanel())
                }
            }

            "/storage", "/disk" -> handleAction(active, update.chatId, "storage", isOwner)
            "/wipe", "/clean" -> {
                if (argument.equals("yes", ignoreCase = true) || argument == "да") {
                    handleAction(active, update.chatId, "wipe:yes", isOwner)
                } else {
                    handleAction(active, update.chatId, "wipe", isOwner)
                }
            }

            "/admins" -> active.sendMessage(update.chatId, adminsText(config), adminPanel())
            "/addadmin" -> addAdmin(active, update.chatId, argument.toLongOrNull(), isOwner)
            "/deladmin" -> removeAdmin(active, update.chatId, argument.toLongOrNull(), isOwner)
            "/mute" -> handleAction(active, update.chatId, "mute:on", isOwner)
            "/unmute" -> handleAction(active, update.chatId, "mute:off", isOwner)
            else -> active.sendMessage(update.chatId, HELP, panel())
        }
    }

    /** One place for both the buttons and the text commands, so they can never drift apart. */
    private fun handleAction(active: TelegramClient, chatId: Long, action: String, isOwner: Boolean) {
        when {
            // Muting is easy to forget and looks exactly like a quiet road. Say it here, because
            // this is the screen somebody opens to check that the scanner is still watching.
            action == "status" -> active.sendMessage(
                chatId,
                if (muted) "🔕 <b>Тревоги выключены</b> (/unmute)\n${host.statusText()}" else host.statusText(),
                panel(),
            )

            action == "check" -> active.sendMessage(chatId, host.preflightText(), panel())

            action == "photo" -> {
                val photo = host.snapshot()
                if (photo == null) {
                    active.sendMessage(chatId, "Камера не отдала кадр", panel())
                } else {
                    active.sendPhoto(chatId, photo, "📷 Кадр с камеры", panel())
                }
            }

            action.startsWith("report:") -> {
                val hours = action.removePrefix("report:").toIntOrNull() ?: 0
                val since = if (hours > 0) {
                    System.currentTimeMillis() - hours * 3_600_000L
                } else {
                    host.currentTripStartMs()
                }
                active.sendMessage(chatId, "🗺 Собираю отчёт…")
                val file = host.buildReport(since)
                if (file == null) {
                    active.sendMessage(chatId, "Нет данных за период", panel())
                } else {
                    active.sendDocument(
                        chatId,
                        file,
                        "Отчёт LensALPR — открой в браузере: карта, маршрут и фото каждой встречи",
                    )
                }
            }

            action == "list" -> active.sendMessage(chatId, host.listVehicles(ThreatLevel.WATCH.rank), panel())
            action == "bl" -> active.sendMessage(chatId, host.blacklistText(), panel())

            action.startsWith("ign:") -> {
                val plate = action.removePrefix("ign:")
                active.sendMessage(chatId, host.setIgnored(plate, true), panel())
            }

            action.startsWith("unign:") -> {
                val plate = action.removePrefix("unign:")
                active.sendMessage(chatId, host.setIgnored(plate, false), panel())
            }

            action.startsWith("pol:") -> {
                val plate = action.removePrefix("pol:")
                active.sendMessage(chatId, host.setPolice(plate, true), panel())
            }

            action.startsWith("unpol:") -> {
                val plate = action.removePrefix("unpol:")
                active.sendMessage(chatId, host.setPolice(plate, false), panel())
            }

            action == "pollist" -> active.sendMessage(chatId, host.policeText(), panel())

            action.startsWith("bl:") -> {
                val plate = action.removePrefix("bl:")
                active.sendMessage(chatId, host.setBlacklist(plate, true), panel())
                host.buildVehicleReport(plate)?.let { report ->
                    active.sendDocument(chatId, report, "🗺 Карта встреч этой машины")
                }
            }

            action == "pause" -> {
                val paused = !host.isPaused()
                active.sendMessage(chatId, host.setPaused(paused), panel())
            }

            action == "lens" -> active.sendMessage(chatId, host.nextLens(), panel())

            action == "rec" -> active.sendMessage(chatId, host.toggleRecording(), panel())

            action == "session" -> {
                val start = !host.isSessionRunning()
                active.sendMessage(chatId, host.setSessionRunning(start), panel())
            }

            action == "mute:on" -> {
                muted = true
                active.sendMessage(chatId, "🔕 Уведомления выключены", panel())
            }

            action == "mute:off" -> {
                muted = false
                active.sendMessage(chatId, "🔔 Уведомления включены", panel())
            }

            action == "mute" -> {
                muted = !muted
                active.sendMessage(
                    chatId,
                    if (muted) "🔕 Уведомления выключены" else "🔔 Уведомления включены",
                    panel(),
                )
            }

            action == "who" -> active.sendMessage(chatId, host.companionsText(), panel())

            action == "storage" -> active.sendMessage(chatId, host.storageText(), storagePanel())

            // Two taps, never one: a wipe throws away evidence that cannot be recovered.
            action == "wipe" -> active.sendMessage(
                chatId,
                "🧹 <b>Полная очистка</b>\n${host.storageText()}\n\nУдалить всё это? Отменить будет нельзя.",
                TelegramClient.inlineKeyboard(
                    listOf(
                        listOf("🗑 Да, стереть всё" to "wipe:yes"),
                        listOf("⬅️ Отмена" to "status"),
                    ),
                ),
            )

            action == "wipe:yes" -> {
                if (!isOwner) {
                    active.sendMessage(chatId, "Стирать данные может только владелец", panel())
                } else {
                    // The evidence is gone; the memory of having warned about it has to go too, or
                    // the first sighting after a wipe is silently treated as a duplicate.
                    alertState.clear()
                    dossierSentMs.clear()
                    active.sendMessage(chatId, host.wipeAll(), panel())
                }
            }

            action == "admins" -> active.sendMessage(chatId, adminsText(settings()), adminPanel())

            action == "settings" -> active.sendMessage(chatId, host.settingsText(), settingsPanel())

            action.startsWith("set:") -> {
                val parts = action.removePrefix("set:").split(":")
                val key = parts.getOrNull(0).orEmpty()
                val delta = parts.getOrNull(1)?.toIntOrNull() ?: 0
                host.applySetting(key, delta)
                active.sendMessage(chatId, host.settingsText(), settingsPanel())
            }

            action.startsWith("approve:") -> {
                val id = action.removePrefix("approve:").toLongOrNull()
                if (!isOwner || id == null) {
                    active.sendMessage(chatId, "Только владелец может добавлять админов")
                } else {
                    addAdmin(active, chatId, id, true)
                }
            }

            action.startsWith("deladmin:") -> {
                val id = action.removePrefix("deladmin:").toLongOrNull()
                removeAdmin(active, chatId, id, isOwner)
            }

            else -> active.sendMessage(chatId, HELP, panel())
        }
    }

    private fun sendVehicleCard(active: TelegramClient, chatId: Long, plate: String) {
        val card = host.vehicleCard(plate)
        if (card == null) {
            active.sendMessage(chatId, "Не встречалась: <code>$plate</code>", panel())
            return
        }
        active.sendMessage(
            chatId,
            card.first,
            TelegramClient.inlineRow(listOf("⛔️ В чёрный список" to "bl:$plate")),
        )
        val shots = card.second.takeLast(MAX_PHOTOS)
        // Say it rather than let the card's "с фото: 12" be contradicted by nine attachments.
        if (card.second.size > shots.size) {
            active.sendMessage(
                chatId,
                "📷 показываю последние ${shots.size} снимков из ${card.second.size}",
            )
        }
        val failed = shots.count { photo -> !active.sendPhoto(chatId, photo, photoCaption(photo)) }
        if (failed > 0) {
            active.sendMessage(chatId, "⚠️ $failed из ${shots.size} фото не ушли — повтори /card")
        }
    }

    private fun addAdmin(active: TelegramClient, chatId: Long, id: Long?, isOwner: Boolean) {
        if (!isOwner) {
            active.sendMessage(chatId, "Только владелец может добавлять админов", panel())
            return
        }
        if (id == null || id == 0L) {
            active.sendMessage(chatId, "Формат: /addadmin 123456789", panel())
            return
        }
        store.addAdmin(id, "id $id", ROLE_ADMIN, System.currentTimeMillis())
        active.sendMessage(chatId, "✅ Админ <code>$id</code> добавлен", adminPanel())
        active.sendMessage(id, "✅ Тебе выдан доступ к LensALPR", panel())
    }

    private fun removeAdmin(active: TelegramClient, chatId: Long, id: Long?, isOwner: Boolean) {
        if (!isOwner) {
            active.sendMessage(chatId, "Только владелец может удалять админов", panel())
            return
        }
        val removed = id != null && store.removeAdmin(id)
        active.sendMessage(
            chatId,
            if (removed) "🗑 Админ <code>$id</code> удалён" else "Не найден",
            adminPanel(),
        )
    }

    private fun adminsText(config: BotSettings): String {
        val admins = store.admins()
        return buildString {
            append("👥 <b>Админы</b>\n")
            append("владелец: <code>${config.ownerId}</code>\n")
            if (admins.isEmpty()) {
                append("больше никого\n")
            } else {
                admins.forEach { admin -> append("• <code>${admin.chatId}</code> — ${admin.title}\n") }
            }
            append("\nДобавить: <code>/addadmin id</code>  ·  убрать: <code>/deladmin id</code>")
        }
    }

    private fun panel(): String = TelegramClient.inlineKeyboard(
        listOf(
            listOf("👁 Кто сзади" to "who", "📷 Кадр" to "photo", "📊 Статус" to "status"),
            listOf("🗺 1ч" to "report:1", "🗺 6ч" to "report:6", "🗺 24ч" to "report:24"),
            listOf("🚗 Машины" to "list", "⛔️ ЧС" to "bl"),
            listOf(
                (if (host.isPaused()) "▶️ Продолжить" else "⏸ Пауза") to "pause",
                "🔀 Линза" to "lens",
            ),
            listOf(
                (if (host.isSessionRunning()) "🛑 Остановить" else "🟢 Запустить") to "session",
                "🎥 Запись" to "rec",
            ),
            listOf("⚙️ Настройки" to "settings", "👥 Админы" to "admins"),
            listOf((if (muted) "🔔 Звук" else "🔕 Тихо") to "mute", "💾 Память" to "storage"),
        ),
    )

    private fun storagePanel(): String = TelegramClient.inlineKeyboard(
        listOf(
            listOf("🧹 Стереть всё" to "wipe"),
            listOf("⬅️ Меню" to "status"),
        ),
    )

    /** Every threshold the operator tunes from the car, with one tap per step. */
    private fun settingsPanel(): String {
        // A row of three buttons with no indication of which one is already pressed is how the
        // operator ended up running the A/B comparison for days while believing the scanner was on
        // its safe setting. The tick is the whole point of the row.
        val crops = settings().narrowCrops
        fun crop(label: String, mode: Int) =
            (if (crops == mode) "✅ $label" else label) to "set:=narrow:$mode"
        return TelegramClient.inlineKeyboard(
            listOf(
                listOf("⏱ хвост −30с" to "set:tail:-30", "⏱ +30с" to "set:tail:30"),
                listOf("🎥 стоп −15с" to "set:absent:-15", "🎥 +15с" to "set:absent:15"),
                listOf("🎥 вкл/выкл" to "set:video:1", "😴 сон на стоянке" to "set:park:1"),
                listOf("🔔 уровень −" to "set:level:-1", "🔔 уровень +" to "set:level:1"),
                listOf("🤝 встреч −" to "set:meetings:-1", "🤝 встреч +" to "set:meetings:1"),
                listOf(
                    crop("📐 по машине", RuntimeSettings.NARROW_OFF),
                    crop("по номеру", RuntimeSettings.NARROW_ON),
                    crop("сравнить", RuntimeSettings.NARROW_EXPERIMENT),
                ),
                listOf("⬅️ Меню" to "status"),
            ),
        )
    }

    private fun adminPanel(): String {
        val rows = store.admins().take(6).map { admin ->
            listOf("🗑 ${admin.chatId}" to "deladmin:${admin.chatId}")
        }
        return TelegramClient.inlineKeyboard(rows + listOf(listOf("⬅️ Меню" to "status")))
    }

    /** Telegram parses HTML in every message; free text has to survive that. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * The alarm as the driver reads it at a red light.
     *
     * A repeat says so in the first line. Without that mark a second identical message looks like
     * Telegram redelivering the old one, and the single most important fact — that the car which
     * was called a tail half an hour ago is behind us *again* — is exactly what gets dismissed.
     */
    private fun alertText(evidence: FollowEvidence, reason: AlertReason): String {
        val level = when {
            // Police first, and it survives every other verdict: what the driver needs to read at
            // a glance is which of the two hand-made lists this is.
            evidence.police -> "🚔 <b>ПОЛИЦИЯ</b>"
            evidence.blacklisted -> "⛔️ <b>ЧЁРНЫЙ СПИСОК</b>"
            evidence.level == ThreatLevel.TAIL -> "🔴 <b>ХВОСТ</b>"
            evidence.level == ThreatLevel.SUSPECT -> "🟠 <b>ПОДОЗРЕНИЕ</b>"
            evidence.level == ThreatLevel.WATCH -> "🟡 <b>НАБЛЮДЕНИЕ</b>"
            else -> "⚪️ <b>Контакт</b>"
        }
        val header = when (reason) {
            AlertReason.RETURNED -> "↩️ <b>СНОВА РЯДОМ</b> · $level"
            AlertReason.BLACKLISTED -> "↩️ $level · снова рядом"
            AlertReason.PRESENT -> "📍 <b>ВСЁ ЕЩЁ СЗАДИ</b> · $level"
            else -> level
        }
        val details = listOfNotNull(evidence.makeModel, evidence.color).joinToString(" · ")
        return buildString {
            append(header).append('\n')
            append("<code>${evidence.displayPlate}</code>")
            if (details.isNotBlank()) append("  $details")
            append('\n')
            if (reason != AlertReason.RAISED && evidence.awayMs >= 60_000L) {
                append("пропадала на ${evidence.awayMs / 60_000} мин\n")
            }
            if (evidence.reasons.isNotEmpty()) append(evidence.reasons.joinToString(" · ")).append('\n')
            append("линза ${evidence.lastLens ?: "?"} · встреч ${evidence.encounters}")
        }
    }

    companion object {
        const val TAG = "LensALPR.Bot"
        const val ROLE_ADMIN = "admin"
        const val ALERT_COOLDOWN_MS = 180_000L

        /**
         * How often a "still behind us" line may reach the chat.
         *
         * The voice repeats every few seconds; this channel cannot. A Telegram bot may send about
         * one message per second to a chat and roughly twenty a minute to a group before the API
         * starts answering 429, and those refusals would queue behind the alarms that matter.
         */
        const val PRESENT_TELEGRAM_MS = 120_000L

        /** A car's full history is worth sending about once per drive, not once per reappearance. */
        const val DOSSIER_COOLDOWN_MS = 30L * 60_000L
        const val RETRY_DELAY_MS = 4_000L
        const val MAX_PHOTOS = 6

        /** Undelivered alarms held at once; past this the least urgent and oldest is dropped. */
        const val MAX_PENDING_ALERTS = 20

        /** Attempts before an alarm is written off — about twenty minutes of trying. */
        const val MAX_ALERT_ATTEMPTS = 10

        /** Delay before each retry pass, indexed by the least-tried alarm in the queue. */
        val RETRY_BACKOFF_MS = longArrayOf(
            5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 120_000L, 180_000L,
        )
        val PHOTO_TIME = SimpleDateFormat("dd.MM HH:mm", Locale.US)

        /** A bot may upload 50 MB; anything larger is refused outright. */
        const val MAX_UPLOAD_MB = 49.0

        val HELP = """
            <b>LensALPR — пульт</b>

            /who — кто сейчас за тобой и сколько держится
            /status — что происходит сейчас
            /check — проверка готовности перед выездом
            /photo — кадр с камеры прямо сейчас
            /report [часов] — HTML-отчёт с картой и фото встреч
            /list — машины от уровня «наблюдение», /all — вообще все
            /find текст — поиск по номерам
            /plate НОМЕР — карточка машины и фото встреч
            /bl НОМЕР, /unbl НОМЕР, /blacklist — чёрный список
            /police НОМЕР, /unpolice НОМЕР, /policelist — полиция: кричит как чёрный список
            /ignore НОМЕР, /unignore НОМЕР, /ignored — «свои» машины, которые не тревожат
            /rename СТАРЫЙ НОВЫЙ — поправить номер или разделить неверную склейку
            /pause — пауза и продолжение, /lens — следующая линза
            /stop — остановить сессию и отпустить камеру, /go — запустить снова
            /rec — начать или закончить запись вручную
            /settings — пороги; /set tail 120, /set absent 60, /set level 2, /set meetings 2
            /admins, /addadmin id, /deladmin id
            /storage — сколько занято на телефоне
            /wipe — полная очистка: база, фото, видео, отчёты
            /mute, /unmute — уведомления
            /howto — как проверить слежку на дороге
        """.trimIndent()

        /**
         * How to make the scanner say "хвост" on purpose.
         *
         * Written down because the thresholds are not guessable, and a test that fails for the
         * wrong reason — driving straight, or the follower never leaving the frame — reads exactly
         * like a broken app. Every number here is the one the code actually uses.
         */
        val HOWTO = """
            <b>Как проверить слежку на дороге</b>

            <b>1. Что нужно, чтобы дошло до ХВОСТА</b>
            Все пути к «хвосту» требуют минимум <b>один общий поворот</b>. По прямой дороге
            хвостом не станет никогда — максимум «подозрение».
            Общий поворот засчитывается, если машина была видна <b>за 60 с до</b> поворота
            и снова <b>в течение 120 с после</b> него.

            Быстрее всего сработает так:
            • <b>3 общих поворота</b> → ХВОСТ сразу
            • 2 поворота + <b>5 минут</b> рядом → ХВОСТ
            • 2 раза потерялась и вернулась + 1 поворот → ХВОСТ

            Повороты считаются по GPS-курсу. На медленном развороте с полной остановкой
            манёвр может не засчитаться — лучше повороты <b>на ходу</b>, перекрёстки, круговые.

            <b>2. Ступени до этого</b>
            🟡 наблюдение — 3 распознавания + 1 мин рядом или 700 м
            🟠 подозрение — 2 поворота, либо 3 мин + 2.5 км (по прямой — 8 км)
            🔴 хвост — см. выше
            ⛔️ чёрный список — вручную кнопкой или /bl НОМЕР

            <b>3. Чтобы услышать «СНОВА РЯДОМ»</b>
            Машина должна пропасть из виду <b>дольше 2 минут</b>, и только потом вернуться.
            Если она всё время висит сзади — тревога будет <b>одна</b>, на повышение уровня.
            Это не поломка, это чтобы предупреждение не превратилось в бубнёж.
            Потолок — <b>одна тревога на машину в 8 минут</b>.

            <b>4. Что произойдёт при «хвосте»</b>
            • голос вслух: уровень, номер по буквам, марка и цвет, улики
            • сообщение с фото и кнопками «в чёрный список» / «игнорировать»
            • все фото встреч + карта маршрута
            • <b>начинается видеозапись</b> (нарезкой по 90 с)

            <b>5. Порог голоса и тревог</b>
            /settings → «🔔 уровень −/+». Сейчас читает вслух и шлёт в чат
            от выбранного уровня и выше. Ниже — только в списке машин.

            <b>6. Если тревог нет вообще</b>
            /check — там видно, распознаются ли кадры. Если написано
            «кадры идут, но не распознаются» — дело в линзе, а не в слежке.

            <b>7. В конце</b>
            /stop — придёт разбор поездки и HTML-отчёт со всеми уликами.
        """.trimIndent()
    }
}
