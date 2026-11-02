package app.brisa.browser

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.util.TimeZone
import kotlin.random.Random

/**
 * A believable made-up browsing history, shown instead of the real one while "hide real history" is on.
 * It is generated from a private random seed and the calendar, so it looks the same each time it is opened
 * during the day and grows through the day like a real one. The real history is never touched.
 */
object DecoyHistory {
    private const val DAY = 86_400_000L
    private const val DAYS = 14
    private const val MAX = 300
    private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    fun enabled(context: Context) = context.defaultPrefs().getBoolean(Prefs.HISTORY_DECOY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.defaultPrefs().edit {
            putBoolean(Prefs.HISTORY_DECOY, on)
            if (on) putBoolean(Prefs.DECOY_CLEARED, false)
        }
    }

    fun clear(context: Context) = context.defaultPrefs().edit { putBoolean(Prefs.DECOY_CLEARED, true) }

    fun remove(context: Context, url: String) {
        val prefs = context.defaultPrefs()
        val removed = (prefs.getStringSet(Prefs.DECOY_REMOVED, null) ?: emptySet()).toMutableSet()
        removed += url
        prefs.edit { putStringSet(Prefs.DECOY_REMOVED, removed) }
    }

    private fun seed(context: Context): Long {
        val prefs = context.defaultPrefs()
        var seed = prefs.getLong(Prefs.DECOY_SEED, 0L)
        if (seed == 0L) {
            seed = Random.nextLong() or 1L
            prefs.edit { putLong(Prefs.DECOY_SEED, seed) }
        }
        return seed
    }

