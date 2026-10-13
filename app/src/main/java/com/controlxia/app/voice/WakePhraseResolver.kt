package com.controlxia.app.voice

/**
 * Traduce el nombre que eligió el usuario a palabras que el modelo de voz
 * conoce, para que el asistente responda a cualquier nombre.
 *
 * Vosk solo reconoce palabras de su vocabulario. Si el nombre no está tal cual,
 * se prueba con escrituras que en español suenan igual (sin tilde, x→s, k→c,
 * v→b, ll→y…) y, como último recurso, partido en dos palabras conocidas
 * ("siomara" → "sio" + "mara"). Si nada funciona devuelve null y la app le
 * pide al usuario que lo escriba como suena.
 */
object WakePhraseResolver {

    /** Palabras reconocibles para [phrase] (una o más), o null si no hay forma. */
    fun resolve(phrase: String, hasWord: (String) -> Boolean): List<String>? {
        val words = WakePhraseDetector.normalize(phrase)
        if (words.isEmpty()) return null
        val result = mutableListOf<String>()
        for (word in words) result += resolveWord(word, hasWord) ?: return null
        return result
    }

    private fun resolveWord(word: String, hasWord: (String) -> Boolean): List<String>? {
        val candidates = spellings(word)
        candidates.firstOrNull(hasWord)?.let { return listOf(it) }
        // Partido en dos palabras conocidas, priorizando cortes parejos. Cada
        // parte tiene que tener vocal, para que suene como sílaba ("tl" o "ny"
        // el modelo las conoce como siglas, no como sonido).
        for (candidate in candidates) {
            val cuts = (MIN_PART..candidate.length - MIN_PART)
                .sortedBy { kotlin.math.abs(candidate.length / 2 - it) }
            for (i in cuts) {
                val first = candidate.substring(0, i)
                val second = candidate.substring(i)
                if (!hasVowel(first) || !hasVowel(second)) continue
                if (hasWord(first) && hasWord(second)) return listOf(first, second)
            }
        }
        return null
    }

    /**
     * Escrituras que suenan igual, de la más fiel a la menos: la original,
     * variantes de tilde y una o dos sustituciones fonéticas.
     */
    fun spellings(word: String): List<String> {
        val result = LinkedHashSet<String>()
        result += word
        result += accentVariants(word)
        val firstLevel = RULES.mapNotNull { it.apply(word) }
        result += firstLevel
        firstLevel.forEach { result += accentVariants(it) }
        for (variant in firstLevel) {
            for (rule in RULES) rule.apply(variant)?.let { result += it }
        }
        return result.filter { it.isNotBlank() && it.length <= MAX_LENGTH }
    }

    private fun accentVariants(word: String): List<String> {
        val plain = stripAccents(word)
        if (plain != word) return listOf(plain)
        // Sin tilde: probar con tilde en cada vocal ("mia" → "mía").
        return word.indices.mapNotNull { i ->
            ACCENTED[word[i]]?.let { word.substring(0, i) + it + word.substring(i + 1) }
        }
    }

    private fun hasVowel(part: String): Boolean = part.any { it in VOWELS }

    private fun stripAccents(word: String): String =
        word.map { c -> PLAIN[c] ?: c }.joinToString("")

    private class Rule(pattern: String, private val replacement: String) {
        private val regex = Regex(pattern)

        fun apply(word: String): String? =
            regex.replace(word, replacement).takeIf { it != word }
    }

    private const val MIN_PART = 2
    private const val MAX_LENGTH = 40

    private const val VOWELS = "aeiouáéíóúü"
    private val ACCENTED = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú')
    private val PLAIN = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u')

    /** Sustituciones que en español (rioplatense) no cambian el sonido. */
    private val RULES = listOf(
        Rule("x", "s"),
        Rule("x", "j"),
        Rule("x", "ks"),
        Rule("k(?=[eiéí])", "qu"),
        Rule("k", "c"),
        Rule("qu(?=[eiéí])", "k"),
        Rule("c(?=[eiéí])", "s"),
        Rule("z", "s"),
        Rule("s", "z"),
        Rule("v", "b"),
        Rule("b", "v"),
        Rule("w", "u"),
        Rule("w", "gu"),
        Rule("ll", "y"),
        Rule("y", "ll"),
        Rule("y$", "i"),
        Rule("i$", "y"),
        Rule("y(?=[^aeiouáéíóú]|$)", "i"),
        Rule("^h", ""),
        Rule("j(?=[eiéí])", "g"),
        Rule("g(?=[eiéí])", "j"),
        Rule("sh", "ch"),
        Rule("ch", "sh"),
        Rule("ph", "f"),
        Rule("th", "t"),
        Rule("ck", "k"),
        Rule("([bcdfgklmnpstvz])\\1", "$1"),
        Rule("ee", "i"),
        Rule("oo", "u"),
    )
}
