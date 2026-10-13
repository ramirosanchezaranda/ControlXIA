package com.controlxia.app.actions

import android.content.Context
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.controlxia.app.permissions.PermissionManager
import java.text.Normalizer
import java.util.Locale

/**
 * Busca contactos por nombre o por apodo ("mi pareja", "mamá"). Los apodos los
 * enseña el usuario por voz ("mi pareja es Lucía") y se guardan en el teléfono.
 */
class Contacts(private val context: Context) {

    data class Contact(val name: String, val phone: String)

    sealed interface Lookup {
        data class Found(val contact: Contact) : Lookup
        data class Ambiguous(val names: List<String>) : Lookup
        data class NotFound(val reason: String) : Lookup
    }

    private val aliases = context.applicationContext
        .getSharedPreferences("contact_aliases", Context.MODE_PRIVATE)

    fun saveAlias(alias: String, contactName: String): String {
        val key = normalize(alias.removePrefix("mi ").removePrefix("mí "))
        if (key.isBlank() || contactName.isBlank()) return "Falta el apodo o el contacto."
        val found = find(contactName)
        if (found is Lookup.NotFound) return found.reason
        if (found is Lookup.Ambiguous) return "Hay varios contactos: ${found.names.joinToString(", ")}. ¿Cuál?"
        val name = (found as Lookup.Found).contact.name
        aliases.edit().putString(key, name).apply()
        return "Listo, «$alias» es $name."
    }

    /** Resuelve un nombre o apodo a un contacto con teléfono. */
    fun find(nameOrAlias: String): Lookup {
        if (!PermissionManager.hasContacts(context)) {
            return Lookup.NotFound("Necesito permiso para ver los contactos: activalo en ControlXIA.")
        }
        val alias = normalize(nameOrAlias.removePrefix("mi ").removePrefix("mí "))
        val query = normalize(aliases.getString(alias, null) ?: nameOrAlias)
        if (query.isBlank()) return Lookup.NotFound("No entendí a quién.")

        val all = allWithPhone()
        val exact = all.filter { normalize(it.name) == query }
        val partial = exact.ifEmpty {
            all.filter { contact ->
                val name = normalize(contact.name)
                name.contains(query) || query.split(" ").all { it.length > 1 && name.contains(it) }
            }
        }
        val byName = partial.groupBy { it.name }
        return when {
            byName.isEmpty() -> Lookup.NotFound("No encontré a «$nameOrAlias» en los contactos.")
            byName.size > 1 -> Lookup.Ambiguous(byName.keys.take(4))
            else -> Lookup.Found(pickMobile(byName.values.first()))
        }
    }

    /** Número en formato internacional sin "+" (para WhatsApp), o null si no se puede armar. */
    fun international(phone: String): String? {
        val raw = phone.filter { it.isDigit() || it == '+' }
        if (raw.startsWith("+")) return raw.filter(Char::isDigit)
        if (raw.startsWith("00")) return raw.drop(2)
        val country = countryIso()
        val code = COUNTRY_CODES[country] ?: return null
        var local = raw.trimStart('0')
        if (country == "ar") {
            // Celulares argentinos: 54 9 + característica + número, sin el 15.
            if (local.startsWith("54")) local = local.drop(2).removePrefix("9")
            if (local.length == 12) {
                val cut = (2..4).firstOrNull { local.substring(it, it + 2) == "15" }
                if (cut != null) local = local.removeRange(cut, cut + 2)
            }
            return if (local.length == 10) "549$local" else null
        }
        return code + local
    }

    private fun allWithPhone(): List<Contact> {
        val result = mutableListOf<Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                val type = cursor.getInt(2)
                // Los móviles primero dentro de cada contacto.
                val contact = Contact(name, number)
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) result.add(0, contact)
                else result += contact
            }
        }
        return result
    }

    private fun pickMobile(numbers: List<Contact>): Contact = numbers.first()

    private fun countryIso(): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.lowercase(Locale.ROOT) }
            .firstOrNull { it.isNotBlank() } ?: "ar"
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    private companion object {
        val COUNTRY_CODES = mapOf(
            "ar" to "54", "uy" to "598", "cl" to "56", "py" to "595", "bo" to "591", "pe" to "51",
            "co" to "57", "ec" to "593", "ve" to "58", "mx" to "52", "es" to "34", "us" to "1",
            "br" to "55",
        )
    }
}
