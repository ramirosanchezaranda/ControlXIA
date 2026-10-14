package com.controlxia.app.brain

import com.controlxia.app.brain.LocalSkills.Tool
import com.controlxia.app.brain.LocalSkills.ReadMessages
import com.controlxia.app.brain.LocalSkills.ReplyLast
import com.controlxia.app.brain.LocalSkills.JustTheName
import com.controlxia.app.brain.LocalSkills.Repeat
import com.controlxia.app.brain.LocalSkills.Answer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Lo que se resuelve sin modelo con datos: alarmas, mensajes, búsquedas, cuentas, agenda… */
class LocalSkillsTest {

    @Test
    fun parsesSkills() {
        val cases: List<Pair<String, Any?>> = listOf(
            "¿qué hora es?" to Tool("get_time", emptyMap()), "qué día es hoy" to Tool("get_date", emptyMap()),
            "poneme una alarma a las 7" to Tool("set_alarm", mapOf("hour" to 7, "minute" to 0)),
            "poné una alarma a las siete y media de la tarde" to Tool("set_alarm", mapOf("hour" to 19, "minute" to 30)),
            "alarma a las 8 menos cuarto" to Tool("set_alarm", mapOf("hour" to 7, "minute" to 45)),
            "despertame mañana a las 6 y cuarto" to Tool("set_alarm", mapOf("hour" to 6, "minute" to 15)),
            "alarma a las 11 de la noche" to Tool("set_alarm", mapOf("hour" to 23, "minute" to 0)),
            "poné un temporizador de 5 minutos" to Tool("set_timer", mapOf("seconds" to 300)),
            "temporizador de media hora" to Tool("set_timer", mapOf("seconds" to 1800)),
            "avisame en 1 hora y 10 minutos" to Tool("set_timer", mapOf("seconds" to 4200)),
            "temporizador de 30 segundos" to Tool("set_timer", mapOf("seconds" to 30)),
            "mandale a Juan que llego tarde" to Tool("send_message", mapOf("contact" to "juan", "text" to "Llego tarde", "app" to "whatsapp")),
            "Beto, mandale un mensaje a mi pareja diciendo que la quiero" to Tool("send_message", mapOf("contact" to "mi pareja", "text" to "La quiero", "app" to "whatsapp")),
            "decile a Rebeca que estos mensajes los mandé con la app" to Tool("send_message", mapOf("contact" to "rebeca", "text" to "Estos mensajes los mandé con la app", "app" to "whatsapp")),
            "mandale un sms a mamá que ya salgo" to Tool("send_message", mapOf("contact" to "mama", "text" to "Ya salgo", "app" to "sms")),
            "mandale al grupo fútbol 2 que hoy no voy" to Tool("send_message", mapOf("contact" to "grupo futbol 2", "text" to "Hoy no voy", "app" to "whatsapp")),
            "preguntale a Juan si viene a cenar" to Tool("send_message", mapOf("contact" to "juan", "text" to "¿Viene a cenar?", "app" to "whatsapp")),
            "mandale un audio a Rebeca" to Tool("send_audio", mapOf("contact" to "rebeca")),
            "mandale un audio a Juan que diga feliz cumpleaños" to Tool("send_audio", mapOf("contact" to "juan", "text" to "Feliz cumpleaños")),
            "respondele que sí" to ReplyLast("Sí"), "contestale a Juan que en 10 llego" to ReplyLast("En 10 llego", "juan"),
            "leeme los mensajes" to ReadMessages(), "leeme los mensajes de Juan" to ReadMessages(from = "juan"),
            "leeme los mensajes de whatsapp" to ReadMessages(app = "whatsapp"), "tengo mensajes nuevos" to ReadMessages(),
            "buscá zapatillas nike en mercado libre" to Tool("search_in_app", mapOf("app" to "mercadolibre", "query" to "zapatillas nike")),
            "buscá las zapatillas nike más baratas en mercado libre" to Tool("search_in_app", mapOf("app" to "mercadolibre", "query" to "zapatillas nike", "sort" to "price_low")),
            "buscá en youtube recetas de pizza" to Tool("search_in_app", mapOf("app" to "youtube", "query" to "recetas de pizza")),
            "poné música de Queen en spotify" to Tool("search_in_app", mapOf("app" to "spotify", "query" to "musica de queen")),
            "buscá el clima en Buenos Aires" to Tool("app_search", mapOf("app" to "buenos aires", "query" to "clima")),
            "buscá el clima" to Tool("search_in_app", mapOf("app" to "google", "query" to "clima")),
            "buscá pizza en PedidosYa" to Tool("app_search", mapOf("app" to "pedidosya", "query" to "pizza")),
            "buscá pizza en pedidos ya" to Tool("app_search", mapOf("app" to "pedidos ya", "query" to "pizza")),
            "buscá vuelos a Bariloche en Despegar" to Tool("app_search", mapOf("app" to "despegar", "query" to "vuelos a bariloche")),
            "buscame a Juan en la app de Telegram" to Tool("app_search", mapOf("app" to "telegram", "query" to "juan")),
            "en Spotify tocá Biblioteca" to Tool("app_tap", mapOf("app" to "spotify", "label" to "Biblioteca")),
            "abrí Spotify y tocá Tu biblioteca" to Tool("app_tap", mapOf("app" to "spotify", "label" to "Tu biblioteca")),
            "en YouTube andá a Suscripciones" to Tool("app_tap", mapOf("app" to "youtube", "label" to "Suscripciones")),
            "leeme las notificaciones" to Tool("notifications", mapOf("action" to "read")),
            "Beto leeme las notificaciones de WhatsApp" to Tool("notifications", mapOf("action" to "read", "filter" to "whatsapp")),
            "qué notificaciones tengo" to Tool("notifications", mapOf("action" to "read")),
            "tengo notificaciones nuevas" to Tool("notifications", mapOf("action" to "read")),
            "tengo alguna notificación del banco" to Tool("notifications", mapOf("action" to "read", "filter" to "banco")),
            "decime las notificaciones" to Tool("notifications", mapOf("action" to "read")),
            "borrá las notificaciones" to Tool("notifications", mapOf("action" to "clear")),
            "limpiá todas las notificaciones" to Tool("notifications", mapOf("action" to "clear")),
            "abrí la notificación de Gmail" to Tool("notifications", mapOf("action" to "open", "filter" to "gmail")),
            "abrí la última notificación" to Tool("notifications", mapOf("action" to "open")),
            "mandale a Juan por Telegram que llego tarde" to Tool("send_message", mapOf("contact" to "juan", "text" to "Llego tarde", "app" to "telegram")),
            "mandale a Juan que llego tarde por Telegram" to Tool("send_message", mapOf("contact" to "juan", "text" to "Llego tarde", "app" to "telegram")),
            "decile a Ana por instagram que ya voy" to Tool("send_message", mapOf("contact" to "ana", "text" to "Ya voy", "app" to "instagram")),
            "mandale a Juan que llego tarde por la noche" to Tool("send_message", mapOf("contact" to "juan", "text" to "Llego tarde por la noche", "app" to "whatsapp")),
            "mandale un mensaje a Pedro por messenger diciendo que mañana no puedo" to Tool("send_message", mapOf("contact" to "pedro", "text" to "Mañana no puedo", "app" to "messenger")),
            "abrí los reels" to Tool("open_app_section", mapOf("app" to "instagram", "section" to "reels")),
            "mostrame mis compras" to Tool("open_app_section", mapOf("app" to "mercadolibre", "section" to "purchases")),
            "llevame a casa" to Tool("open_app_section", mapOf("app" to "google_maps", "section" to "navigate", "value" to "casa")),
            "abrí el chat de Juan" to Tool("whatsapp_open_chat", mapOf("name" to "juan")),
            "abrí el grupo fútbol 2" to Tool("whatsapp_open_chat", mapOf("name" to "futbol 2")),
            "mi pareja es Lucía" to Tool("save_contact_alias", mapOf("alias" to "pareja", "contact" to "Lucía")),
            "chevetó" to JustTheName, "Beto" to JustTheName, "che beto" to JustTheName, "repetí" to Repeat,
            "cuánto es 25 por 4" to Answer("Da 100."), "1.500 dividido 3" to Answer("Da 500."),
            "cuánto es el 15 por ciento de 2000" to Answer("Da 300."), "raíz de 81" to Answer("Da 9."),
            "7 al cuadrado" to Answer("Da 49."), "cuánto es 10 dividido 4" to Answer("Da 2,5."), "cuánto es dos más dos" to Answer("Da 4."),
            "cuánto es 1000 por 1500" to Answer("Da 1.500.000."),
            "agendá dentista el jueves a las 4 de la tarde" to Tool("calendar_event", mapOf("title" to "Dentista", "date" to "2026-10-15", "time" to "16:00")),
            "recordame comprar pan mañana a las 10" to Tool("calendar_event", mapOf("title" to "Comprar pan", "date" to "2026-10-14", "time" to "10:00")),
            "anotá reunión con Juan el 25 de octubre a las 9" to Tool("calendar_event", mapOf("title" to "Reunión con Juan", "date" to "2026-10-25", "time" to "09:00")),
            // Al modelo:
            "recordame llamar a mamá" to null, "5" to null,
            "en el último video que estaba viendo" to null, "entonces no funciona más" to null, "el internet" to null,
            "buscá el chat de Juan" to null, "abrí instagram y mirá los reels" to null,
        )
        val today = LocalDate.of(2026, 10, 13)
        val wrong = cases.mapNotNull { (text, want) ->
            val got = LocalSkills.parse(text, "Beto", today)
            if (got == want) null else "«$text» → $got (esperaba $want)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}
