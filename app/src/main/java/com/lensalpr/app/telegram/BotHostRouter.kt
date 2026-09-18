package com.lensalpr.app.telegram

import java.io.File

/**
 * Points the bot at the scanner when there is one, and answers honestly when there is not.
 *
 * The bot used to be a field of the scanning activity, so closing the app took the only remote
 * control with it — and a phone that has locked itself out has no activity at all, which is
 * precisely when the bot matters most. Now the bot lives in its own service and talks to this,
 * and the activity attaches and detaches as it comes and goes.
 *
 * Every answer here is a statement of fact rather than an error: "scanning is not running" is
 * something the operator can act on, an exception is not.
 */
object BotHostRouter : BotHost {

    @Volatile
    private var delegate: BotHost? = null

    /** True while a live scanner is attached. */
    val attached: Boolean get() = delegate != null

    fun attach(host: BotHost) {
        delegate = host
    }

    /** Detaches [host], but only if it is still the current one — a later attach must not be undone. */
    fun detach(host: BotHost) {
        if (delegate === host) delegate = null
    }

    private const val IDLE = "💤 Сканирование не запущено — открой приложение на телефоне."

    override fun statusText(): String = delegate?.statusText() ?: IDLE
    override fun buildReport(sinceMs: Long): File? = delegate?.buildReport(sinceMs)
    override fun buildVehicleReport(plate: String): File? = delegate?.buildVehicleReport(plate)
    override fun listVehicles(minLevel: Int): String = delegate?.listVehicles(minLevel) ?: IDLE
    override fun vehicleCard(plate: String): Pair<String, List<File>>? = delegate?.vehicleCard(plate)
    override fun setBlacklist(plate: String, blacklisted: Boolean): String =
        delegate?.setBlacklist(plate, blacklisted) ?: IDLE
    override fun blacklistText(): String = delegate?.blacklistText() ?: IDLE
    override fun currentTripStartMs(): Long = delegate?.currentTripStartMs() ?: 0L
    override fun snapshot(): File? = delegate?.snapshot()
    override fun setPaused(paused: Boolean): String = delegate?.setPaused(paused) ?: IDLE
    override fun isPaused(): Boolean = delegate?.isPaused() ?: true
    override fun setSessionRunning(running: Boolean): String =
        delegate?.setSessionRunning(running) ?: IDLE
    override fun isSessionRunning(): Boolean = delegate?.isSessionRunning() ?: false
    override fun nextLens(): String = delegate?.nextLens() ?: IDLE
    override fun toggleRecording(): String = delegate?.toggleRecording() ?: IDLE
    override fun findPlates(query: String): String = delegate?.findPlates(query) ?: IDLE
    override fun companionsText(): String = delegate?.companionsText() ?: IDLE
    override fun preflightText(): String = delegate?.preflightText() ?: IDLE
    override fun settingsText(): String = delegate?.settingsText() ?: IDLE
    override fun applySetting(key: String, delta: Int): String =
        delegate?.applySetting(key, delta) ?: IDLE
    override fun storageText(): String = delegate?.storageText() ?: IDLE
    override fun wipeAll(): String = delegate?.wipeAll() ?: IDLE
    override fun setIgnored(plate: String, ignored: Boolean): String =
        delegate?.setIgnored(plate, ignored) ?: IDLE
    override fun setPolice(plate: String, police: Boolean): String =
        delegate?.setPolice(plate, police) ?: IDLE
    override fun policeText(): String = delegate?.policeText() ?: IDLE
    override fun ignoredText(): String = delegate?.ignoredText() ?: IDLE
    override fun renamePlate(from: String, to: String): String =
        delegate?.renamePlate(from, to) ?: IDLE
}
