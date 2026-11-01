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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit. спор
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

// --- DATA MODELS ---
data class NewsItem(val title: String, val source: String, val pubDate: String, val link: String, val summary: String)
data class UfcEvent(val title: String, val type: String, val date: String, val localTime: String, val venue: String, val bouts: List<BoutDetail>)
data class BoutDetail(val boutName: String, val odds: String)
data class Fighter(val name: String, val division: String, val code: String, val record: String, val isChamp: Boolean = false)
data class LiveMatchState(val fighterA: String, val oddsA: String, var probA: Int, val fighterB: String, val oddsB: String, var probB: Int, var round: String, var time: String, var status: String, var prediction: String, val eventTitle: String)

// --- TIMEZONE HELPER ---
object TimezoneHelper {
    fun formatEventTime(utcIsoString: String, fallbackDateStr: String): String {
        return try {
            if (utcIsoString.isBlank()) return fallbackDateStr
            val parsedZdt = ZonedDateTime.parse(utcIsoString)
            val localZdt = parsedZdt.withZoneSameInstant(java.time.ZoneId.systemDefault())
            val formatter = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a (zz)", Locale.getDefault())
            localZdt.format(formatter)
        } catch (_: Exception) {
            fallbackDateStr
        }
    }
}

// --- SEED DATA ---
object UfcSeedData {
    val upcomingEvents = listOf(
        UfcEvent(
            title = "UFC Fight Night: Moicano vs. Nolan", type = "FIGHT NIGHT", date = "Sat, Nov 1, 2026",
            localTime = TimezoneHelper.formatEventTime("2026-11-01T00:00:00Z", "Sat 12:00 AM UTC"),
            venue = "Meta Apex, Las Vegas, US",
            bouts = listOf(
                BoutDetail("Lightweight Main Event: Renato Moicano vs. Tom Nolan", "Moicano -145 / Nolan +125"),
                BoutDetail("Welterweight: Randy Brown vs. Jacobe Smith", "Brown +180 / Smith -220"),
                BoutDetail("Women's Bantamweight: Yana Santos vs. Luana Santos", "Y. Santos +250 / L. Santos -310")
            )
        ),
        UfcEvent(
            title = "UFC 334: Gane vs. Hokit", type = "PPV", date = "Sat, Nov 14, 2026",
            localTime = TimezoneHelper.formatEventTime("2026-11-14T22:00:00Z", "Sat 10:00 PM UTC"),
            venue = "Madison Square Garden, New York City, US",
            bouts = listOf(
                BoutDetail("Heavyweight World Title: Ciryl Gane (C) vs. Josh Hokit", "Gane -350 / Hokit +280"),
                BoutDetail("Women's Bantamweight Title: Kayla Harrison (C) vs. Amanda Nunes", "Harrison -130 / Nunes +110")
            )
        ),
        UfcEvent(
            title = "UFC 335: Oliveira vs. Lopes", type = "PPV", date = "Sat, Dec 12, 2026",
            localTime = TimezoneHelper.formatEventTime("2026-12-12T22:00:00Z", "Sat 10:00 PM UTC"),
            venue = "T-Mobile Arena, Las Vegas, US",
            bouts = listOf(
                BoutDetail("BMF Title (Lightweight): Charles Oliveira (C) vs. Diego Lopes", "Oliveira -160 / Lopes +135"),
                BoutDetail("Flyweight World Title: Joshua Van (C) vs. Manel Kape", "Van -120 / Kape +100")
            )
        )
    )

    val initialRoster = listOf(
        Fighter("Ciryl Gane", "Heavyweight", "HW", "13-2-0", isChamp = true),
        Fighter("Carlos Ulberg", "Light Heavyweight", "LHW", "12-1-0", isChamp = true),
        Fighter("Sean Strickland", "Middleweight", "MW", "29-6-0", isChamp = true),
        Fighter("Islam Makhachev", "Welterweight", "WW", "27-1-0", isChamp = true),
        Fighter("Justin Gaethje", "Lightweight", "LW", "26-5-0", isChamp = true),
        Fighter("Charles Oliveira", "Lightweight (BMF)", "LW", "35-10-0", isChamp = true),
        Fighter("Alexander Volkanovski", "Featherweight", "FW", "29-4-0", isChamp = true),
        Fighter("Petr Yan", "Bantamweight", "BW", "21-5-0", isChamp = true),
        Fighter("Joshua Van", "Flyweight", "FLW", "15-2-0", isChamp = true),
        Fighter("Kayla Harrison", "Women's Bantamweight", "WMMA", "19-1-0", isChamp = true)
    )
}

