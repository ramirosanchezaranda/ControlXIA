package com.controlxia.app.actions

import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

/**
 * Los productos que se ven en una lista de resultados (Mercado Libre, tiendas):
 * cada precio con el título que tiene arriba. Se arma con los textos que la
 * app le muestra a la accesibilidad, en el orden de la pantalla. La parte que
 * decide no usa Android: se prueba en la PC.
 */
object ScreenItems {

    data class Item(val title: String, val price: String)

    /** Los textos visibles de la pantalla, en orden de lectura. */
    fun texts(root: AccessibilityNodeInfo?): List<String> {
        val out = mutableListOf<String>()
        Nodes.visit(root) { node ->
            listOf(node.text?.toString(), node.contentDescription?.toString())
                .mapNotNull { it?.replace(Regex("\\s+"), " ")?.trim() }
                .filter { it.isNotEmpty() && it != out.lastOrNull() }
                .forEach { out += it }
        }
        return out
    }

    /** Hasta [max] productos (título y precio) de [texts], en el orden en que aparecen. */
    fun extract(texts: List<String>, max: Int): List<Item> {
        val joined = joinCurrency(texts)
        val items = mutableListOf<Item>()
        val usedTitles = mutableSetOf<Int>()
        for ((i, text) in joined.withIndex()) {
            if (items.size >= max) break
            val price = price(text) ?: continue
            // El título: el texto "de producto" más cercano hacia arriba que no se usó.
            val titleIndex = (i - 1 downTo maxOf(0, i - LOOK_BACK))
                .firstOrNull { it !in usedTitles && isTitle(joined[it]) } ?: continue
            if (items.any { it.title == joined[titleIndex] }) continue
            usedTitles += titleIndex
            items += Item(joined[titleIndex], price)
        }
        return items
    }

    /** "$" y "25.999" en textos separados → "$ 25.999". */
    private fun joinCurrency(texts: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            if (t.trim() in CURRENCY && i + 1 < texts.size && NUMBER.matches(texts[i + 1].trim())) {
                out += "${t.trim()} ${texts[i + 1].trim()}"
                i += 2
            } else {
                out += t
                i++
            }
        }
        return out
    }

    /** El precio de un texto, o null si no es un precio actual (las cuotas y el "antes" no cuentan). */
    internal fun price(text: String): String? {
        val plain = plain(text)
        if (Regex("\\b(?:cuotas?|antes|anterior|ahorras|descuento|envio|ahorro)\\b").containsMatchIn(plain)) return null
        PRICE.find(text)?.let { return it.value.replace(Regex("\\s+"), " ").trim() }
        PESOS.find(plain)?.let { m -> return "$ ${m.groupValues[1]}" }
        return null
    }

    private fun isTitle(text: String): Boolean {
        if (text.length < MIN_TITLE || price(text) != null || PRICE.containsMatchIn(text)) return false
        val plain = plain(text)
        if (BADGE.containsMatchIn(plain)) return false
        return plain.count { it.isLetter() } >= MIN_TITLE / 2
    }

    private val CURRENCY = setOf("$", "US$", "U\$S", "ARS", "R$", "S/", "MXN", "CLP", "UYU")
    private val NUMBER = Regex("\\d{1,3}(?:[.\\s]\\d{3})*(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?")
    private val PRICE = Regex("(?:US\\$|U\\\$S|R\\$|\\$|ARS|S/)\\s?\\d{1,3}(?:[.\\s]\\d{3})*(?:,\\d{1,2})?(?!\\d)")
    private val PESOS = Regex("\\b(\\d{1,3}(?:\\.\\d{3})+|\\d{3,})(?: con \\d{1,2} centavos)? pesos\\b")
    private val BADGE = Regex(
        "\\b(?:envio|llega|gratis|cuotas|off|vendido|vendidos|patrocinado|oferta|disponible|full|retiralo|" +
            "mismo precio|antes|descuento|estrellas|opiniones|calificacion|resultados|filtrar|ordenar|" +
            "promocionado|ver mas|agregar|carrito|favorito|compartir)\\b|%"
    )
    private const val MIN_TITLE = 12
    private const val LOOK_BACK = 6

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
