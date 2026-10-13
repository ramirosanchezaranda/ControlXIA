package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Cuentas dichas en voz alta, sin el modelo: "cuánto es 25 por 4", "1500
 * dividido 3", "el 15 por ciento de 2000", "raíz de 81", "7 al cuadrado".
 */
object Calculator {

    private val PREFIX = Regex("^(?:(?:decime )?cuanto (?:es|da|son|seria)|calcula|calculame|calcular|el resultado de|resolve|cuanto)\\s+")

    private val NUMBERS = mapOf(
        "cero" to "0", "uno" to "1", "una" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5",
        "seis" to "6", "siete" to "7", "ocho" to "8", "nueve" to "9", "diez" to "10", "once" to "11",
        "doce" to "12", "quince" to "15", "veinte" to "20", "treinta" to "30", "cuarenta" to "40",
        "cincuenta" to "50", "cien" to "100", "mil" to "1000",
    )

    /** La respuesta hablada, o null si [text] no es una cuenta. */
    fun answer(text: String, agentName: String = ""): String? {
        var s = Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("(\\d)\\.(\\d{3})(?!\\d)"), "$1$2") // 1.500 → 1500
            .replace(Regex("(\\d),(\\d)"), "$1.$2") // 2,5 → 2.5
            .replace(Regex("[¿?¡!]"), " ")
            .trim()
        if (agentName.isNotBlank()) s = s.replace(Regex("^${Regex.escape(agentName.lowercase(Locale("es")))}[, ]+"), "")
        val explicit = PREFIX.containsMatchIn(s)
        s = s.replace(PREFIX, "")
        s = s.replace(Regex("\\b(?:por ?ciento|%) (?:de|del)\\b"), " %of ")
            .replace(Regex("\\bmultiplicado por\\b"), " * ")
            .replace(Regex("\\bdividido (?:por|en|entre)?\\b"), " / ")
            .replace(Regex("\\bsobre\\b"), " / ")
            .replace(Regex("\\bal cuadrado\\b"), " ^ 2 ")
            .replace(Regex("\\bal cubo\\b"), " ^ 3 ")
            .replace(Regex("\\belevado (?:a la|a)\\b"), " ^ ")
            .replace(Regex("\\braiz (?:cuadrada )?de\\b"), " r ")
            .replace(Regex("\\bmas\\b"), " + ")
            .replace(Regex("\\bmenos\\b"), " - ")
            .replace(Regex("\\bpor\\b|\\bx\\b"), " * ")
            .replace(Regex("\\b(?:el|la|los|las)\\b"), " ")
            .replace(Regex("([+*/^()-])"), " $1 ")
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { NUMBERS[it] ?: it }
        if (tokens.isEmpty()) return null
        val isNumber = { t: String -> t.toDoubleOrNull() != null }
        val ops = setOf("+", "-", "*", "/", "^", "%of", "r", "(", ")")
        if (tokens.any { !isNumber(it) && it !in ops }) return null
        if (tokens.none { it in ops } || tokens.none(isNumber)) return null
        if (!explicit && tokens.size < 3 && "r" !in tokens) return null
        val value = runCatching { Parser(tokens).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return "Esa cuenta no tiene resultado (¿dividiste por cero?)."
        return "Da ${format(value)}."
    }

    private fun format(value: Double): String {
        if (abs(value - round(value)) < 1e-9 && abs(value) < 1e15) return "%,d".format(Locale("es", "AR"), round(value).toLong())
        return "%,.2f".format(Locale("es", "AR"), value).trimEnd('0').trimEnd(',')
    }

    /** Suma y resta < producto, división y porcentaje < potencia < raíz y signo. */
    private class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun parse(): Double {
            val value = sum()
            require(pos == tokens.size)
            return value
        }

        private fun peek() = tokens.getOrNull(pos)

        private fun sum(): Double {
            var value = product()
            while (peek() == "+" || peek() == "-") {
                val op = tokens[pos++]
                val right = product()
                value = if (op == "+") value + right else value - right
            }
            return value
        }

        private fun product(): Double {
            var value = power()
            while (peek() == "*" || peek() == "/" || peek() == "%of") {
                val op = tokens[pos++]
                val right = power()
                value = when (op) {
                    "*" -> value * right
                    "/" -> value / right
                    else -> value / 100 * right // 15 por ciento de 2000
                }
            }
            return value
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == "^") {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double = when (peek()) {
            "-" -> { pos++; -unary() }
            "+" -> { pos++; unary() }
            "r" -> { pos++; sqrt(unary()) }
            "(" -> { pos++; val v = sum(); require(peek() == ")"); pos++; v }
            else -> tokens.getOrNull(pos++)?.toDoubleOrNull() ?: error("número")
        }
    }
}
