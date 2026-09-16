package com.lensalpr.app.telegram

import android.os.SystemClock
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One incoming Telegram event, flattened to what the bot actually needs. */
data class BotUpdate(
    val updateId: Long,
    val chatId: Long,
    val senderId: Long,
    val senderName: String,
    val text: String?,
    val callbackData: String?,
    val callbackId: String?,
)

/**
 * Direct Bot API client: the phone talks to Telegram itself, there is no relay server to run.
 *
 * Calls are blocking and belong on a worker thread. The token is never logged.
 */
class TelegramClient(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(POLL_TIMEOUT_SECONDS + 20L, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * A separate ceiling for uploads, because evidence takes as long as the uplink takes.
     *
     * `callTimeout` bounds the whole operation including the request body, and a forty-megabyte
     * clip on a mobile uplink in motion — one to three megabits, less in a tunnel — needs minutes.
     * The three-minute limit shared with commands cut those uploads off mid-body and reported them
     * as "Telegram не принял", which is not what happened. Still finite: this lane also carries
     * photos and reports, and one stalled clip must not hold them for ever. A dead socket is
     * caught by the shorter write timeout long before the call ceiling.
     */
    private val uploadHttp = http.newBuilder()
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    val isConfigured: Boolean get() = TOKEN_PATTERN.matches(token)

    fun getMe(): String? {
        val result = call("getMe", FormBody.Builder().build()) ?: return null
        return result.optJSONObject("result")?.optString("username")?.takeIf { it.isNotBlank() }
    }

    /**
     * Sends a text, in as many messages as it takes.
     *
     * The Bot API refuses anything past 4096 characters, and cutting the string at a fixed offset
     * cut through tags and entities — a long blacklist became invalid HTML and never arrived at
     * all. Long texts are split on line boundaries instead, which every list this app produces
     * respects, and a line that is itself too long is split on a space.
     */
    fun sendMessage(chatId: Long, text: String, replyMarkup: String? = null): Boolean {
        val chunks = splitForTelegram(text)
        var ok = true
        chunks.forEachIndexed { index, chunk ->
            // The keyboard goes with the last piece, where the reader ends up.
            val markup = if (index == chunks.lastIndex) replyMarkup else null
            val body = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", chunk)
                .add("parse_mode", "HTML")
                .add("disable_web_page_preview", "true")
                .apply { if (markup != null) add("reply_markup", markup) }
                .build()
            if (call("sendMessage", body) == null) ok = false
        }
        return ok
    }

    fun sendPhoto(
        chatId: Long,
        photo: File,
        caption: String,
        replyMarkup: String? = null,
    ): Boolean {
        if (!photo.exists()) return sendMessage(chatId, caption, replyMarkup)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption.take(MAX_CAPTION))
            .addFormDataPart("parse_mode", "HTML")
            .apply { if (replyMarkup != null) addFormDataPart("reply_markup", replyMarkup) }
            .addFormDataPart("photo", photo.name, photo.asRequestBody(JPEG))
            .build()
        return call("sendPhoto", body, uploadHttp) != null
    }

    fun sendVideo(chatId: Long, video: File, caption: String): Boolean {
        if (!video.exists()) return false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption.take(MAX_CAPTION))
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("supports_streaming", "true")
            .addFormDataPart("video", video.name, video.asRequestBody(MP4))
            .build()
        return call("sendVideo", body, uploadHttp) != null
    }

    fun sendDocument(chatId: Long, document: File, caption: String): Boolean {
        if (!document.exists()) return false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption.take(MAX_CAPTION))
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("document", document.name, document.asRequestBody(HTML))
            .build()
        return call("sendDocument", body, uploadHttp) != null
    }

    fun answerCallback(callbackId: String, text: String?) {
        val body = FormBody.Builder()
            .add("callback_query_id", callbackId)
            .apply { if (text != null) add("text", text.take(180)) }
            .build()
        call("answerCallbackQuery", body)
    }

    /**
     * Long-polls for commands.
     *
     * Returns null when Telegram could not be reached at all, and an empty list on a quiet
     * timeout: the two used to look the same, and the poller treated a failed *skip of the
     * backlog* as an empty backlog — then replayed yesterday's commands.
     */
    fun getUpdates(offset: Long, timeoutSeconds: Long = POLL_TIMEOUT_SECONDS): List<BotUpdate>? {
        val body = FormBody.Builder()
            .add("offset", offset.toString())
            .add("timeout", timeoutSeconds.toString())
            .add("allowed_updates", """["message","callback_query"]""")
            .build()
        val result = call("getUpdates", body) ?: return null
        val array = result.optJSONArray("result") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::parseUpdate)?.let(::add)
            }
        }
    }

    private fun parseUpdate(update: JSONObject): BotUpdate? {
        val updateId = update.optLong("update_id", -1L)
        if (updateId < 0L) return null

        update.optJSONObject("callback_query")?.let { callback ->
            val from = callback.optJSONObject("from") ?: return null
            val chat = callback.optJSONObject("message")?.optJSONObject("chat")
            return BotUpdate(
                updateId = updateId,
                chatId = chat?.optLong("id") ?: from.optLong("id"),
                senderId = from.optLong("id"),
                senderName = displayName(from),
                text = null,
                callbackData = callback.optString("data").takeIf { it.isNotBlank() },
                callbackId = callback.optString("id").takeIf { it.isNotBlank() },
            )
        }

        val message = update.optJSONObject("message") ?: return null
        val from = message.optJSONObject("from") ?: return null
        val chat = message.optJSONObject("chat") ?: return null
        return BotUpdate(
            updateId = updateId,
            chatId = chat.optLong("id"),
            senderId = from.optLong("id"),
            senderName = displayName(from),
            text = message.optString("text").takeIf { it.isNotBlank() },
            callbackData = null,
            callbackId = null,
        )
    }

    private fun displayName(from: JSONObject): String {
        val name = listOfNotNull(
            from.optString("first_name").takeIf { it.isNotBlank() },
            from.optString("last_name").takeIf { it.isNotBlank() },
        ).joinToString(" ")
        val username = from.optString("username").takeIf { it.isNotBlank() }
        return when {
            username != null && name.isNotBlank() -> "$name (@$username)"
            username != null -> "@$username"
            name.isNotBlank() -> name
            else -> from.optLong("id").toString()
        }
    }

    /** When Telegram last answered anything at all, on the elapsed-realtime clock. */
    @Volatile
    var lastReachedAtMs: Long = 0L
        private set

    /** When a request last failed to reach Telegram. */
    @Volatile
    var lastFailureAtMs: Long = 0L
        private set

    /**
     * The last thing the Bot API refused, as it described it — or null while it is answering
     * normally. A wire that works and a bot that rejects every call with "Unauthorized" used to
     * look identical from the outside; this is what tells them apart.
     */
    @Volatile
    var lastApiError: String? = null
        private set

    /** The token itself is rejected: no retry, no network change and no waiting will fix that. */
    @Volatile
    var isRejected: Boolean = false
        private set

    /**
     * Whether the bot can currently reach Telegram.
     *
     * True until proven otherwise, and true again the moment anything gets through: a single
     * failure in a tunnel is not an outage, but a run of them with nothing succeeding in between
     * means every alarm raised meanwhile went nowhere.
     */
    val isReachable: Boolean
        get() = lastFailureAtMs == 0L || lastReachedAtMs >= lastFailureAtMs

    /**
     * One Bot API call, honouring the one back-off Telegram actually tells us about.
     *
     * A burst of encounter photos is the normal case here — a confirmed tail arrives with its whole
     * dossier — and Telegram answers a burst with `429 Too Many Requests` and a `retry_after`. That
     * was logged at warn level and thrown away, so the pictures simply did not appear and the card
     * above them still said the car had been met five times. Waiting the number of seconds the
     * server asked for is the entire fix; it is not a guess. A wait longer than a lane can afford
     * is not attempted at all — the caller's own retry queue comes back later — rather than spent
     * on attempts that are certain to be refused before the deadline.
     */
    private fun call(
        method: String,
        body: okhttp3.RequestBody,
        /** Uploads get the generous ceiling; commands keep the short one. */
        client: OkHttpClient = http,
    ): JSONObject? {
        if (!isConfigured) return null
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url("$API$token/$method")
                .post(body)
                .build()
            val outcome = try {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (payload.isBlank()) return null
                    val json = JSONObject(payload)
                    // Reached Telegram and got an answer, whatever it said: the wire works.
                    lastReachedAtMs = SystemClock.elapsedRealtime()
                    if (json.optBoolean("ok", false)) {
                        lastApiError = null
                        return json
                    }
                    val description = json.optString("description")
                    val code = json.optInt("error_code", 0)
                    lastApiError = if (description.isBlank()) "error $code" else description
                    if (code == 401 || code == 404) isRejected = true
                    val retryAfter = json.optJSONObject("parameters")
                        ?.optInt("retry_after", 0)
                        ?.takeIf { it > 0 }
                    Log.w(TAG, "$method failed: $description")
                    retryAfter
                }
            } catch (error: Exception) {
                // Network hiccups in a moving car are normal; the caller decides whether to retry.
                // Recorded rather than merely logged: an operator whose phone cannot resolve the
                // API host at all gets no alerts, no clips and no reply to /status — and the one
                // thing they must not conclude is that the road was quiet.
                lastFailureAtMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "$method transport error: ${error.javaClass.simpleName}")
                return null
            }
            if (outcome == null || attempt >= MAX_RATE_LIMIT_RETRIES) return null
            val waitMs = outcome * 1_000L
            if (waitMs > MAX_RETRY_AFTER_MS) {
                Log.i(TAG, "$method rate limited for ${outcome}s; leaving it to the caller's queue")
                return null
            }
            attempt += 1
            Log.i(TAG, "$method rate limited; waiting ${waitMs}ms (attempt $attempt)")
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    companion object {
        private const val TAG = "LensALPR.Telegram"
        private const val API = "https://api.telegram.org/bot"
        private const val MAX_TEXT = 4_000
        private const val MAX_CAPTION = 1_000

        /** How many times a call waits out a `retry_after` before giving the caller its failure. */
        private const val MAX_RATE_LIMIT_RETRIES = 2

        /**
         * The longest `retry_after` a lane blocks for. Telegram's usual figure is under a minute;
         * anything longer is handed back so the caller's own queue can come back later instead of
         * the lane standing still.
         */
        private const val MAX_RETRY_AFTER_MS = 65_000L
        const val POLL_TIMEOUT_SECONDS = 30L

        private val JPEG = "image/jpeg".toMediaType()
        private val HTML = "text/html".toMediaType()
        private val MP4 = "video/mp4".toMediaType()
        private val TOKEN_PATTERN = Regex("^[0-9]{5,20}:[A-Za-z0-9_-]{20,220}$")

        /** Telegram parses HTML in every message; free text has to survive that. */
        fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        /**
         * Cuts a message into pieces Telegram accepts, on line boundaries.
         *
         * Every multi-line message this app sends keeps its tags within one line, so cutting
         * between lines never splits a tag. A single line longer than the limit is cut on the
         * last space before it, and as a last resort at the limit itself.
         */
        fun splitForTelegram(text: String, limit: Int = MAX_TEXT): List<String> {
            if (text.length <= limit) return listOf(text)
            val chunks = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotEmpty()) {
                    chunks += current.toString()
                    current.setLength(0)
                }
            }
            text.split('\n').forEach { line ->
                var rest = line
                while (rest.length > limit) {
                    flush()
                    val cut = rest.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
                    chunks += rest.substring(0, cut)
                    rest = rest.substring(cut).trimStart()
                }
                if (current.length + rest.length + 1 > limit) flush()
                if (current.isNotEmpty()) current.append('\n')
                current.append(rest)
            }
            flush()
            return chunks
        }

        fun keyboard(rows: List<List<String>>): String = JSONObject()
            .put("keyboard", JSONArray(rows.map { row -> JSONArray(row) }))
            .put("resize_keyboard", true)
            .put("is_persistent", true)
            .toString()

        /** One row of buttons. */
        fun inlineRow(buttons: List<Pair<String, String>>): String =
            inlineKeyboard(listOf(buttons))

        /** Full inline panel: rows of (label, callback data). */
        fun inlineKeyboard(rows: List<List<Pair<String, String>>>): String = JSONObject()
            .put(
                "inline_keyboard",
                JSONArray(
                    rows.map { row ->
                        JSONArray(
                            row.map { (label, data) ->
                                JSONObject().put("text", label).put("callback_data", data)
                            },
                        )
                    },
                ),
            )
            .toString()
    }
}