    fun entries(context: Context): List<History.Entry> {
        val prefs = context.defaultPrefs()
        if (prefs.getBoolean(Prefs.DECOY_CLEARED, false)) return emptyList()
        val removed = prefs.getStringSet(Prefs.DECOY_REMOVED, null) ?: emptySet()
        val engine = SearchEngines.current(prefs)
        val seed = seed(context)
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now)
        val today = (now + offset) / DAY
        val out = ArrayList<History.Entry>()
        for (back in 0 until DAYS) {
            val dayIndex = today - back
            val dayStart = dayIndex * DAY - offset
            val rnd = Random(seed + dayIndex * 7919L)
            val weekday = ((dayIndex + 4) % 7).toInt() // 0 = Sunday
            val weekend = weekday == 0 || weekday == 6
            val sessions = if (weekend) rnd.nextInt(3, 6) else rnd.nextInt(2, 5)
            repeat(sessions) {
                val (startHour, endHour) = HOURS[rnd.nextInt(HOURS.size)]
                var time = dayStart + rnd.nextInt(startHour, endHour) * 3_600_000L + rnd.nextInt(0, 60) * 60_000L
                repeat(rnd.nextInt(3, 11)) {
                    val page = page(rnd, engine)
                    if (time <= now && page.second !in removed) out += History.Entry(page.first, page.second, time)
                    time += rnd.nextLong(20_000L, 240_000L)
                }
            }
        }
        return out.sortedByDescending { it.time }.distinctBy { it.url }.take(MAX)
    }

    private val HOURS = listOf(7 to 9, 12 to 14, 13 to 15, 17 to 19, 19 to 21, 21 to 23, 22 to 24)

    private fun page(rnd: Random, engine: SearchEngines.Engine): Pair<String, String> {
        val roll = rnd.nextInt(100)
        return when {
            roll < 30 -> {
                val q = SEARCHES[rnd.nextInt(SEARCHES.size)]
                val title = if (engine.id == "google") "$q - Buscar con Google" else "$q - ${engine.name}"
                title to engine.url(q)
            }
            roll < 50 -> {
                val id = String(CharArray(11) { ID_CHARS[rnd.nextInt(ID_CHARS.length)] })
                "${VIDEOS[rnd.nextInt(VIDEOS.size)]} - YouTube" to "https://www.youtube.com/watch?v=$id"
            }
            roll < 62 -> {
                val article = ARTICLES[rnd.nextInt(ARTICLES.size)]
                "$article - Wikipedia, la enciclopedia libre" to
                    "https://es.wikipedia.org/wiki/" + Uri.encode(article.replace(' ', '_'))
            }
            roll < 72 -> {
                val q = PRODUCTS[rnd.nextInt(PRODUCTS.size)]
                "Amazon.es : $q" to "https://www.amazon.es/s?k=" + Uri.encode(q)
            }
            roll < 78 -> {
                val q = PLACES[rnd.nextInt(PLACES.size)]
                "$q - Google Maps" to "https://www.google.com/maps/search/" + Uri.encode(q)
            }
            else -> SITES[rnd.nextInt(SITES.size)]
        }
    }

    private val SEARCHES = listOf(
        "clima mañana", "receta de arroz con pollo", "traductor inglés español", "horario supermercado domingo",
        "cómo quitar manchas de la ropa", "mejores series para ver", "vuelos baratos", "resultados de ayer",
        "qué hacer este fin de semana", "tutorial excel tablas dinámicas", "cómo hacer pan en casa",
        "ofertas en electrónica", "precio del dólar hoy", "película estreno cine", "cómo cambiar una rueda",
        "calorías de una manzana", "ejercicios para la espalda", "mejor móvil calidad precio",
        "cómo se escribe", "farmacia de guardia", "recetas fáciles para cenar", "significado de palabras",
        "canciones de moda", "cómo plantar tomates", "mapa de carreteras", "consejos para dormir mejor",
    )

    private val VIDEOS = listOf(
        "Música para concentrarse y estudiar", "Resumen del partido | Mejores momentos", "Receta fácil en 10 minutos",
        "Tutorial paso a paso para principiantes", "Documental: la vida en los océanos", "Rutina de ejercicio en casa",
        "Mix de canciones para el coche", "Cómo arreglar el wifi lento", "Entrevista completa", "Los mejores trucos del mes",
    )

    private val ARTICLES = listOf(
        "Sistema solar", "Revolución industrial", "Fotosíntesis", "Segunda Guerra Mundial", "Teorema de Pitágoras",
        "Cambio climático", "Imperio romano", "Inteligencia artificial", "Océano Pacífico", "Renacimiento",
        "Sistema inmunitario", "Ajedrez", "Café", "Pizza", "Volcán", "Fútbol", "Internet", "Mitocondria",
    )

    private val PRODUCTS = listOf(
        "auriculares inalámbricos", "funda para móvil", "cargador usb c", "zapatillas running", "mochila",
        "lámpara de escritorio", "cable hdmi", "botella térmica",
    )

    private val PLACES = listOf("farmacia cerca", "gasolinera", "cafetería", "supermercado", "restaurante", "cajero automático")

    private val SITES = listOf(
        "Bandeja de entrada - Gmail" to "https://mail.google.com/mail/u/0/#inbox",
        "WhatsApp" to "https://web.whatsapp.com/",
        "YouTube" to "https://www.youtube.com/",
        "Traductor de Google" to "https://translate.google.com/",
        "Netflix" to "https://www.netflix.com/browse",
        "Wikipedia, la enciclopedia libre" to "https://es.wikipedia.org/wiki/Wikipedia:Portada",
        "BBC News Mundo" to "https://www.bbc.com/mundo",
        "EL PAÍS: el periódico global" to "https://elpais.com/",
        "Amazon.es: compra online de electrónica, libros, deporte, hogar, moda y mucho más" to "https://www.amazon.es/",
    )
}

/** What the History screen and address bar suggestions see: the decoy while it is on, the real list otherwise. */
object HistoryView {
    fun all(context: Context): List<History.Entry> =
        if (DecoyHistory.enabled(context)) DecoyHistory.entries(context) else History.all(context)

    fun remove(context: Context, url: String) {
        if (DecoyHistory.enabled(context)) DecoyHistory.remove(context, url) else History.remove(context, url)
    }

    fun clear(context: Context) {
        if (DecoyHistory.enabled(context)) DecoyHistory.clear(context) else History.clear(context)
    }
}
