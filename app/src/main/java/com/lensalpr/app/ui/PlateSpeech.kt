package com.lensalpr.app.ui

/**
 * Turns a plate into something a Russian voice can actually say.
 *
 * Latvian plates are Latin letters and the voice is Russian. Handing it `EN-7209` produces
 * something between an English word and a mumble — at speed, from a phone on the rear glass, that
 * is not a plate anybody can write down. Every character is therefore spoken as its Russian letter
 * name, digits one at a time, and the separator is dropped rather than announced as "минус".
 *
 * The names are the ones a Russian speaker uses out loud for Latin letters, not a transliteration
 * of their sound: `H` is "аш" because that is what people say, even though the letter is not.
 */
object PlateSpeech {

    private val NAMES = mapOf(
        'A' to "а", 'B' to "бэ", 'C' to "цэ", 'D' to "дэ", 'E' to "е", 'F' to "эф",
        'G' to "гэ", 'H' to "аш", 'I' to "и", 'J' to "йот", 'K' to "ка", 'L' to "эль",
        'M' to "эм", 'N' to "эн", 'O' to "о", 'P' to "пэ", 'Q' to "ку", 'R' to "эр",
        'S' to "эс", 'T' to "тэ", 'U' to "у", 'V' to "вэ", 'W' to "дубль вэ",
        'X' to "икс", 'Y' to "игрек", 'Z' to "зет",
    )

    /**
     * The plate as a sequence of spoken tokens.
     *
     * Separated by commas rather than spaces: the pause is what makes seven characters parseable
     * by ear, and without it the engine runs them together into one nonsense word.
     */
    fun spell(plate: String): String = plate
        .uppercase()
        .mapNotNull { char ->
            when {
                char.isDigit() -> char.toString()
                NAMES.containsKey(char) -> NAMES[char]
                // Dashes and spaces are layout, not content; announcing them wastes the one
                // moment the driver is listening.
                else -> null
            }
        }
        .joinToString(", ")

    /**
     * The classifier's English colour and body words, in Russian.
     *
     * Colour is the one field that helps a driver find the car in the mirror, and a Russian voice
     * reading "white" produces "вайт" — a sound that identifies nothing. The vocabulary is a closed
     * set coming out of the vendor classifier, so a lookup covers it; anything unrecognised is left
     * alone rather than mangled, because a brand name usually survives being read as-is.
     */
    private val WORDS = mapOf(
        "black" to "чёрная", "white" to "белая", "silver" to "серебристая", "gray" to "серая",
        "grey" to "серая", "red" to "красная", "blue" to "синяя", "green" to "зелёная",
        "yellow" to "жёлтая", "orange" to "оранжевая", "brown" to "коричневая",
        "tan" to "бежевая", "purple" to "фиолетовая", "gold" to "золотистая",
        "suv" to "внедорожник", "hatchback" to "хэтчбек", "sedan" to "седан", "van" to "фургон",
        "coupe" to "купе", "pickup" to "пикап", "heavytruck" to "грузовик", "mpv" to "минивэн",
        "motorcycle" to "мотоцикл", "bus" to "автобус", "convertible" to "кабриолет",
        "wagon" to "универсал", "truck" to "грузовик",
    )

    fun word(value: String?): String? {
        val key = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return WORDS[key] ?: key
    }
}
