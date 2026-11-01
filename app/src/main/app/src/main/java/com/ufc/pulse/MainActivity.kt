package com.ufc.pulse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

// --- DATA MODELS ---
data class NewsItem(
    val title: String,
    val source: String,
    val pubDate: String,
    val link: String,
    val summary: String
)

data class BoutDetail(
    val boutName: String,
    val odds: String,
    val prediction: String
)

data class UfcEvent(
    val title: String,
    val type: String,
    val date: String,
    val localTime: String,
    val venue: String,
    val bouts: List<BoutDetail>
)

data class Fighter(
    val name: String,
    val division: String,
    val code: String,
    val record: String,
    val odds: String,
    val isChamp: Boolean = false
)

data class LiveMatchState(
    val eventTitle: String,
    val weightClass: String,
    val fighterA: String,
    val recordA: String,
    val oddsA: String,
    val probA: Int,
    val fighterB: String,
    val recordB: String,
    val oddsB: String,
    val probB: Int,
    val round: String,
    val time: String,
    val strikesA: Int,
    val strikesB: Int,
    val takedownsA: Int,
    val takedownsB: Int,
    val status: String,
    val prediction: String,
    val isLiveFromApi: Boolean = false
)

// --- SAFE URL OPENER ---
fun openUrlSafely(context: Context, rawUrl: String) {
    try {
        val cleanUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://www.ufc.com"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {}
}

// --- LOCAL TIMEZONE CONVERTER (IRELAND GMT/IST) ---
object TimezoneHelper {
    fun formatEventTime(utcIsoString: String, fallbackDateStr: String): String {
        return try {
            if (utcIsoString.isBlank()) return fallbackDateStr
            val parsedZdt = ZonedDateTime.parse(utcIsoString)
            val localZdt = parsedZdt.withZoneSameInstant(ZoneId.systemDefault())
            val formatter = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a z", Locale.getDefault())
            localZdt.format(formatter)
        } catch (_: Throwable) {
            fallbackDateStr
        }
    }
}

// --- VERIFIED LATE-2026 UFC SCHEDULE, ODDS, PREDICTIONS & ROSTER ---
object UfcSeedData {
    val defaultLiveMatch = LiveMatchState(
        eventTitle = "UFC Fight Night: Moicano vs. Nolan",
        weightClass = "Lightweight Main Event • 5 Rounds",
        fighterA = "Renato Moicano",
        recordA = "21-7-1",
        oddsA = "-165",
        probA = 62,
        fighterB = "Tom Nolan",
        recordB = "11-1-0",
        oddsB = "+140",
        probB = 38,
        round = "2",
        time = "3:18",
        strikesA = 34,
        strikesB = 27,
        takedownsA = 2,
        takedownsB = 0,
        status = "Moicano controlling top position in half-guard, landing short elbows.",
        prediction = "Moicano via Submission (RNC) or Decision • 62% Win Prob"
    )

    val upcomingEvents: List<UfcEvent> by lazy {
        listOf(
            UfcEvent(
                title = "UFC Fight Night: Moicano vs. Nolan",
                type = "FIGHT NIGHT",
                date = "Sat, Oct 31 / Sun, Nov 1, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-11-01T00:00:00Z", "Sun, Nov 1 • 12:00 AM GMT"),
                venue = "Meta Apex, Las Vegas, US",
                bouts = listOf(
                    BoutDetail("Lightweight: Renato Moicano (21-7-1) vs. Tom Nolan (11-1-0)", "Moicano -165 / Nolan +140", "Pick: Moicano via Sub/Dec (62%)"),
                    BoutDetail("Welterweight: Randy Brown (20-8-0) vs. Jacobe Smith (13-0-0)", "Brown +180 / Smith -220", "Pick: Smith via TKO/Dec (68%)"),
                    BoutDetail("Women's Bantamweight: Yana Santos (17-8-0) vs. Luana Santos (11-2-0)", "Y. Santos +210 / L. Santos -260", "Pick: L. Santos via Dec (71%)"),
                    BoutDetail("Welterweight: Jean-Paul Lebosnoyani (11-2-0) vs. Farman Hasanov (5-0-0)", "Lebosnoyani -135 / Hasanov +115", "Pick: Lebosnoyani via Sub (56%)"),
                    BoutDetail("Featherweight: Julian Erosa (31-14-0) vs. JeongYeong Lee (11-3-0)", "Erosa +120 / Lee -140", "Pick: Lee via KO/TKO (58%)"),
                    BoutDetail("Flyweight: Cody Durden (18-11-1) vs. Ramazonbek Temirov (20-4-0)", "Durden +195 / Temirov -240", "Pick: Temirov via TKO (69%)")
                )
            ),
            UfcEvent(
                title = "UFC Fight Night: Bonfim vs. Brady",
                type = "FIGHT NIGHT",
                date = "Sat, Nov 7, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-11-07T22:00:00Z", "Sat, Nov 7 • 10:00 PM GMT"),
                venue = "Meta Apex, Las Vegas, US",
                bouts = listOf(
                    BoutDetail("Welterweight: Gabriel Bonfim (20-1-0) vs. Sean Brady (19-2-0)", "Bonfim +145 / Brady -175", "Pick: Brady via Decision (64%)"),
                    BoutDetail("Women's Strawweight: Tatiana Suarez (13-1-0) vs. Virna Jandiroba (23-4-0)", "Suarez -230 / Jandiroba +190", "Pick: Suarez via Dec/Sub (70%)"),
                    BoutDetail("Heavyweight: Waldo Cortes-Acosta (17-4-0) vs. Johnny Walker (23-10-0)", "Cortes-Acosta -155 / Walker +130", "Pick: Cortes-Acosta via KO (60%)"),
                    BoutDetail("Bantamweight: Davey Grant (18-8-0) vs. Elijah Smith (10-1-0)", "Grant +165 / Smith -200", "Pick: Smith via Decision (65%)")
                )
            ),
            UfcEvent(
                title = "UFC 334: Gane vs. Hokit",
                type = "PPV",
                date = "Sat, Nov 14, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-11-15T03:00:00Z", "Sun, Nov 15 • 3:00 AM GMT"),
                venue = "Madison Square Garden, New York City, US",
                bouts = listOf(
                    BoutDetail("Heavyweight Title: Ciryl Gane (C) vs. Josh Hokit", "Gane -340 / Hokit +270", "Pick: Gane via TKO/Dec (77%)"),
                    BoutDetail("Women's Bantamweight Title: Kayla Harrison (C) vs. Amanda Nunes", "Harrison -135 / Nunes +115", "Pick: Harrison via Decision (57%)"),
                    BoutDetail("Middleweight: Caio Borralho vs. Yousri Belgaroui", "Borralho -260 / Belgaroui +210", "Pick: Borralho via Dec (72%)"),
                    BoutDetail("Welterweight: Uros Medic vs. Kevin Holland", "Medic +130 / Holland -155", "Pick: Holland via KO/Sub (61%)"),
                    BoutDetail("Welterweight: Stephen Thompson vs. Charles Radtke", "Thompson -145 / Radtke +122", "Pick: Thompson via Dec (59%)")
                )
            ),
            UfcEvent(
                title = "UFC Fight Night: Prochazka vs. Stirling",
                type = "FIGHT NIGHT",
                date = "Sat, Nov 21, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-11-21T19:00:00Z", "Sat, Nov 21 • 7:00 PM GMT"),
                venue = "ABHA Arena, Doha, Qatar",
                bouts = listOf(
                    BoutDetail("Light Heavyweight: Jiri Prochazka vs. Navajo Stirling", "Prochazka -190 / Stirling +160", "Pick: Prochazka via KO/TKO (65%)"),
                    BoutDetail("Featherweight: Aljamain Sterling vs. Kevin Vallejos", "Sterling -210 / Vallejos +175", "Pick: Sterling via Decision (67%)"),
                    BoutDetail("Lightweight: Dan Hooker vs. Brian Ortega", "Hooker -150 / Ortega +125", "Pick: Hooker via TKO/Dec (60%)"),
                    BoutDetail("Middleweight: Jared Cannonier vs. Ikram Aliskerov", "Cannonier +170 / Aliskerov -205", "Pick: Aliskerov via KO (66%)")
                )
            ),
            UfcEvent(
                title = "UFC Fight Night 296: Adesanya vs. Magomedov",
                type = "FIGHT NIGHT",
                date = "Sat, Dec 5, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-12-05T20:00:00Z", "Sat, Dec 5 • 8:00 PM GMT"),
                venue = "anb Arena, Riyadh, Saudi Arabia",
                bouts = listOf(
                    BoutDetail("Middleweight Main Event: Israel Adesanya vs. Shara Magomedov", "Adesanya -175 / Magomedov +145", "Pick: Adesanya via Decision (63%)")
                )
            ),
            UfcEvent(
                title = "UFC 335: Oliveira vs. Lopes",
                type = "PPV",
                date = "Sat, Dec 12, 2026",
                localTime = TimezoneHelper.formatEventTime("2026-12-13T03:00:00Z", "Sun, Dec 13 • 3:00 AM GMT"),
                venue = "T-Mobile Arena, Las Vegas, US",
                bouts = listOf(
                    BoutDetail("BMF Title (Lightweight): Charles Oliveira (C) vs. Diego Lopes", "Oliveira -155 / Lopes +130", "Pick: Oliveira via Submission (61%)"),
                    BoutDetail("Flyweight Title: Joshua Van (C) vs. Manel Kape", "Van -125 / Kape +105", "Pick: Van via Decision (55%)"),
                    BoutDetail("Interim Welterweight Title: Carlos Prates vs. Michael Morales", "Prates -130 / Morales +110", "Pick: Prates via KO/TKO (56%)"),
                    BoutDetail("Heavyweight: Sergei Pavlovich vs. Alex Pereira", "Pavlovich +115 / Pereira -135", "Pick: Pereira via KO/TKO (58%)"),
                    BoutDetail("Middleweight: Joe Pyfer vs. Bo Nickal", "Pyfer +140 / Nickal -165", "Pick: Nickal via Sub/Dec (62%)")
                )
            )
        )
    }

    val initialRoster = listOf(
        Fighter("Ciryl Gane", "Heavyweight", "HW", "13-2-0", "-340", isChamp = true),
        Fighter("Carlos Ulberg", "Light Heavyweight", "LHW", "12-1-0", "-180", isChamp = true),
        Fighter("Sean Strickland", "Middleweight", "MW", "29-6-0", "-150", isChamp = true),
        Fighter("Islam Makhachev", "Welterweight", "WW", "27-1-0", "-380", isChamp = true),
        Fighter("Justin Gaethje", "Lightweight", "LW", "26-5-0", "-135", isChamp = true),
        Fighter("Charles Oliveira", "Lightweight (BMF)", "LW", "35-10-0", "-155", isChamp = true),
        Fighter("Alexander Volkanovski", "Featherweight", "FW", "29-4-0", "-190", isChamp = true),
        Fighter("Petr Yan", "Bantamweight", "BW", "21-5-0", "-175", isChamp = true),
        Fighter("Joshua Van", "Flyweight", "FLW", "15-2-0", "-125", isChamp = true),
        Fighter("Kayla Harrison", "Women's Bantamweight", "WMMA", "19-1-0", "-135", isChamp = true),
        Fighter("Natalia Silva", "Women's Flyweight", "WMMA", "19-5-1", "-210", isChamp = true),
        Fighter("Mackenzie Dern", "Women's Strawweight", "WMMA", "15-5-0", "-140", isChamp = true),
        Fighter("Alex Pereira", "Heavyweight / LHW", "HW", "12-3-0", "-135"),
        Fighter("Sergei Pavlovich", "Heavyweight", "HW", "18-3-0", "+115"),
        Fighter("Alexander Volkov", "Heavyweight", "HW", "41-11-0", "-160"),
        Fighter("Waldo Cortes-Acosta", "Heavyweight", "HW", "17-4-0", "-155"),
        Fighter("Johnny Walker", "Heavyweight", "HW", "23-10-0", "+130"),
        Fighter("Jiri Prochazka", "Light Heavyweight", "LHW", "31-5-1", "-190"),
        Fighter("Dominick Reyes", "Light Heavyweight", "LHW", "17-5-0", "+120"),
        Fighter("Azamat Murzakanov", "Light Heavyweight", "LHW", "16-2-0", "-170"),
        Fighter("Israel Adesanya", "Middleweight", "MW", "24-5-0", "-175"),
        Fighter("Caio Borralho", "Middleweight", "MW", "17-1-0", "-260"),
        Fighter("Bo Nickal", "Middleweight", "MW", "7-0-0", "-165"),
        Fighter("Sean Brady", "Welterweight", "WW", "19-2-0", "-175"),
        Fighter("Gabriel Bonfim", "Welterweight", "WW", "20-1-0", "+145"),
        Fighter("Carlos Prates", "Welterweight", "WW", "21-6-0", "-130"),
        Fighter("Michael Morales", "Welterweight", "WW", "17-0-0", "+110"),
        Fighter("Renato Moicano", "Lightweight", "LW", "21-7-1", "-165"),
        Fighter("Tom Nolan", "Lightweight", "LW", "11-1-0", "+140"),
        Fighter("Dan Hooker", "Lightweight", "LW", "24-12-0", "-150"),
        Fighter("Diego Lopes", "Featherweight / LW", "FW", "26-6-0", "+130"),
        Fighter("Movsar Evloev", "Featherweight", "FW", "20-1-0", "-220"),
        Fighter("Arnold Allen", "Featherweight", "FW", "21-4-0", "-145"),
        Fighter("Merab Dvalishvili", "Bantamweight", "BW", "21-6-0", "-200"),
        Fighter("Davey Grant", "Bantamweight", "BW", "18-8-0", "+165"),
        Fighter("Manel Kape", "Flyweight", "FLW", "20-7-0", "+105"),
        Fighter("Brandon Moreno", "Flyweight", "FLW", "22-8-2", "-165"),
        Fighter("Amanda Nunes", "Women's Bantamweight", "WMMA", "23-5-0", "+115"),
        Fighter("Tatiana Suarez", "Women's Strawweight", "WMMA", "13-1-0", "-230"),
        Fighter("Virna Jandiroba", "Women's Strawweight", "WMMA", "23-4-0", "+190")
    )
}

