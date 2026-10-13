package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.controlxia.app.permissions.PermissionManager
import java.text.Normalizer
import java.util.Locale

/**
 * Abre apps directamente en una búsqueda o sección con enlaces profundos
 * (deep links): es lo más rápido y confiable, sin tocar la pantalla. Si la app
 * no reconoce el enlace se abre en el navegador; si abre la app pero no la
 * sección, el agente puede seguir con el control de pantalla.
 */
class AppDeepLinks(private val context: Context) {

    /** Apps con búsqueda por enlace. Las claves son las que ve el modelo. */
    val searchApps = listOf(
        "mercadolibre", "youtube", "spotify", "google", "google_maps", "instagram",
        "tiktok", "netflix", "play_store",
    )

    /** Secciones conocidas por app. */
    val sections = mapOf(
        "instagram" to listOf("home", "reels", "inbox", "explore", "profile"),
        "youtube" to listOf("home", "shorts", "subscriptions"),
        "mercadolibre" to listOf("home", "purchases"),
        "google_maps" to listOf("navigate"),
    )

    fun search(app: String, query: String, sort: String?): String {
        if (query.isBlank()) return "Falta qué buscar."
        val q = Uri.encode(query.trim())
        val link = when (app) {
            "mercadolibre" -> Link(
                "https://listado.${mercadoLibreDomain()}/${slug(query)}${mercadoLibreSort(sort)}",
                listOf("com.mercadolibre"),
            )
            "youtube" -> Link("https://www.youtube.com/results?search_query=$q", listOf("com.google.android.youtube"))
            "spotify" -> Link("spotify:search:$q", listOf("com.spotify.music"))
            "google" -> Link("https://www.google.com/search?q=$q", emptyList())
            "google_maps" -> Link("geo:0,0?q=$q", listOf("com.google.android.apps.maps"))
            "instagram" -> Link(
                "https://www.instagram.com/explore/search/keyword/?q=$q",
                listOf("com.instagram.android"),
            )
            "tiktok" -> Link(
                "https://www.tiktok.com/search?q=$q",
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            )
            "netflix" -> Link("https://www.netflix.com/search?q=$q", listOf("com.netflix.mediaclient"))
            "play_store" -> Link("market://search?q=$q", listOf("com.android.vending"))
            else -> return "No sé buscar en «$app». Probá con open_app y el control de pantalla."
        }
        val sorted = if (app == "mercadolibre" && sort in setOf("price_low", "price_high")) {
            if (sort == "price_low") ", ordenado por menor precio" else ", ordenado por mayor precio"
        } else {
            ""
        }
        return launch(link) { appName ->
            "Abrí $appName con la búsqueda «$query»$sorted."
        }
    }

    fun openSection(app: String, section: String, value: String?): String {
        val link = when ("$app/$section") {
            "instagram/home" -> Link("https://www.instagram.com/", listOf("com.instagram.android"))
            "instagram/reels" -> Link("https://www.instagram.com/reels/", listOf("com.instagram.android"))
            "instagram/inbox" -> Link("https://www.instagram.com/direct/inbox/", listOf("com.instagram.android"))
            "instagram/explore" -> Link("https://www.instagram.com/explore/", listOf("com.instagram.android"))
            "instagram/profile" -> {
                val user = value?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    ?: return "Falta el usuario de Instagram."
                Link("https://www.instagram.com/${Uri.encode(user)}/", listOf("com.instagram.android"))
            }
            "youtube/home" -> Link("https://www.youtube.com/", listOf("com.google.android.youtube"))
            "youtube/shorts" -> Link("https://www.youtube.com/shorts", listOf("com.google.android.youtube"))
            "youtube/subscriptions" -> Link(
                "https://www.youtube.com/feed/subscriptions",
                listOf("com.google.android.youtube"),
            )
            "mercadolibre/home" -> Link("https://www.${mercadoLibreDomain()}/", listOf("com.mercadolibre"))
            "mercadolibre/purchases" -> Link(
                "https://myaccount.${mercadoLibreDomain()}/my_purchases/list",
                listOf("com.mercadolibre"),
            )
            "google_maps/navigate" -> {
                val destination = value?.takeIf { it.isNotBlank() } ?: return "Falta el destino."
                Link("google.navigation:q=${Uri.encode(destination)}", listOf("com.google.android.apps.maps"))
            }
            else -> return "No conozco la sección «$section» de «$app». Probá con open_app y el control de pantalla."
        }
        return launch(link) { appName -> "Abrí $appName en «$section»." }
    }

    private class Link(val uri: String, val packages: List<String>)

    /**
     * Abre el enlace en la app indicada (la primera instalada que lo acepte) o,
     * si ninguna lo acepta, en el navegador.
     */
    private fun launch(link: Link, done: (appName: String) -> String): String {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) return "El teléfono está bloqueado: hay que desbloquearlo primero."
        if (!PermissionManager.canLaunchActivities(context)) {
            return "Para abrir apps necesito el permiso Mostrar sobre otras apps. Activalo en ControlXIA."
        }
        val uri = Uri.parse(link.uri)
        for (pkg in link.packages) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                context.startActivity(intent)
                return done(appLabel(pkg))
            } catch (e: ActivityNotFoundException) {
                // La app no está instalada o no acepta el enlace: probar la siguiente.
            } catch (e: SecurityException) {
                // Idem.
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            done("el navegador")
        } catch (e: Exception) {
            "No encontré cómo abrir eso en este teléfono."
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** "Zapatillas Nike Air" → "zapatillas-nike-air" (formato de URL de Mercado Libre). */
    private fun slug(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ]+"), "-")
            .trim('-')

    private fun mercadoLibreSort(sort: String?): String = when (sort) {
        "price_low" -> "_OrderId_PRICE_NoIndex_True"
        "price_high" -> "_OrderId_PRICE*DESC_NoIndex_True"
        else -> ""
    }

    /** Dominio de Mercado Libre según el país del chip (Argentina por defecto). */
    private fun mercadoLibreDomain(): String = when (countryIso()) {
        "mx" -> "mercadolibre.com.mx"
        "co" -> "mercadolibre.com.co"
        "cl" -> "mercadolibre.cl"
        "uy" -> "mercadolibre.com.uy"
        "pe" -> "mercadolibre.com.pe"
        "br" -> "mercadolivre.com.br"
        else -> "mercadolibre.com.ar"
    }

    private fun countryIso(): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.lowercase(Locale.ROOT) }
            .firstOrNull { it.isNotBlank() } ?: "ar"
    }
}
