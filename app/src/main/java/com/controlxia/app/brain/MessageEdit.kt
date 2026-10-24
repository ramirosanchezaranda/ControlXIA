package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Corregir de palabra el mensaje que espera el "sí", sin dictarlo de nuevo.
 * El reconocedor a veces entiende mal una palabra ("la IA" → "la guía"): así
 * se arregla solo esa.
 *
 * - «cambiá guía por IA», «reemplazá X por Y», «en vez de X poné Y»,
 *   «poné Y en vez de X», «no es X, es Y», «no dije X, dije Y»
 * - «no, ponele …», «no, que diga …», «mejor decile …»: el mensaje entero nuevo
 * - «agregale …»: al final
 * - «sacale …», «borrá …»: lo saca
 *
 * Sin Android: se prueba en la PC.
 */
object MessageEdit {

    sealed interface Result {
        data class Edited(val text: String) : Result

        /** Pidió cambiar o sacar algo que no está en el mensaje. */
        data class NotFound(val words: String) : Result
    }

    private const val LEAD = """(?:(?:no|mejor|perd[oó]n)[,.]?\s+)*"""
    private const val SAY = """(?:pon[eé](?:le)?|escrib[ií](?:le)?|dec[ií](?:le)?)"""

    private val REPLACE = listOf(
        Regex("""(?iu)^$LEAD(?:cambi[aá](?:le)?|re?emplaz[aá](?:le)?|correg[ií](?:le)?)\s+(?:la palabra\s+)?(?<from>.+?)\s+por\s+(?<to>.+)$"""),
        Regex("""(?iu)^$LEAD(?:en vez de|en lugar de)\s+(?<from>.+?),?\s+(?:$SAY|va|es|que diga)\s+(?<to>.+)$"""),
        Regex("""(?iu)^$LEAD$SAY\s+(?<to>.+?)\s+(?:en vez de|en lugar de)\s+(?<from>.+)$"""),
        Regex("""(?iu)^no\s+(?:es|era|dice|dije|va)\s+(?<from>.+?),?\s+(?:es|era|dice|dije|va|sino)\s+(?<to>.+)$"""),
    )

    /** El mensaje entero de nuevo: tiene que empezar con "no" o "mejor", o decir "que diga". */
    private val WHOLE = listOf(
        Regex("""(?iu)^(?:(?:no|mejor)[,.]?\s+)+(?:$SAY|mand[aá](?:le)?)\s+(?:que\s+)?(?<text>.+)$"""),
        Regex("""(?iu)^$LEAD(?:que diga|que ponga|el mensaje es|el texto es|el mensaje que diga)\s*:?\s+(?<text>.+)$"""),
    )

    private val APPEND = Regex("""(?iu)^(?:y\s+)?(?:agreg[aá](?:le)?|sum[aá](?:le)?|añad[ií](?:le)?)\s+(?:al final\s+)?(?:que\s+)?(?<text>.+)$""")

    private val REMOVE = Regex("""(?iu)^(?:sac[aá](?:le)?|borr[aá](?:le)?|quit[aá](?:le)?)\s+(?:la palabra\s+)?(?<text>.+)$""")

    /** "Decile a Juan que…", "mandale a Pedro…": no es corregir, es otro destinatario. */
    private val OTHER_PERSON = Regex("""(?iu)^a\s+\S+.*""")

    private val ARTICLE = Regex("""(?iu)^(?:la|el|los|las|un|una|lo)\s+""")

    /** El mensaje corregido si [command] es una corrección de [text]; null si es otra cosa. */
    fun apply(command: String, text: String): Result? {
        val said = clean(command)
        if (said.isEmpty()) return null
        REPLACE.firstNotNullOfOrNull { it.matchEntire(said) }?.let { m ->
            return replace(text, part(m, "from"), part(m, "to"))
        }
        WHOLE.firstNotNullOfOrNull { it.matchEntire(said) }?.let { m ->
            val whole = part(m, "text")
            if (whole.isEmpty() || OTHER_PERSON.matches(whole)) return null
            return Result.Edited(whole)
        }
        APPEND.matchEntire(said)?.let { m ->
            val extra = part(m, "text").ifEmpty { return null }
            return Result.Edited(text.trimEnd() + " " + extra)
        }
        REMOVE.matchEntire(said)?.let { m ->
            val gone = part(m, "text").ifEmpty { return null }
            val pattern = loose(gone)
            if (!pattern.containsMatchIn(text)) return Result.NotFound(gone)
            val left = pattern.replace(text, "").replace(Regex("""\s{2,}"""), " ").replace(Regex("""\s+([,.!?])"""), "$1").trim()
            return if (left.isEmpty()) Result.NotFound(gone) else Result.Edited(left)
        }
        return null
    }

    private fun replace(text: String, from: String, to: String): Result {
        if (from.isEmpty() || to.isEmpty()) return Result.NotFound(from)
        val pattern = loose(from)
        if (pattern.containsMatchIn(text)) return Result.Edited(pattern.replace(text) { to })
        // "Cambiá la guía por la IA" con el mensaje "…escribió guía": sin el artículo.
        val bare = from.replace(ARTICLE, "")
        if (bare != from && bare.isNotEmpty()) {
            val barePattern = loose(bare)
            if (barePattern.containsMatchIn(text)) return Result.Edited(barePattern.replace(text) { to.replace(ARTICLE, "") })
        }
        return Result.NotFound(from)
    }

    private fun part(match: MatchResult, name: String): String = clean(match.groups[name]?.value.orEmpty())

    /** Sin comillas, sin espacios de más ni el punto final. */
    private fun clean(text: String): String =
        text.trim().trim('«', '»', '"', '“', '”', '\'').trim().trimEnd('.', '!', '?', ',', ';', ':').trim()

    /** Las mismas palabras, con o sin tildes y mayúsculas, como palabras enteras. */
    private fun loose(words: String): Regex {
        val body = words.trim().split(Regex("""\s+""")).joinToString("""\s+""") { word ->
            word.map { c -> VOWELS[base(c)] ?: Regex.escape(c.toString()) }.joinToString("")
        }
        return Regex("""(?iu)(?<![\p{L}\p{N}])$body(?![\p{L}\p{N}])""")
    }

    private fun base(c: Char): Char =
        Normalizer.normalize(c.toString().lowercase(Locale("es")), Normalizer.Form.NFD).first()

    private val VOWELS = mapOf(
        'a' to "[aáàâä]",
        'e' to "[eéèêë]",
        'i' to "[iíìîï]",
        'o' to "[oóòôö]",
        'u' to "[uúùûü]",
    )
}