// --- NETWORK REPOSITORY ---
object UfcNetworkRepository {
    private fun httpGet(urlString: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Chrome/130.0.0.0")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
        } catch (_: Exception) { "" } finally { conn?.disconnect() }
    }

    suspend fun fetchNews(): List<NewsItem> = withContext(Dispatchers.IO) {
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
                        val webLink = art.optJSONObject("links")?.optJSONObject("web")?.optString("href") ?: "https://www.ufc.com"
                        if (headline.isNotBlank()) combined.add(NewsItem(headline, "ESPN UFC", published, webLink, desc))
                    }
                }
            }
        } catch (_: Exception) {}
        combined
    }
}

// --- NOTIFICATION & ONE UI NOW BAR HELPER ---
object NotificationHelper {
    private const val CHANNEL_ID = "ufc_live_bar"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "UFC Live Fight Ticker", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Constant live ticker feed for One UI Now Bar"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun sendLiveTickerNotification(context: Context, title: String, status: String) {
        try {
            createChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) return

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 999, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(status)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(999, notification)
        } catch (_: SecurityException) {}
    }
}

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD20A0A),
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF16161B)
                )
            ) {
                UfcPulseApp()
            }
        }
    }
}

@Composable
fun UfcPulseApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var splitDashboardMode by remember { mutableStateOf(false) }

    var newsList by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var eventsList by remember { mutableStateOf(UfcSeedData.upcomingEvents) }
    var rosterList by remember { mutableStateOf(UfcSeedData.initialRoster) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var divisionFilter by remember { mutableStateOf("ALL") }
    var eventFilter by remember { mutableStateOf("ALL") }

    // Live Match Simulation State
    var liveMatch by remember {
        mutableStateOf(
            LiveMatchState(
                fighterA = "Renato Moicano", oddsA = "-145", probA = 58,
                fighterB = "Tom Nolan", oddsB = "+125", probB = 42,
                round = "2", time = "3:42",
                status = "Moicano controlling clinch & securing takedown control.",
                prediction = "Moicano favored to win via Decision (74% confidence)",
                eventTitle = "UFC Fight Night: Moicano vs. Nolan"
            )
        )
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        scope.launch {
            newsList = UfcNetworkRepository.fetchNews()
            isLoading = false
            NotificationHelper.sendLiveTickerNotification(context, "LIVE: ${liveMatch.fighterA} vs ${liveMatch.fighterB}", liveMatch.status)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        val isUnfoldedFold8 = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF121216)).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("UFC PULSE PRO", color = Color(0xFFD20A0A), fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isUnfoldedFold8) "FOLD 4:3" else "COVER", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Live Tracker • Odds • AI Predictor", color = Color.Gray, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isUnfoldedFold8) {
                        Button(
                            onClick = { splitDashboardMode = !splitDashboardMode },
                            colors = ButtonDefaults.buttonColors(containerColor = if (splitDashboardMode) Color(0xFFFFD700) else Color(0xFF262630)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (splitDashboardMode) "3-Pane: ON" else "3-Pane", fontSize = 11.sp, color = if (splitDashboardMode) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                newsList = UfcNetworkRepository.fetchNews()
                                isLoading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD20A0A)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Sync", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFD20A0A))

            if (!(isUnfoldedFold8 && splitDashboardMode)) {
                TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF121216), contentColor = Color(0xFFD20A0A)) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("🟢 Live Fights") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("RSS News") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Events") })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Roster") })
                }
            }

            if (isUnfoldedFold8 && splitDashboardMode) {
                Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("LIVE OCTAGON TICKER", color = Color(0xFFFFD700), fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        LiveMatchPane(liveMatch, onSimulate = {
                            liveMatch = simulateRound(liveMatch)
                            NotificationHelper.sendLiveTickerNotification(context, "LIVE: ${liveMatch.fighterA} vs ${liveMatch.fighterB}", liveMatch.status)
                        })
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("BREAKING RSS", color = Color(0xFFD20A0A), fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        NewsListPane(newsList, onSelect = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.link))) })
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("ROSTER W-L-D", color = Color(0xFF4CAF50), fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        RosterPane(rosterList, searchQuery, { searchQuery = it }, divisionFilter, { divisionFilter = it }, 1)
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        LiveMatchPane(liveMatch, onSimulate = {
                            liveMatch = simulateRound(liveMatch)
                            NotificationHelper.sendLiveTickerNotification(context, "LIVE: ${liveMatch.fighterA} vs ${liveMatch.fighterB}", liveMatch.status)
                        })
                    }
                    1 -> Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        NewsListPane(newsList, onSelect = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.link))) })
                    }
                    2 -> Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            listOf("ALL", "PPV", "FIGHT NIGHT").forEach { filter ->
                                Button(
                                    onClick = { eventFilter = filter },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (eventFilter == filter) Color(0xFFD20A0A) else Color(0xFF22222A))
                                ) { Text(filter, fontSize = 12.sp, color = Color.White) }
                            }
                        }
                        EventsListPane(eventsList, eventFilter)
                    }
                    3 -> Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        RosterPane(rosterList, searchQuery, { searchQuery = it }, divisionFilter, { divisionFilter = it }, if (isUnfoldedFold8) 2 else 1)
                    }
                }
            }
        }
    }
}

