package com.lensalpr.app.telegram

/**
 * Built-in bot credentials.
 *
 * Baked in deliberately so a fresh install is already reachable from Telegram with no setup. The
 * token ships inside the APK, which means anyone holding the APK controls the bot - fine for a
 * private development build, not for anything distributed. The setup screen overrides both values
 * when they are filled in.
 */
object TelegramDefaults {
    const val BOT_TOKEN = "7834189384:AAHNEkhPgrW_QLrd5VnsRkS8pMfQPMMzxmg"

    /** Owner: may add and remove other admins. */
    const val OWNER_ID = 738339858L

    const val ENABLED = true
}