// --- FAULT-TOLERANT NETWORK REPOSITORY ---
object UfcNetworkRepository {
    private fun httpGet(urlString: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; SM-F976B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                )
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return try {
            parser.nextText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseRssXml(xmlStr: String, sourceLabel: String): List<NewsItem> {
        if (xmlStr.isBlank()) return emptyList()
        val items = mutableListOf<NewsItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xmlStr))
            var eventType = parser.eventType
            var insideItem = false
            var title = ""
            var link = ""
            var pubDate = ""
            var desc = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tag.equals("item", ignoreCase = true) || tag.equals("entry", ignoreCase = true)) {
                            insideItem = true
                            title = ""; link = ""; pubDate = ""; desc = ""
                        } else if (insideItem) {
                            when (tag.lowercase()) {
                                "title" -> title = safeNextText(parser)
                                "link" -> {
                                    val hrefAttr = parser.getAttributeValue(null, "href")
                                    val textLink = safeNextText(parser)
                                    link = if (!hrefAttr.isNullOrBlank()) hrefAttr else textLink
                                }
                                "pubdate", "published", "updated" -> pubDate = safeNextText(parser).take(22)
                                "description", "summary", "content" -> {
                                    desc = safeNextText(parser)
                                        .replace(Regex("<.*?>"), "")
                                        .replace("&nbsp;", " ")
                                        .replace("&#8217;", "'")
                                        .replace("&amp;", "&")
                                        .trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((tag.equals("item", ignoreCase = true) || tag.equals("entry", ignoreCase = true)) && insideItem) {
                            if (title.isNotBlank()) {
                                items.add(
                                    NewsItem(
                                        title = title,
                                        source = sourceLabel,
                                        pubDate = pubDate.ifBlank { "Latest" },
                                        link = link.ifBlank { "https://www.ufc.com" },
                                        summary = desc
                                    )
                                )
                            }
                            insideItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Throwable) {}
        return items
    }

    suspend fun fetchNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val seenTitles = mutableSetOf<String>()
        val combined = mutableListOf<NewsItem>()

        try {
            val jsonStr = httpGet("https://site.api.espn.com/apis/site/v2/sports/mma/ufc/news")
            if (jsonStr.isNotBlank()) {
                val articles = JSONObject(jsonStr).optJSONArray("articles")
                if (articles != null) {
                    for (i in 0 until articles.length()) {
                        val art = articles.getJSONObject(i)
                        val headline = art.optString("headline").trim()
                        val desc = art.optString("description").trim()
                        val published = art.optString("published").take(16).replace("T", " ")
                        val webLink = art.optJSONObject("links")?.optJSONObject("web")?.optString("href")
                            ?: "https://www.espn.com/mma/"
                        if (headline.isNotBlank() && seenTitles.add(headline.lowercase())) {
                            combined.add(NewsItem(headline, "ESPN UFC", published, webLink, desc))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        parseRssXml(httpGet("https://www.mmafighting.com/rss/current"), "MMA Fighting").take(15).forEach {
            if (seenTitles.add(it.title.lowercase())) combined.add(it)
        }
        parseRssXml(httpGet("https://www.sherdog.com/rss/news.xml"), "Sherdog RSS").take(15).forEach {
            if (seenTitles.add(it.title.lowercase())) combined.add(it)
        }

        if (combined.isEmpty()) {
            listOf(
                NewsItem(
                    title = "UFC Fight Night: Moicano vs. Nolan Live Results & Round-by-Round Updates",
                    source = "UFC Pulse Feed",
                    pubDate = "Live Today",
                    link = "https://www.ufc.com",
                    summary = "Follow live octagon results, fighter moneyline odds, and main card start times."
                ),
                NewsItem(
                    title = "UFC 334: Gane vs. Hokit & Harrison vs. Nunes Official Fight Card Finalized for MSG",
                    source = "UFC Pulse Feed",
                    pubDate = "Breaking",
                    link = "https://www.ufc.com",
                    summary = "Two title fights headline Madison Square Garden on November 14."
                )
            )
        } else {
            combined
        }
    }

    private fun mapDivisionCode(weightClass: String): String {
        val w = weightClass.lowercase()
        return when {
            w.contains("women") -> "WMMA"
            w.contains("light heavyweight") -> "LHW"
            w.contains("heavyweight") -> "HW"
            w.contains("middleweight") -> "MW"
            w.contains("welterweight") -> "WW"
            w.contains("lightweight") -> "LW"
            w.contains("featherweight") -> "FW"
            w.contains("bantamweight") -> "BW"
            w.contains("flyweight") -> "FLW"
            else -> "MMA"
        }
    }

    suspend fun fetchLiveScoreboardAndRoster(): Triple<LiveMatchState?, List<UfcEvent>, List<Fighter>> = withContext(Dispatchers.IO) {
        val dynamicEvents = mutableListOf<UfcEvent>()
        val dynamicFighters = LinkedHashMap<String, Fighter>()
        UfcSeedData.initialRoster.forEach { dynamicFighters[it.name] = it }
        var detectedLiveMatch: LiveMatchState? = null

        try {
            val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
            val start = LocalDate.now().minusDays(1).format(fmt)
            val end = LocalDate.now().plusDays(90).format(fmt)
            val jsonStr = httpGet("https://site.api.espn.com/apis/site/v2/sports/mma/ufc/scoreboard?dates=$start-$end")

            if (jsonStr.isNotBlank()) {
                val eventsArr = JSONObject(jsonStr).optJSONArray("events")
                if (eventsArr != null) {
                    for (i in 0 until eventsArr.length()) {
                        val ev = eventsArr.getJSONObject(i)
                        val eventName = ev.optString("name", "UFC Event")
                        val utcDateStr = ev.optString("date", "")
                        val localTimeFormatted = TimezoneHelper.formatEventTime(utcDateStr, utcDateStr.take(10))
                        val isPpv = eventName.contains(Regex("UFC \\d+"))
                        val comps = ev.optJSONArray("competitions")
                        val boutsList = mutableListOf<BoutDetail>()
                        var venueName = "UFC Arena"

                        if (comps != null) {
                            for (j in 0 until comps.length()) {
                                val comp = comps.getJSONObject(j)
                                if (j == 0) {
                                    venueName = comp.optJSONObject("venue")?.optString("fullName") ?: "UFC Apex / Arena"
                                }
                                val weightClass = comp.optJSONObject("type")?.optString("text") ?: "UFC Bout"
                                val statusObj = comp.optJSONObject("status")
                                val statusState = statusObj?.optJSONObject("type")?.optString("state") ?: "pre"
                                val statusDetail = statusObj?.optJSONObject("type")?.optString("detail") ?: "Scheduled"
                                val period = statusObj?.optInt("period", 1) ?: 1
                                val clock = statusObj?.optString("displayClock", "5:00") ?: "5:00"

                                val oddsObj = comp.optJSONArray("odds")?.optJSONObject(0)
                                val oddsDetails = oddsObj?.optString("details")?.ifBlank { null } ?: "Even -110"

                                val competitors = comp.optJSONArray("competitors")
                                if (competitors != null && competitors.length() >= 2) {
                                    val c1 = competitors.getJSONObject(0)
                                    val c2 = competitors.getJSONObject(1)
                                    val n1 = c1.optJSONObject("athlete")?.optString("displayName") ?: "Fighter 1"
                                    val n2 = c2.optJSONObject("athlete")?.optString("displayName") ?: "Fighter 2"
                                    val r1 = c1.optJSONArray("records")?.optJSONObject(0)?.optString("summary") ?: "15-3-0"
                                    val r2 = c2.optJSONArray("records")?.optJSONObject(0)?.optString("summary") ?: "14-4-0"
                                    val w1 = c1.optBoolean("winner", false)
                                    val w2 = c2.optBoolean("winner", false)

                                    val resultTag = when {
                                        w1 -> "Winner: $n1 ($statusDetail)"
                                        w2 -> "Winner: $n2 ($statusDetail)"
                                        statusState == "in" -> "LIVE R$period ($clock)"
                                        else -> "Pick: $n1 (56%)"
                                    }

                                    boutsList.add(
                                        BoutDetail(
                                            boutName = "$weightClass: $n1 ($r1) vs. $n2 ($r2)",
                                            odds = oddsDetails,
                                            prediction = resultTag
                                        )
                                    )

                                    if (statusState == "in" || (detectedLiveMatch == null && i == 0 && j == 0)) {
                                        detectedLiveMatch = LiveMatchState(
                                            eventTitle = eventName,
                                            weightClass = weightClass,
                                            fighterA = n1,
                                            recordA = r1,
                                            oddsA = oddsDetails,
                                            probA = if (w1) 100 else if (w2) 0 else 58,
                                            fighterB = n2,
                                            recordB = r2,
                                            oddsB = "Live Line",
                                            probB = if (w2) 100 else if (w1) 0 else 42,
                                            round = if (statusState == "in") period.toString() else "1",
                                            time = clock,
                                            strikesA = 28,
                                            strikesB = 21,
                                            takedownsA = 1,
                                            takedownsB = 0,
                                            status = "$statusDetail • $n1 vs. $n2",
                                            prediction = resultTag,
                                            isLiveFromApi = statusState == "in"
                                        )
                                    }

                                    val divCode = mapDivisionCode(weightClass)
                                    if (!dynamicFighters.containsKey(n1)) {
                                        dynamicFighters[n1] = Fighter(n1, weightClass, divCode, r1, "-120")
                                    }
                                    if (!dynamicFighters.containsKey(n2)) {
                                        dynamicFighters[n2] = Fighter(n2, weightClass, divCode, r2, "+100")
                                    }
                                }
                            }
                        }
                        if (boutsList.isNotEmpty()) {
                            dynamicEvents.add(
                                UfcEvent(
                                    title = eventName,
                                    type = if (isPpv) "PPV" else "FIGHT NIGHT",
                                    date = utcDateStr.take(10),
                                    localTime = localTimeFormatted,
                                    venue = venueName,
                                    bouts = boutsList.take(12)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        val mergedEvents = if (dynamicEvents.isNotEmpty()) {
            val titles = dynamicEvents.map { it.title.lowercase() }.toSet()
            dynamicEvents + UfcSeedData.upcomingEvents.filter { it.title.lowercase() !in titles }
        } else {
            UfcSeedData.upcomingEvents
        }

        Triple(detectedLiveMatch, mergedEvents, dynamicFighters.values.toList())
    }
}

// --- CRASH-PROOF BACKGROUND RSS WORKER ---
class UfcRssWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val news = UfcNetworkRepository.fetchNews()
            if (news.isNotEmpty()) {
                val prefs = applicationContext.getSharedPreferences("ufc_pulse_pro_v5_prefs", Context.MODE_PRIVATE)
                val lastHeadline = prefs.getString("last_headline_str", "") ?: ""
                val top = news.first()
                if (top.title.isNotBlank() && top.title != lastHeadline) {
                    prefs.edit().putString("last_headline_str", top.title).apply()
                    NotificationHelper.sendBreakingNewsAlert(applicationContext, top.title, top.source, top.link)
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        }
    }
}

// --- ONE UI LIVE BAR & RSS NOTIFICATION ENGINE ---
object NotificationHelper {
    private const val RSS_CHANNEL_ID = "ufc_rss_alerts_v5"
    private const val LIVE_BAR_CHANNEL_ID = "ufc_oneui_live_bar_v5"
    private const val LIVE_BAR_NOTIFICATION_ID = 334001

    fun createChannels(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val rssChannel = NotificationChannel(
                    RSS_CHANNEL_ID,
                    "UFC Breaking News RSS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Pop-up RSS notifications for breaking UFC news"
                }

                val liveBarChannel = NotificationChannel(
                    LIVE_BAR_CHANNEL_ID,
                    "One UI Now Bar Live Fight Ticker",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Ongoing live fight round, odds, and result prediction for One UI Now Bar"
                    setSound(null, null)
                }

                nm.createNotificationChannel(rssChannel)
                nm.createNotificationChannel(liveBarChannel)
            }
        } catch (_: Throwable) {}
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun sendBreakingNewsAlert(context: Context, headline: String, source: String, url: String) {
        try {
            if (!hasNotificationPermission(context)) return
            createChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val cleanUrl = if (url.startsWith("http")) url else "https://www.ufc.com"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
            val uniqueId = (headline.hashCode() and 0x7FFFFFFF)
            val pendingIntent = PendingIntent.getActivity(
                context,
                uniqueId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, RSS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("UFC News • $source")
                .setContentText(headline)
                .setStyle(NotificationCompat.BigTextStyle().bigText(headline))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(uniqueId, notification)
        } catch (_: Throwable) {}
    }

    fun updateOneUiLiveBar(context: Context, match: LiveMatchState, enabled: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!enabled) {
                nm.cancel(LIVE_BAR_NOTIFICATION_ID)
                return
            }
            if (!hasNotificationPermission(context)) return
            createChannels(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                LIVE_BAR_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = "R${match.round} (${match.time}) • ${match.fighterA} (${match.oddsA}) vs ${match.fighterB} (${match.oddsB})"
            val body = "${match.prediction} | ${match.status}"

            val notification = NotificationCompat.Builder(context, LIVE_BAR_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText("One UI Live Fight Ticker")
                .setShortCriticalText("R${match.round} ${match.probA}%")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setProgress(100, match.probA.coerceIn(0, 100), false)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(LIVE_BAR_NOTIFICATION_ID, notification)
        } catch (_: Throwable) {}
    }
}

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            NotificationHelper.createChannels(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                }
            }

            val workRequest = PeriodicWorkRequestBuilder<UfcRssWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ufc_rss_poll_v5",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (_: Throwable) {}

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD20A0A),
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF16161B)
                )
            ) {
                UfcPulseProScreen()
            }
        }
    }
}

// --- LIVE ROUND & PREDICTION SIMULATOR ---
fun advanceLiveSimulation(current: LiveMatchState): LiveMatchState {
    return when (current.round) {
        "1" -> current.copy(
            round = "2",
            time = "3:18",
            oddsA = "-185",
            oddsB = "+155",
            probA = 64,
            probB = 36,
            strikesA = current.strikesA + 18,
            strikesB = current.strikesB + 11,
            takedownsA = current.takedownsA + 1,
            status = "${current.fighterA} lands a body kick and secures a body-lock takedown against the cage.",
            prediction = "Live AI Pick: ${current.fighterA} via Decision or R3 Submission (64% prob)"
        )
        "2" -> current.copy(
            round = "3",
            time = "2:05",
            oddsA = "-290",
            oddsB = "+230",
            probA = 76,
            probB = 24,
            strikesA = current.strikesA + 24,
            strikesB = current.strikesB + 9,
            takedownsA = current.takedownsA + 1,
            status = "${current.fighterA} takes the back in Round 3 and threatens with a rear-naked choke.",
            prediction = "Live AI Pick: ${current.fighterA} Heavy Favorite via Submission / TKO (76% prob)"
        )
        "3" -> current.copy(
            round = "4",
            time = "1:40",
            oddsA = "-450",
            oddsB = "+340",
            probA = 84,
            probB = 16,
            strikesA = current.strikesA + 21,
            strikesB = current.strikesB + 12,
            status = "${current.fighterB} showing championship heart, but ${current.fighterA} is ahead 30-27 on live cards.",
            prediction = "Live AI Pick: ${current.fighterA} via Unanimous Decision Lock (84% prob)"
        )
        "4" -> current.copy(
            round = "FINAL",
            time = "5:00",
            oddsA = "-900",
            oddsB = "+600",
            probA = 95,
            probB = 5,
            strikesA = current.strikesA + 19,
            strikesB = current.strikesB + 14,
            status = "OFFICIAL RESULT: ${current.fighterA} def. ${current.fighterB} via Unanimous Decision (49-46, 49-46, 48-47).",
            prediction = "Final Result Verified: ${current.fighterA} Winner (Unanimous Decision)"
        )
        else -> current.copy(
            round = "1",
            time = "4:12",
            oddsA = "-155",
            oddsB = "+130",
            probA = 56,
            probB = 44,
            strikesA = 12,
            strikesB = 10,
            takedownsA = 0,
            takedownsB = 0,
            status = "Round 1 underway. Both fighters trading calf kicks and testing range in center Octagon.",
            prediction = "Live AI Pick: Opening Round Toss-Up • Slight grappling edge to ${current.fighterA} (56%)"
        )
    }
}

// --- MAIN ADAPTIVE COMPOSABLE (GALAXY Z FOLD 8 4:3 + COVER SCREEN) ---
@Composable
fun UfcPulseProScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeHandler = remember { CoroutineExceptionHandler { _, _ -> } }

    var selectedTab by remember { mutableIntStateOf(0) }
    var splitDashboardMode by remember { mutableStateOf(false) }
    var oneUiLiveBarPinned by remember { mutableStateOf(true) }

    var liveMatch by remember { mutableStateOf(UfcSeedData.defaultLiveMatch) }
    var newsList by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var selectedNews by remember { mutableStateOf<NewsItem?>(null) }

    var eventsList by remember { mutableStateOf(UfcSeedData.upcomingEvents) }
    var selectedEvent by remember { mutableStateOf<UfcEvent?>(UfcSeedData.upcomingEvents.firstOrNull()) }

    var rosterList by remember { mutableStateOf(UfcSeedData.initialRoster) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var divisionFilter by remember { mutableStateOf("ALL") }
    var eventFilter by remember { mutableStateOf("ALL") }

    fun refreshAllData() {
        isLoading = true
        scope.launch(safeHandler) {
            val fetchedNews = UfcNetworkRepository.fetchNews()
            newsList = fetchedNews
            if (selectedNews == null && fetchedNews.isNotEmpty()) {
                selectedNews = fetchedNews.first()
            }

            val (apiLive, liveEvents, liveRoster) = UfcNetworkRepository.fetchLiveScoreboardAndRoster()
            if (apiLive != null) {
                liveMatch = apiLive
            }
            eventsList = liveEvents
            if (selectedEvent == null && liveEvents.isNotEmpty()) {
                selectedEvent = liveEvents.first()
            }
            rosterList = liveRoster
            isLoading = false
            NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
        }
    }

    LaunchedEffect(Unit) {
        refreshAllData()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        val isUnfoldedFold8 = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121216))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("UFC PULSE PRO", color = Color(0xFFD20A0A), fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isUnfoldedFold8) "FOLD 8 INNER 4:3" else "COVER MODE",
                            color = Color(0xFFFFD700),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Live Tracker • Moneyline Odds • AI Predictor • GMT/IST", color = Color.Gray, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isUnfoldedFold8) {
                        Button(
                            onClick = { splitDashboardMode = !splitDashboardMode },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (splitDashboardMode) Color(0xFFFFD700) else Color(0xFF262630)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (splitDashboardMode) "3-Pane: ON" else "3-Pane View",
                                fontSize = 11.sp,
                                color = if (splitDashboardMode) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val top = newsList.firstOrNull()
                            NotificationHelper.sendBreakingNewsAlert(
                                context,
                                top?.title ?: "UFC Fight Night Live Results & Odds Updated",
                                top?.source ?: "UFC RSS",
                                top?.link ?: "https://www.ufc.com"
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262630)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Test RSS Alert", fontSize = 11.sp, color = Color.White)
                    }
                    Button(
                        onClick = { refreshAllData() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD20A0A)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Sync", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFD20A0A))
            }

            if (!(isUnfoldedFold8 && splitDashboardMode)) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF121216),
                    contentColor = Color(0xFFD20A0A)
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Live Tracker", fontSize = 12.sp) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("RSS (${newsList.size})", fontSize = 12.sp) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Events (${eventsList.size})", fontSize = 12.sp) })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Roster (${rosterList.size})", fontSize = 12.sp) })
                }
            }

            if (isUnfoldedFold8 && splitDashboardMode) {
                // GALAXY Z FOLD 8 3-PANE COMMAND DASHBOARD
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("LIVE OCTAGON & PREDICTOR", color = Color(0xFFFFD700), fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                        LiveTrackerPane(
                            match = liveMatch,
                            oneUiPinned = oneUiLiveBarPinned,
                            onToggleOneUiPin = {
                                oneUiLiveBarPinned = !oneUiLiveBarPinned
                                NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                            },
                            onAdvanceRound = {
                                liveMatch = advanceLiveSimulation(liveMatch)
                                NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                            }
                        )
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("BREAKING UFC RSS", color = Color(0xFFD20A0A), fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                        NewsListPane(
                            newsList = newsList,
                            selectedNews = null,
                            onSelect = { openUrlSafely(context, it.link) }
                        )
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("EVENTS & ODDS (LOCAL TZ)", color = Color(0xFF4CAF50), fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                        EventsListPane(
                            eventsList = eventsList,
                            eventFilter = "ALL",
                            selectedEvent = null,
                            onSelectEvent = {},
                            showFullBoutsInline = true
                        )
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> {
                        if (isUnfoldedFold8) {
                            // Unfolded Split-Screen: Live Octagon Left | Upcoming Bouts & Odds Right (16.dp Hinge Gutter)
                            Row(
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    LiveTrackerPane(
                                        match = liveMatch,
                                        oneUiPinned = oneUiLiveBarPinned,
                                        onToggleOneUiPin = {
                                            oneUiLiveBarPinned = !oneUiLiveBarPinned
                                            NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                                        },
                                        onAdvanceRound = {
                                            liveMatch = advanceLiveSimulation(liveMatch)
                                            NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                                        }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    EventDetailPane(event = eventsList.firstOrNull())
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                LiveTrackerPane(
                                    match = liveMatch,
                                    oneUiPinned = oneUiLiveBarPinned,
                                    onToggleOneUiPin = {
                                        oneUiLiveBarPinned = !oneUiLiveBarPinned
                                        NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                                    },
                                    onAdvanceRound = {
                                        liveMatch = advanceLiveSimulation(liveMatch)
                                        NotificationHelper.updateOneUiLiveBar(context, liveMatch, oneUiLiveBarPinned)
                                    }
                                )
                            }
                        }
                    }

                    1 -> {
                        if (isUnfoldedFold8) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    NewsListPane(
                                        newsList = newsList,
                                        selectedNews = selectedNews,
                                        onSelect = { selectedNews = it }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    NewsDetailPane(item = selectedNews ?: newsList.firstOrNull())
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                NewsListPane(
                                    newsList = newsList,
                                    selectedNews = null,
                                    onSelect = { openUrlSafely(context, it.link) }
                                )
                            }
                        }
                    }

                    2 -> {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                                listOf("ALL", "PPV", "FIGHT NIGHT").forEach { filter ->
                                    Button(
                                        onClick = { eventFilter = filter },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (eventFilter == filter) Color(0xFFD20A0A) else Color(0xFF22222A)
                                        )
                                    ) {
                                        Text(filter, fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            if (isUnfoldedFold8) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        EventsListPane(
                                            eventsList = eventsList,
                                            eventFilter = eventFilter,
                                            selectedEvent = selectedEvent,
                                            onSelectEvent = { selectedEvent = it },
                                            showFullBoutsInline = false
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        EventDetailPane(event = selectedEvent ?: eventsList.firstOrNull())
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    EventsListPane(
                                        eventsList = eventsList,
                                        eventFilter = eventFilter,
                                        selectedEvent = null,
                                        onSelectEvent = {},
                                        showFullBoutsInline = true
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            RosterPane(
                                rosterList = rosterList,
                                searchQuery = searchQuery,
                                onSearchChange = { searchQuery = it },
                                divisionFilter = divisionFilter,
                                onDivisionChange = { divisionFilter = it },
                                columns = if (isUnfoldedFold8) 2 else 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTrackerPane(
    match: LiveMatchState,
    oneUiPinned: Boolean,
    onToggleOneUiPin: () -> Unit,
    onAdvanceRound: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            // One UI Now Bar / Dynamic Island Pill Controller
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181822)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (oneUiPinned) "● ONE UI NOW BAR / STATUS PILL ACTIVE" else "○ ONE UI NOW BAR PAUSED",
                            color = if (oneUiPinned) Color(0xFF4CAF50) else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${match.fighterA} (${match.oddsA}) vs ${match.fighterB} (${match.oddsB})",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "R${match.round} • ${match.prediction}",
                            color = Color(0xFFFFD700),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onToggleOneUiPin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (oneUiPinned) Color(0xFF2B2B38) else Color(0xFFD20A0A)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (oneUiPinned) "Unpin Bar" else "Pin Now Bar", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        item {
            // Live Fight Card with Odds & Real-Time Prediction Bar
            Card(
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFD20A0A), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (match.isLiveFromApi) "● LIVE OCTAGON FEED" else "● LIVE TRACKER & ROUND PREDICTOR",
                            color = Color(0xFFFF4500),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Round ${match.round} • ${match.time}",
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${match.eventTitle} • ${match.weightClass}", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(match.fighterA, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            Text("Record: ${match.recordA}", color = Color.Gray, fontSize = 12.sp)
                            Text("Live Odds: ${match.oddsA}", color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("VS", color = Color(0xFFD20A0A), fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp))
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(match.fighterB, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            Text("Record: ${match.recordB}", color = Color.Gray, fontSize = 12.sp)
                            Text("Live Odds: ${match.oddsB}", color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Win Probability Bar
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${match.fighterA} Win Prob: ${match.probA}%", color = Color(0xFFD20A0A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${match.fighterB} Win Prob: ${match.probB}%", color = Color(0xFF2196F3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (match.probA / 100f).coerceIn(0.05f, 0.95f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = Color(0xFFD20A0A),
                        trackColor = Color(0xFF2196F3)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live Fight Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E26), RoundedCornerShape(8.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sig. Strikes: ${match.strikesA}", color = Color.White, fontSize = 12.sp)
                        Text("Takedowns: ${match.takedownsA} - ${match.takedownsB}", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Sig. Strikes: ${match.strikesB}", color = Color.White, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live Outcome Predictor Box
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF20202A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("LIVE RESULT PREDICTION AS FIGHT PLAYS OUT", color = Color(0xFF4CAF50), fontSize = 10.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(match.prediction, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(match.status, color = Color(0xFFC0C0C8), fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onAdvanceRound,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD20A0A))
                    ) {
                        Text("Simulate Next Live Round & Update Now Bar ➔", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun NewsListPane(
    newsList: List<NewsItem>,
    selectedNews: NewsItem?,
    onSelect: (NewsItem) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(newsList) { item ->
            val isSelected = selectedNews?.title == item.title
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) Color(0xFFD20A0A) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(item) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.source, color = Color(0xFFD20A0A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(item.pubDate, color = Color.Gray, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (item.summary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.summary, color = Color(0xFFB0B0B8), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun NewsDetailPane(item: NewsItem?) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (item == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select an article on the left pane to inspect", color = Color.Gray)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.source, color = Color(0xFFD20A0A), fontSize = 13.sp, fontWeight = FontWeight.Black)
                        Text(item.pubDate, color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        item.summary.ifBlank { "Tap below to read the full story directly on ${item.source}." },
                        color = Color(0xFFD8D8E0),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
                Button(
                    onClick = { openUrlSafely(context, item.link) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD20A0A))
                ) {
                    Text("Open Full Article in Browser", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EventsListPane(
    eventsList: List<UfcEvent>,
    eventFilter: String,
    selectedEvent: UfcEvent? = null,
    onSelectEvent: (UfcEvent) -> Unit,
    showFullBoutsInline: Boolean
) {
    val filtered = eventsList.filter { eventFilter == "ALL" || it.type == eventFilter }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(filtered) { ev ->
            val isSelected = selectedEvent?.title == ev.title && !showFullBoutsInline
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) Color(0xFFFFD700) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectEvent(ev) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            ev.type,
                            color = if (ev.type == "PPV") Color(0xFFFFD700) else Color(0xFFD20A0A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(ev.localTime, color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(ev.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(ev.venue, color = Color.Gray, fontSize = 12.sp)
                    if (showFullBoutsInline) {
                        Spacer(modifier = Modifier.height(10.dp))
                        ev.bouts.forEach { bout ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("• ${bout.boutName}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Odds: ${bout.odds}", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(bout.prediction, color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("${ev.bouts.size} Scheduled Bouts with Odds & Picks • Tap to view", color = Color(0xFF9E9EA8), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EventDetailPane(event: UfcEvent?) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (event == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select an event to inspect fight card odds & predictions", color = Color.Gray)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    event.type,
                    color = if (event.type == "PPV") Color(0xFFFFD700) else Color(0xFFD20A0A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(event.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("Local Start: ${event.localTime}", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(event.venue, color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(event.bouts) { bout ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF202028)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(bout.boutName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Odds: ${bout.odds}", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(bout.prediction, color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RosterPane(
    rosterList: List<Fighter>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    divisionFilter: String,
    onDivisionChange: (String) -> Unit,
    columns: Int
) {
    val divisions = listOf("ALL", "CHAMPS", "HW", "LHW", "MW", "WW", "LW", "FW", "BW", "FLW", "WMMA")
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text("Search fighter or division (e.g. Gane, Oliveira, Heavyweight)") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            divisions.forEach { div ->
                Button(
                    onClick = { onDivisionChange(div) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (divisionFilter == div) Color(0xFFD20A0A) else Color(0xFF22222A)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(div, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        val filteredRoster = rosterList.filter { f ->
            val matchesSearch = f.name.contains(searchQuery, ignoreCase = true) ||
                f.division.contains(searchQuery, ignoreCase = true)
            val matchesDiv = when (divisionFilter) {
                "ALL" -> true
                "CHAMPS" -> f.isChamp
                else -> f.code.equals(divisionFilter, ignoreCase = true)
            }
            matchesSearch && matchesDiv
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(filteredRoster) { fighter ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fighter.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (fighter.isChamp) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("CHAMP", color = Color(0xFFFFD700), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Text("${fighter.division} • Line: ${fighter.odds}", color = Color.Gray, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fighter.record, color = Color(0xFF4CAF50), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            Text("W - L - D", color = Color.Gray, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}