fun simulateRound(current: LiveMatchState): LiveMatchState {
    return when (current.round) {
        "2" -> current.copy(round = "3", time = "2:15", probA = 72, probB = 28, status = "Moicano landing heavy ground-and-pound elbows.", prediction = "Moicano heavily favored to win via TKO or Decision.")
        "3" -> current.copy(round = "4", time = "1:05", probA = 85, probB = 15, status = "Nolan bleeding; defending submission attempts.", prediction = "Submission or Unanimous Decision lock for Moicano.")
        "4" -> current.copy(round = "FINAL", time = "5:00", probA = 92, probB = 8, status = "Fight concluded. Awaiting official decision.", prediction = "Winner Prediction: Renato Moicano via Decision.")
        else -> current.copy(round = "2", time = "3:42", probA = 58, probB = 42, status = "Moicano controlling clinch & securing takedown control.", prediction = "Moicano favored to win via Decision (74% confidence)")
    }
}

@Composable
fun LiveMatchPane(match: LiveMatchState, onSimulate: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            // One UI Live Status Bar Widget Simulation
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181822)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ONE UI LIVE TICKER ACTIVE", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${match.fighterA} vs ${match.fighterB}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(match.status, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = onSimulate, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD20A0A))) {
                        Text("Next Round ➔", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("● LIVE IN OCTAGON", color = Color(0xFFFF4500), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Round ${match.round} • ${match.time}", color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(match.fighterA, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("Odds: ${match.oddsA}", color = Color(0xFFFFD700), fontSize = 12.sp)
                        }
                        Text("VS", color = Color(0xFFD20A0A), fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(match.fighterB, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("Odds: ${match.oddsB}", color = Color(0xFFFFD700), fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${match.fighterA} (${match.probA}%)", color = Color(0xFFD20A0A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${match.fighterB} (${match.probB}%)", color = Color(0xFF2196F3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { match.probA / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFFD20A0A),
                        trackColor = Color(0xFF2196F3),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202028)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("🤖 AI LIVE PREDICTION & MOMENTUM", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(match.prediction, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewsListPane(newsList: List<NewsItem>, onSelect: (NewsItem) -> Unit) {
    if (newsList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading live RSS feeds...", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(newsList) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(item) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.source, color = Color(0xFFD20A0A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(item.pubDate, color = Color.Gray, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EventsListPane(eventsList: List<UfcEvent>, eventFilter: String) {
    val filtered = eventsList.filter { eventFilter == "ALL" || it.type == eventFilter }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(filtered) { ev ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ev.type, color = if (ev.type == "PPV") Color(0xFFFFD700) else Color(0xFFD20A0A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        Text(ev.localTime, color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(ev.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(ev.venue, color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    ev.bouts.forEach { bout ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("• ${bout.boutName}", color = Color(0xFFD8D8E0), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(bout.odds, color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RosterPane(rosterList: List<Fighter>, searchQuery: String, onSearchChange: (String) -> Unit, divisionFilter: String, onDivisionChange: (String) -> Unit, columns: Int) {
    val divisions = listOf("ALL", "CHAMPS", "HW", "LHW", "MW", "WW", "LW", "FW", "BW", "FLW")
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = { Text("Search fighter or division") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            divisions.forEach { div ->
                Button(
                    onClick = { onDivisionChange(div) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (divisionFilter == div) Color(0xFFD20A0A) else Color(0xFF22222A)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) { Text(div, fontSize = 11.sp, color = Color.White) }
            }
        }
        val filtered = rosterList.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.division.contains(searchQuery, ignoreCase = true)
        }
        LazyVerticalGrid(columns = GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered) { fighter ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fighter.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (fighter.isChamp) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CHAMP", color = Color(0xFFFFD700), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Text(fighter.division, color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(fighter.record, color = Color(0xFF4CAF50), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
