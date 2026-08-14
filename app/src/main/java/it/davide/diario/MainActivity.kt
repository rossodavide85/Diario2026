@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.davide.diario

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.LaunchedEffect
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

const val YEAR = 2026

// Your maximum heart rate, used to compute HR training zones (Z1–Z5).
const val MAX_HR = 188

// Approx. calories per alcohol unit (~12 g ethanol × 7 kcal/g ≈ 84, rounded).
const val KCAL_PER_ALCOHOL_UNIT = 90

// Weekly goal: max number of days with alcohol.
const val ALCOHOL_WEEKLY_GOAL = 2

@Serializable
data class DayRecord(
    val alcohol: String? = null,
    val activity: String? = null,
    val distanceKm: Double? = null,
    val durationMin: Int? = null,
    val calories: Int? = null,
    val avgHr: Int? = null,
    val elevationM: Int? = null,
    val alcoholUnits: Int? = null,
    val drinks: Map<String, Int>? = null
) {
    val isEmpty: Boolean get() = alcohol == null && activity == null
}

data class Drink(val key: String, val label: String, val units: Double, val kcal: Int)

// Standard servings: alcohol units (1 unit ≈ 12 g ethanol) and calories per serving.
val DRINKS = listOf(
    Drink("wine", "🍷 Vino (125 ml)", 1.0, 90),
    Drink("beer", "🍺 Birra chiara (33 cl)", 1.1, 140),
    Drink("strongbeer", "🍺 Doppio malto (33 cl)", 1.7, 230),
    Drink("spirit", "🥃 Superalcolico (40 ml)", 1.0, 95),
    Drink("aperitif", "🍹 Aperitivo (spritz)", 0.8, 130)
)
private val DRINK_BY_KEY = DRINKS.associateBy { it.key }

/** Total alcohol units of a day (from the drinks map, or legacy alcoholUnits). */
fun unitsOf(rec: DayRecord): Double {
    val d = rec.drinks
    return if (d != null) d.entries.sumOf { (k, c) -> (DRINK_BY_KEY[k]?.units ?: 0.0) * c }
    else (rec.alcoholUnits ?: 0).toDouble()
}

/** Calories from alcohol for a day (from the drinks map, or legacy units × 90). */
fun alcoholKcalOf(rec: DayRecord): Int {
    val d = rec.drinks
    return if (d != null) d.entries.sumOf { (k, c) -> (DRINK_BY_KEY[k]?.kcal ?: 0) * c }
    else (rec.alcoholUnits ?: 0) * KCAL_PER_ALCOHOL_UNIT
}

fun fmtUnits(u: Double): String =
    if (u == u.toLong().toDouble()) u.toLong().toString() else "%.1f".format(u)

private fun seedData(): Map<String, DayRecord> = mapOf(
    "2026-07-28" to DayRecord("yes", "rest"),
    "2026-07-29" to DayRecord("yes", "rest"),
    "2026-07-30" to DayRecord("no", "run"),
    "2026-07-31" to DayRecord("no", "walk"),
    "2026-08-01" to DayRecord("yes", "walk"),
    "2026-08-02" to DayRecord("no", "rest"),
    "2026-08-04" to DayRecord("no", "walk"),
    "2026-08-05" to DayRecord("yes", "run"),
    "2026-08-06" to DayRecord("no", "walk"),
    "2026-08-07" to DayRecord("no", "walk"),
    "2026-08-08" to DayRecord("yes", "run"),
    "2026-08-09" to DayRecord("no", "rest"),
    "2026-08-10" to DayRecord("no", "run"),
    "2026-08-11" to DayRecord("no", "walk"),
)

/** Local-only persistence: a single JSON file in the app's private storage. */
class DiaryStore(context: Context) {
    private val file = File(context.filesDir, "diario.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): Map<String, DayRecord> {
        if (!file.exists()) {
            val s = seedData()
            save(s)
            return s
        }
        return runCatching { json.decodeFromString<Map<String, DayRecord>>(file.readText()) }
            .getOrDefault(emptyMap())
    }

    fun save(data: Map<String, DayRecord>) {
        runCatching { file.writeText(json.encodeToString(data)) }
    }

    fun serialize(data: Map<String, DayRecord>): String = json.encodeToString(data)

    fun parse(text: String): Map<String, DayRecord> =
        json.decodeFromString<Map<String, DayRecord>>(text)
}

// Accent colors (chosen to read well in both light and dark themes)
private val CAlcohol = Color(0xFFD8952E)
private val CRun = Color(0xFFE05A3A)
private val CWalk = Color(0xFF57A65F)
private val CRest = Color(0xFF9A9282)
private val CWater = Color(0xFF3F9AC9)
private val CHeart = Color(0xFFD64550)
private val CElev = Color(0xFF9E7B4F)

private val MONTHS = arrayOf(
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
)
private val DOW = listOf("Lu", "Ma", "Me", "Gi", "Ve", "Sa", "Do")

data class Counts(val alcoholYes: Int, val alcoholNo: Int, val run: Int, val walk: Int, val rest: Int)

private enum class FilterType(val label: String, val emoji: String, val color: Color) {
    ALCOHOL_YES("Alcool sì", "🍺", CAlcohol),
    ALCOHOL_NO("Alcool no", "💧", CWater),
    RUN("Corse", "🏃", CRun),
    WALK("Camminate", "🚶", CWalk),
    REST("Riposi", "😴", CRest);

    fun matches(rec: DayRecord): Boolean = when (this) {
        ALCOHOL_YES -> rec.alcohol == "yes"
        ALCOHOL_NO -> rec.alcohol == "no"
        RUN -> rec.activity == "run"
        WALK -> rec.activity == "walk"
        REST -> rec.activity == "rest"
    }
}

data class Streaks(val current: Int, val longest: Int)

data class PeriodStats(
    val label: String,
    val total: Int,
    val alcoholYes: Int,
    val alcoholNo: Int,
    val run: Int,
    val walk: Int,
    val rest: Int,
    val totalKm: Double,
    val totalMin: Int,
    val totalCalories: Int,
    val avgHr: Int?,
    val totalElevation: Int,
    val alcoholUnits: Int,
    val alcoholKcal: Int
)

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Evening reminder at 23:30
        Reminder.ensureChannel(this)
        Reminder.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DiarioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DiaryApp()
                }
            }
        }
    }
}

@Composable
fun DiarioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF7FC3BB), secondary = Color(0xFF7FC3BB))
    } else {
        lightColorScheme(primary = Color(0xFF3A6B66), secondary = Color(0xFF3A6B66))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun DiaryApp() {
    val context = LocalContext.current
    val store = remember { DiaryStore(context) }
    val data = remember { mutableStateMapOf<String, DayRecord>().apply { putAll(store.load()) } }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<FilterType?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var showHeatmap by remember { mutableStateOf(false) }
    var detailKey by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ActivityDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }

    LaunchedEffect(detailKey) {
        val k = detailKey
        if (k != null) {
            detailLoading = true
            detail = runCatching { HealthImport.readActivityDetail(context, k, MAX_HR) }.getOrNull()
            detailLoading = false
        } else {
            detail = null
        }
    }

    val counts by remember {
        derivedStateOf {
            var ay = 0; var an = 0; var r = 0; var w = 0; var rest = 0
            data.values.forEach { rec ->
                when (rec.alcohol) { "yes" -> ay++; "no" -> an++ }
                when (rec.activity) { "run" -> r++; "walk" -> w++; "rest" -> rest++ }
            }
            Counts(ay, an, r, w, rest)
        }
    }

    // Backup: let the user choose where to write a JSON file.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(store.serialize(data).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(context, "Backup salvato", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Backup non riuscito", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Restore: read a previously saved JSON file and replace all data.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                Toast.makeText(context, "Impossibile leggere il file", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { store.parse(text) }.onSuccess { restored ->
                    data.clear()
                    data.putAll(restored)
                    store.save(data)
                    DiaryWidgetProvider.refresh(context)
                    Toast.makeText(context, "Dati ripristinati (${restored.size} giorni)", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "File di backup non valido", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Garmin import via Health Connect (local, no credentials, no server).
    val scope = rememberCoroutineScope()

    suspend fun performImport(silent: Boolean) {
        val n = runCatching {
            val imported = HealthImport.readActivities(context)
            imported.forEach { (key, imp) ->
                val existing = data[key] ?: DayRecord()
                data[key] = existing.copy(
                    activity = imp.activity,
                    distanceKm = imp.km.takeIf { it > 0 },
                    durationMin = imp.minutes.takeIf { it > 0 },
                    calories = imp.calories.takeIf { it > 0 },
                    avgHr = imp.avgHr,
                    elevationM = imp.elevationM.takeIf { it > 0 }
                )
            }
            store.save(data)
            DiaryWidgetProvider.refresh(context)
            imported.size
        }.getOrElse { -1 }
        if (!silent) {
            Toast.makeText(
                context,
                if (n >= 0) "Sincronizzate $n attività da Garmin" else "Errore lettura Health Connect",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val healthPermLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        scope.launch {
            if (HealthImport.hasPermissions(context)) performImport(false)
            else Toast.makeText(context, "Permesso Health Connect negato", Toast.LENGTH_SHORT).show()
        }
    }

    fun importFromGarmin() {
        if (!HealthImport.isAvailable(context)) {
            Toast.makeText(context, "Health Connect non disponibile su questo telefono", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            if (HealthImport.hasAllPermissions(context)) performImport(false)
            else healthPermLauncher.launch(HealthImport.PERMISSIONS)
        }
    }

    // Auto-sync on every app open (silent), if already authorised.
    LaunchedEffect(Unit) {
        if (HealthImport.isAvailable(context) && HealthImport.hasPermissions(context)) {
            performImport(silent = true)
        }
    }

    val listState = rememberLazyListState()
    // Open on July (index 6) at first launch.
    LaunchedEffect(Unit) { runCatching { listState.scrollToItem(6) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diario $YEAR") },
                actions = {
                    TextButton(onClick = { exportLauncher.launch("diario-$YEAR-backup.json") }) {
                        Text("Backup")
                    }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Ripristina")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatsRow(counts) { filter = it }
            Legend()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = { showStats = true }, modifier = Modifier.weight(1f)) {
                    Text("📊 Statistiche")
                }
                FilledTonalButton(onClick = { showHeatmap = true }, modifier = Modifier.weight(1f)) {
                    Text("🗓️ Anno")
                }
            }
            FilledTonalButton(
                onClick = { importFromGarmin() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)
            ) {
                Text("⌚  Sincronizza Garmin ora")
            }
            MonthBalanceCard(data)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(12) { m ->
                    MonthView(
                        month = m + 1,
                        data = data,
                        today = LocalDate.now(),
                        onDayClick = { key -> editingKey = key }
                    )
                }
            }
        }
    }

    editingKey?.let { key ->
        EditDialog(
            dateLabel = labelFor(key),
            record = data[key] ?: DayRecord(),
            onConfirm = { rec ->
                if (rec.isEmpty) data.remove(key) else data[key] = rec
                store.save(data)
                DiaryWidgetProvider.refresh(context)
                editingKey = null
            },
            onClear = {
                data.remove(key)
                store.save(data)
                DiaryWidgetProvider.refresh(context)
                editingKey = null
            },
            onShowDetail = if (data[key]?.activity == "run" || data[key]?.activity == "walk") {
                { detailKey = key; editingKey = null }
            } else null,
            onDismiss = { editingKey = null }
        )
    }

    filter?.let { f ->
        FilterScreen(filter = f, data = data, onClose = { filter = null })
    }

    if (showStats) {
        StatsScreen(data = data, onClose = { showStats = false })
    }

    if (showHeatmap) {
        HeatmapScreen(
            data = data,
            onClose = { showHeatmap = false },
            onEditDay = { key -> showHeatmap = false; editingKey = key }
        )
    }

    detailKey?.let { k ->
        ActivityDetailScreen(
            dateLabel = labelForFull(k),
            loading = detailLoading,
            detail = detail,
            onClose = { detailKey = null }
        )
    }
}

@Composable
private fun StatsRow(c: Counts, onFilter: (FilterType) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(c.alcoholYes, FilterType.ALCOHOL_YES, onFilter)
        StatCard(c.alcoholNo, FilterType.ALCOHOL_NO, onFilter)
        StatCard(c.run, FilterType.RUN, onFilter)
        StatCard(c.walk, FilterType.WALK, onFilter)
        StatCard(c.rest, FilterType.REST, onFilter)
    }
}

@Composable
private fun StatCard(n: Int, filter: FilterType, onFilter: (FilterType) -> Unit) {
    Card(
        modifier = Modifier.width(108.dp).clickable { onFilter(filter) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(4.dp).height(64.dp).background(filter.color))
            Column(Modifier.padding(10.dp)) {
                Text("$n", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = filter.color)
                Text("${filter.emoji} ${filter.label}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("🍺 Alcool", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("🏃 Corsa", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("🚶 Camminata", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(CRest))
            Text("Riposo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MonthView(
    month: Int,
    data: SnapshotStateMap<String, DayRecord>,
    today: LocalDate,
    onDayClick: (String) -> Unit
) {
    val ym = YearMonth.of(YEAR, month)
    val len = ym.lengthOfMonth()
    val leading = (ym.atDay(1).dayOfWeek.value + 6) % 7 // Monday = 0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                MONTHS[month - 1],
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                DOW.forEach { d ->
                    Text(
                        d,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val cells = ArrayList<Int>()
            repeat(leading) { cells.add(0) }
            for (d in 1..len) cells.add(d)
            while (cells.size % 7 != 0) cells.add(0)

            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        Box(Modifier.weight(1f)) {
                            if (day == 0) {
                                Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                            } else {
                                val key = keyOf(month, day)
                                DayCell(
                                    day = day,
                                    rec = data[key],
                                    isToday = today.year == YEAR &&
                                        today.monthValue == month && today.dayOfMonth == day,
                                    onClick = { onDayClick(key) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, rec: DayRecord?, isToday: Boolean, onClick: () -> Unit) {
    val logged = rec != null && !rec.isEmpty
    val base = Modifier
        .padding(2.dp)
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(RoundedCornerShape(10.dp))
        .background(if (logged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
    val withBorder = if (isToday)
        base.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else base

    Column(
        modifier = withBorder.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Day number pinned at the top.
        Text(
            "$day",
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        // Icon sits right under the number. includeFontPadding=false removes the
        // phantom top space emoji otherwise get, so it reads high instead of low.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            // Icons per the rules: alcohol-yes and run/walk only. No icon for alcohol-no or rest.
            val icons = buildString {
                if (rec?.alcohol == "yes") append("🍺")
                when (rec?.activity) {
                    "run" -> append("🏃")
                    "walk" -> append("🚶")
                }
            }
            if (icons.isNotEmpty()) {
                Text(
                    icons,
                    modifier = Modifier.padding(top = 1.dp),
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            } else if (rec?.activity == "rest") {
                // logged rest day: a small dot, no icon
                Box(Modifier.padding(top = 2.dp).size(7.dp).clip(CircleShape).background(CRest))
            }
        }
    }
}

@Composable
private fun EditDialog(
    dateLabel: String,
    record: DayRecord,
    onConfirm: (DayRecord) -> Unit,
    onClear: () -> Unit,
    onShowDetail: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val drinkCounts = remember { mutableStateMapOf<String, Int>().apply { record.drinks?.let { putAll(it) } } }
    var sober by remember { mutableStateOf(record.alcohol == "no") }
    var activity by remember { mutableStateOf(record.activity) }
    var kmText by remember { mutableStateOf(record.distanceKm?.let { fmtKm(it) } ?: "") }
    var minText by remember { mutableStateOf(record.durationMin?.toString() ?: "") }
    val liveUnits = drinkCounts.entries.sumOf { (k, c) -> (DRINK_BY_KEY[k]?.units ?: 0.0) * c }
    val liveAlcKcal = drinkCounts.entries.sumOf { (k, c) -> (DRINK_BY_KEY[k]?.kcal ?: 0) * c }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registra giornata") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

                if (onShowDetail != null) {
                    TextButton(onClick = onShowDetail, contentPadding = PaddingValues(vertical = 0.dp)) {
                        Text("📋 Dettaglio attività  ▸")
                    }
                }

                // Calorie balance: burned by activity vs consumed with alcohol.
                val burned = record.calories ?: 0
                if (burned > 0 || liveAlcKcal > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text("🔥 $burned", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = CRun)
                            Text("bruciate (attività)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column {
                            Text("🍺 $liveAlcKcal", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = CAlcohol)
                            Text("assunte (alcol)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                SectionLabel("ALCOOL")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("🚱 Niente oggi", sober && drinkCounts.values.sum() == 0, CWater) {
                        sober = !sober
                        if (sober) drinkCounts.clear()
                    }
                }
                DRINKS.forEach { dr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dr.label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(
                            onClick = {
                                val c = drinkCounts[dr.key] ?: 0
                                if (c > 0) { if (c - 1 == 0) drinkCounts.remove(dr.key) else drinkCounts[dr.key] = c - 1 }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) { Text("−", fontSize = 20.sp) }
                        Text(
                            "${drinkCounts[dr.key] ?: 0}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = if ((drinkCounts[dr.key] ?: 0) > 0) CAlcohol else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp)
                        )
                        TextButton(
                            onClick = { drinkCounts[dr.key] = (drinkCounts[dr.key] ?: 0) + 1; sober = false },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) { Text("+", fontSize = 20.sp) }
                    }
                }
                if (liveUnits > 0) {
                    Text(
                        "Totale: ${fmtUnits(liveUnits)} unità  ·  ~$liveAlcKcal kcal",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CAlcohol
                    )
                }

                Spacer(Modifier.height(4.dp))
                SectionLabel("ATTIVITÀ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("🏃 Corsa", activity == "run", CRun) {
                        activity = if (activity == "run") null else "run"
                    }
                    ChoiceChip("🚶 Camminata", activity == "walk", CWalk) {
                        activity = if (activity == "walk") null else "walk"
                    }
                    ChoiceChip("😴 Riposo", activity == "rest", CRest) {
                        activity = if (activity == "rest") null else "rest"
                    }
                }

                // Distance/duration only make sense for run or walk.
                if (activity == "run" || activity == "walk") {
                    Spacer(Modifier.height(4.dp))
                    SectionLabel("DISTANZA E DURATA")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = kmText,
                            onValueChange = { kmText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text("km") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it.filter { c -> c.isDigit() } },
                            label = { Text("minuti") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Read-only extras imported from Garmin (calories / heart rate / pace).
                val garmin = buildList {
                    record.calories?.let { add("🔥 $it kcal") }
                    record.avgHr?.let { add("❤️ $it bpm") }
                    fmtPace(record.distanceKm ?: 0.0, record.durationMin ?: 0)?.let { add("⏩ $it") }
                    record.elevationM?.let { if (it > 0) add("⛰️ $it m") }
                }
                if (garmin.isNotEmpty()) {
                    Text(
                        "Da Garmin:  ${garmin.joinToString("   ")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val withMetrics = activity == "run" || activity == "walk"
                val km = if (withMetrics) kmText.replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 } else null
                val min = if (withMetrics) minText.toIntOrNull()?.takeIf { it > 0 } else null
                val drinksMap = drinkCounts.filterValues { it > 0 }.toMap()
                val alcoholState = when {
                    drinksMap.isNotEmpty() -> "yes"
                    sober -> "no"
                    else -> record.alcohol
                }
                onConfirm(
                    record.copy(
                        alcohol = alcoholState,
                        drinks = drinksMap.ifEmpty { null },
                        alcoholUnits = if (drinksMap.isNotEmpty()) null else record.alcoholUnits,
                        activity = activity,
                        distanceKm = if (withMetrics) km else null,
                        durationMin = if (withMetrics) min else null,
                        calories = if (withMetrics) record.calories else null,
                        avgHr = if (withMetrics) record.avgHr else null,
                        elevationM = if (withMetrics) record.elevationM else null
                    )
                )
            }) { Text("Fatto") }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text("Svuota") }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.18f),
            selectedLabelColor = accent
        )
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun HeatmapScreen(
    data: SnapshotStateMap<String, DayRecord>,
    onClose: () -> Unit,
    onEditDay: (String) -> Unit
) {
    var mode by remember { mutableStateOf(0) } // 0 = alcool, 1 = attività
    val gutter = 30.dp

    val jan1 = LocalDate.of(YEAR, 1, 1)
    val dec31 = LocalDate.of(YEAR, 12, 31)
    val gridStart = jan1.minusDays(((jan1.dayOfWeek.value + 6) % 7).toLong())
    val gridEnd = dec31.plusDays(((7 - dec31.dayOfWeek.value) % 7).toLong())
    val weeks = remember { weekStarts(gridStart, gridEnd) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) { Text("‹ Indietro") }
                    Spacer(Modifier.width(4.dp))
                    Text("🗓️ Anno $YEAR", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Alcool") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Attività") })
                }
                HeatmapLegend(mode)
                // Day-of-week header aligned with the grid (left month gutter first).
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Spacer(Modifier.width(gutter))
                    DOW.forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(weeks.size) { wi ->
                        val monday = weeks[wi]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val label = monthLabelForWeek(monday)
                            Box(Modifier.width(gutter)) {
                                if (label != null) {
                                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            for (i in 0..6) {
                                val date = monday.plusDays(i.toLong())
                                val inYear = date.year == YEAR
                                val bg = cellColor(if (inYear) data[keyOfDate(date)] else null, inYear, mode)
                                Box(
                                    Modifier.weight(1f).padding(1.dp).aspectRatio(1f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(bg)
                                        .clickable(enabled = inYear) { onEditDay(keyOfDate(date)) }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HeatmapLegend(mode: Int) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (mode == 0) {
            Swatch(CWater, "Senza alcool")
            Swatch(CAlcohol, "Alcool")
        } else {
            Swatch(CRun, "Corsa")
            Swatch(CWalk, "Camminata")
            Swatch(CRest, "Riposo")
        }
        Swatch(MaterialTheme.colorScheme.surfaceVariant, "Non segnato")
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun cellColor(rec: DayRecord?, inYear: Boolean, mode: Int): Color {
    if (!inYear) return Color.Transparent
    val empty = MaterialTheme.colorScheme.surfaceVariant
    if (rec == null) return empty
    return if (mode == 0) {
        when (rec.alcohol) { "no" -> CWater; "yes" -> CAlcohol; else -> empty }
    } else {
        when (rec.activity) { "run" -> CRun; "walk" -> CWalk; "rest" -> CRest; else -> empty }
    }
}

@Composable
private fun ActivityDetailScreen(
    dateLabel: String,
    loading: Boolean,
    detail: ActivityDetail?,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) { Text("‹ Indietro") }
                    Spacer(Modifier.width(4.dp))
                    Text("Dettaglio attività", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nessuna attività Garmin per questa giornata.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> ActivityDetailBody(dateLabel, detail)
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailBody(dateLabel: String, dt: ActivityDetail) {
    val avgSpeed = if (dt.durationSec > 0) dt.km / (dt.durationSec / 3600.0) else 0.0
    val avgPaceSec = if (dt.km > 0) (dt.durationSec / dt.km).roundToInt() else 0
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "${if (dt.activity == "run") "🏃 Corsa" else "🚶 Camminata"} · $dateLabel",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text(fmtKm(dt.km), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = CRun)
                            Text("km", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column {
                            Text(fmtHms(dt.durationSec), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("tempo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (avgPaceSec > 0) StatCell("Passo medio", fmtPaceSec(avgPaceSec), CWater)
                    dt.bestPaceSecPerKm?.let { StatCell("Passo migliore", fmtPaceSec(it), CWater) }
                    StatCell("Vel. media", "%.1f km/h".format(avgSpeed), CWalk)
                    dt.maxSpeedKmh?.let { StatCell("Vel. max", "%.1f km/h".format(it), CWalk) }
                    dt.avgHr?.let { StatCell("FC media", "$it bpm", CHeart) }
                    dt.maxHr?.let { StatCell("FC max", "$it bpm", CHeart) }
                    dt.minHr?.let { StatCell("FC min", "$it bpm", CHeart) }
                    if (dt.calories > 0) StatCell("Calorie", "${dt.calories} kcal", CAlcohol)
                    if (dt.elevationM > 0) StatCell("Dislivello", "${dt.elevationM} m", CElev)
                }
            }
        }
        if (dt.zonesSec.any { it > 0 }) {
            item { SectionTitle("Zone di frequenza cardiaca") }
            item { HrZones(dt.zonesSec) }
        }
        if (dt.hrSeries.isNotEmpty()) {
            item { SectionTitle("Frequenza cardiaca nel tempo") }
            item { MiniBars(dt.hrSeries, CHeart) }
        }
        if (dt.splits.isNotEmpty()) {
            item { SectionTitle("Tempi per tratto") }
            item { SplitsTable(dt.splits) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column(Modifier.width(104.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HrZones(zonesSec: List<Int>) {
    val colors = listOf(CWater, CWalk, CAlcohol, CRun, CHeart)
    val names = listOf("Z1", "Z2", "Z3", "Z4", "Z5")
    val maxZ = (zonesSec.maxOrNull() ?: 0).coerceAtLeast(1)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..4) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(names[i], fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors[i], modifier = Modifier.width(24.dp))
                    Box(
                        Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(zonesSec[i].toFloat() / maxZ)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors[i])
                        )
                    }
                    Text(
                        fmtHms(zonesSec[i]),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBars(values: List<Int>, color: Color) {
    if (values.isEmpty()) return
    val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(1)
    val minV = values.minOrNull() ?: 0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                values.forEach { v ->
                    val frac = if (maxV > minV) (v - minV).toFloat() / (maxV - minV) else 1f
                    Box(
                        Modifier.weight(1f)
                            .height((6f + frac * 70f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("min $minV · max $maxV bpm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SplitsTable(splits: List<ActivitySplit>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                SplitCell("Tratto", 0.9f, bold = true)
                SplitCell("Tempo", 1f, bold = true)
                SplitCell("km", 1f, bold = true)
                SplitCell("Passo", 1f, bold = true)
                SplitCell("FC", 0.8f, bold = true)
            }
            splits.forEach { sp ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    SplitCell(sp.label, 0.9f)
                    SplitCell(fmtHms(sp.seconds), 1f)
                    SplitCell(fmtKm(sp.km), 1f)
                    SplitCell(sp.paceText, 1f)
                    SplitCell(sp.avgHr?.toString() ?: "—", 0.8f)
                }
            }
        }
    }
}

@Composable
private fun RowScope.SplitCell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        fontSize = 12.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (bold) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    )
}

private fun fmtHms(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fmtPaceSec(secPerKm: Int): String =
    "%d'%02d\"/km".format(secPerKm / 60, secPerKm % 60)

@Composable
private fun StatsScreen(
    data: SnapshotStateMap<String, DayRecord>,
    onClose: () -> Unit
) {
    val streaks = alcoholFreeStreaks(data)
    val months = monthlyStats(data)
    val weeks = weeklyStats(data)

    val kmPts = trendPoints(data,
        { it.distanceKm?.toFloat()?.takeIf { v -> v > 0f } },
        { fmtKm(it.distanceKm ?: 0.0) })
    val pacePts = trendPoints(data,
        { r -> if ((r.distanceKm ?: 0.0) > 0.0 && (r.durationMin ?: 0) > 0) r.durationMin!!.toFloat() / r.distanceKm!!.toFloat() else null },
        { r -> fmtPace(r.distanceKm ?: 0.0, r.durationMin ?: 0) ?: "" })
    val hrPts = trendPoints(data, { it.avgHr?.toFloat() }, { "${it.avgHr}" })
    val elevPts = trendPoints(data,
        { it.elevationM?.toFloat()?.takeIf { v -> v > 0f } },
        { "${it.elevationM}" })
    val anyTrend = kmPts.isNotEmpty() || pacePts.isNotEmpty() || hrPts.isNotEmpty() || elevPts.isNotEmpty()

    val alcWeekPts = weeks.map { Triple(it.label.substringBefore(" –").trim(), it.alcoholYes.toFloat(), "${it.alcoholYes}") }
    val showAlcChart = weeks.any { it.alcoholYes > 0 }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) { Text("‹ Indietro") }
                    Spacer(Modifier.width(4.dp))
                    Text("📊 Statistiche", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { StreakCard(streaks) }
                    item { SectionTitle("Alcool") }
                    item { AlcoholSummaryCard(data) }
                    if (showAlcChart) item { TrendChart("Giorni con alcol / settimana", CAlcohol, alcWeekPts) }
                    item { AlcoholInsightCard(data) }
                    if (anyTrend) item { SectionTitle("Andamento") }
                    if (kmPts.isNotEmpty()) item { TrendChart("Distanza (km)", CRun, kmPts) }
                    if (pacePts.isNotEmpty()) item { TrendChart("Passo (min/km)", CWater, pacePts) }
                    if (hrPts.isNotEmpty()) item { TrendChart("Frequenza cardiaca (bpm)", CHeart, hrPts) }
                    if (elevPts.isNotEmpty()) item { TrendChart("Dislivello (m)", CElev, elevPts) }
                    item { SectionTitle("Per mese") }
                    if (months.isEmpty()) item { EmptyHint() }
                    items(months.size) { i -> PeriodCard(months[i]) }
                    item { SectionTitle("Per settimana") }
                    if (weeks.isEmpty()) item { EmptyHint() }
                    items(weeks.size) { i -> PeriodCard(weeks[i]) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TrendChart(title: String, color: Color, points: List<Triple<String, Float, String>>) {
    if (points.isEmpty()) return
    val maxV = points.maxOf { it.second }.coerceAtLeast(0.0001f)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEach { (date, v, vtext) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vtext, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.width(22.dp)
                                .height((6f + (v / maxV) * 84f).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(date, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Horizontal balance: burned (activity) vs consumed (alcohol). The divider leans
 *  toward the smaller value — the bigger side fills more of the bar. */
@Composable
private fun CalorieBalanceBar(burned: Int, consumed: Int) {
    Row(
        Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (burned > 0) Box(Modifier.weight(burned.toFloat()).fillMaxHeight().background(CRun))
        if (consumed > 0) Box(Modifier.weight(consumed.toFloat()).fillMaxHeight().background(CAlcohol))
    }
}

/** Prominent current-month calorie balance for the home screen. */
@Composable
private fun MonthBalanceCard(data: SnapshotStateMap<String, DayRecord>) {
    val today = LocalDate.now()
    val prefix = "%04d-%02d".format(today.year, today.monthValue)
    var burned = 0
    var consumed = 0
    data.forEach { (k, v) ->
        if (k.startsWith(prefix)) { burned += v.calories ?: 0; consumed += alcoholKcalOf(v) }
    }
    if (burned == 0 && consumed == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "📅 ${MONTHS[today.monthValue - 1]} — bilancio calorie",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🔥 $burned bruciate", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CRun)
                Text("🍺 $consumed assunte", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CAlcohol)
            }
            CalorieBalanceBar(burned, consumed)
        }
    }
}

@Composable
private fun AlcoholSummaryCard(data: SnapshotStateMap<String, DayRecord>) {
    val week = currentWeekAlcoholDays(data)
    val goal = ALCOHOL_WEEKLY_GOAL
    val sober = soberPercent(data)
    val units = totalAlcoholUnits(data)
    val kcal = totalAlcoholKcal(data)
    val overGoal = week > goal
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Questa settimana: $week/$goal giorni con alcol",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (overGoal) CRun else MaterialTheme.colorScheme.onSurface
            )
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth((week.toFloat() / goal).coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (overGoal) CRun else CWalk)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sober?.let { Text("🚱 $it% giorni sobri", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (units > 0) Text("🍺 ${fmtUnits(units)} unità", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (kcal > 0) Text("🔥 ~$kcal kcal da alcol", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AlcoholInsightCard(data: SnapshotStateMap<String, DayRecord>) {
    val ins = alcoholActivityInsight(data)
    if (!ins.hasData) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Alcol e prestazioni", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Allenamenti nel giorno dopo aver bevuto vs dopo un giorno sobrio.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (ins.afterDrinkPace != null && ins.afterSoberPace != null) {
                Text(
                    "⏩ Passo — dopo alcol ${fmtPaceSec(ins.afterDrinkPace)} · sobrio ${fmtPaceSec(ins.afterSoberPace)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (ins.afterDrinkHr != null && ins.afterSoberHr != null) {
                Text(
                    "❤️ FC media — dopo alcol ${ins.afterDrinkHr} bpm · sobrio ${ins.afterSoberHr} bpm",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StreakCard(s: Streaks) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("🔥 ${s.current}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = CWater)
                Text("Serie senza alcool (giorni)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text("🏆 ${s.longest}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Record", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun EmptyHint() {
    Text(
        "Nessun dato ancora.",
        modifier = Modifier.padding(4.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PeriodCard(s: PeriodStats) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${s.label} · ${s.total} ${if (s.total == 1) "giorno" else "giorni"}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            StackedBar(listOf(s.alcoholNo to CWater, s.alcoholYes to CAlcohol))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniLegend("💧", s.alcoholNo)
                MiniLegend("🍺", s.alcoholYes)
            }
            Spacer(Modifier.height(2.dp))
            StackedBar(listOf(s.run to CRun, s.walk to CWalk, s.rest to CRest))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniLegend("🏃", s.run)
                MiniLegend("🚶", s.walk)
                MiniLegend("😴", s.rest)
            }
            val metrics = buildList {
                if (s.totalKm > 0.0) add("📏 ${fmtKm(s.totalKm)} km")
                if (s.totalMin > 0) add("⏱ ${s.totalMin} min")
                fmtPace(s.totalKm, s.totalMin)?.let { add("⏩ $it") }
                if (s.totalCalories > 0) add("🔥 ${s.totalCalories} kcal")
                s.avgHr?.let { add("❤️ $it bpm") }
                if (s.totalElevation > 0) add("⛰️ ${s.totalElevation} m")
            }
            if (metrics.isNotEmpty()) {
                Text(
                    metrics.joinToString("    "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (s.totalCalories > 0 || s.alcoholKcal > 0) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🔥 ${s.totalCalories} kcal", fontSize = 11.sp, color = CRun)
                    Text("🍺 ${s.alcoholKcal} kcal", fontSize = 11.sp, color = CAlcohol)
                }
                CalorieBalanceBar(s.totalCalories, s.alcoholKcal)
            }
        }
    }
}

private fun fmtKm(km: Double): String =
    if (km == km.toLong().toDouble()) km.toLong().toString() else "%.1f".format(km)

/** Average pace as m'ss"/km, or null if not computable. */
private fun fmtPace(km: Double, minutes: Int): String? {
    if (km <= 0.0 || minutes <= 0) return null
    val paceMinPerKm = minutes / km
    var m = paceMinPerKm.toInt()
    var s = ((paceMinPerKm - m) * 60).roundToInt()
    if (s == 60) { m += 1; s = 0 }
    return "%d'%02d\"/km".format(m, s)
}

@Composable
private fun MiniLegend(emoji: String, n: Int) {
    Text("$emoji $n", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StackedBar(segments: List<Pair<Int, Color>>) {
    val total = segments.sumOf { it.first }
    Row(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))
    ) {
        if (total == 0) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
        } else {
            segments.forEach { (v, c) ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c))
            }
        }
    }
}

@Composable
private fun FilterScreen(
    filter: FilterType,
    data: SnapshotStateMap<String, DayRecord>,
    onClose: () -> Unit
) {
    val keys = data.entries.filter { filter.matches(it.value) }.map { it.key }.sorted()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) { Text("‹ Indietro") }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            "${filter.emoji} ${filter.label}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = filter.color
                        )
                        Text(
                            if (keys.size == 1) "1 giorno" else "${keys.size} giorni",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (keys.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nessun giorno registrato", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(keys.size) { i ->
                            val key = keys[i]
                            val rec = data[key]!!
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        labelForFull(key),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        describe(rec),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(rec: DayRecord): String {
    val parts = mutableListOf<String>()
    when (rec.alcohol) {
        "yes" -> {
            val u = unitsOf(rec)
            parts.add("🍺 Alcool" + (if (u > 0) " (${fmtUnits(u)} u.)" else ""))
        }
        "no" -> parts.add("💧 Niente alcool")
    }
    when (rec.activity) {
        "run" -> parts.add("🏃 Corsa")
        "walk" -> parts.add("🚶 Camminata")
        "rest" -> parts.add("😴 Riposo")
    }
    val metrics = buildList {
        rec.distanceKm?.let { if (it > 0) add("${fmtKm(it)} km") }
        rec.durationMin?.let { if (it > 0) add("$it min") }
        fmtPace(rec.distanceKm ?: 0.0, rec.durationMin ?: 0)?.let { add(it) }
        rec.calories?.let { if (it > 0) add("$it kcal") }
        rec.avgHr?.let { add("$it bpm") }
        rec.elevationM?.let { if (it > 0) add("$it m D+") }
    }
    if (metrics.isNotEmpty()) parts.add("(${metrics.joinToString(", ")})")
    return if (parts.isEmpty()) "—" else parts.joinToString("    ")
}

private val WEEKDAYS = arrayOf(
    "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica"
)

private val MONTHS_ABBR = arrayOf(
    "Gen", "Feb", "Mar", "Apr", "Mag", "Giu", "Lug", "Ago", "Set", "Ott", "Nov", "Dic"
)

/** Serie di giorni consecutivi senza alcool (alcohol == "no"). Un giorno con
 *  alcool o non registrato interrompe la serie. */
internal fun alcoholFreeStreaks(data: Map<String, DayRecord>): Streaks {
    val cleanDates = data.entries
        .filter { it.value.alcohol == "no" }
        .map { LocalDate.parse(it.key) }
        .sorted()
    if (cleanDates.isEmpty()) return Streaks(0, 0)

    var longest = 1
    var run = 1
    for (i in 1 until cleanDates.size) {
        run = if (cleanDates[i] == cleanDates[i - 1].plusDays(1)) run + 1 else 1
        if (run > longest) longest = run
    }

    var current = 1
    for (i in cleanDates.size - 1 downTo 1) {
        if (cleanDates[i] == cleanDates[i - 1].plusDays(1)) current++ else break
    }
    return Streaks(current, longest)
}

private fun currentWeekAlcoholDays(data: Map<String, DayRecord>): Int {
    val today = LocalDate.now()
    val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
    val sunday = monday.plusDays(6)
    return data.entries.count { e ->
        val d = LocalDate.parse(e.key)
        !d.isBefore(monday) && !d.isAfter(sunday) && e.value.alcohol == "yes"
    }
}

private fun soberPercent(data: Map<String, DayRecord>): Int? {
    var yes = 0; var no = 0
    data.values.forEach { when (it.alcohol) { "yes" -> yes++; "no" -> no++ } }
    val tot = yes + no
    return if (tot == 0) null else no * 100 / tot
}

private fun totalAlcoholUnits(data: Map<String, DayRecord>): Double =
    data.values.sumOf { unitsOf(it) }

private fun totalAlcoholKcal(data: Map<String, DayRecord>): Int =
    data.values.sumOf { alcoholKcalOf(it) }

data class AlcoholInsight(
    val afterDrinkPace: Int?, val afterSoberPace: Int?,
    val afterDrinkHr: Int?, val afterSoberHr: Int?,
    val hasData: Boolean
)

/** Compares activity days by whether the PREVIOUS day had alcohol. */
private fun alcoholActivityInsight(data: Map<String, DayRecord>): AlcoholInsight {
    var dPaceSum = 0.0; var dPaceN = 0; var sPaceSum = 0.0; var sPaceN = 0
    var dHrSum = 0; var dHrN = 0; var sHrSum = 0; var sHrN = 0
    for ((key, rec) in data) {
        val prev = LocalDate.parse(key).minusDays(1)
        val prevKey = "%04d-%02d-%02d".format(prev.year, prev.monthValue, prev.dayOfMonth)
        val prevAlc = data[prevKey]?.alcohol ?: continue
        val afterDrink = prevAlc == "yes"
        val km = rec.distanceKm ?: 0.0
        val dm = rec.durationMin ?: 0
        if (km > 0 && dm > 0) {
            val pace = dm * 60.0 / km
            if (afterDrink) { dPaceSum += pace; dPaceN++ } else { sPaceSum += pace; sPaceN++ }
        }
        rec.avgHr?.let {
            if (afterDrink) { dHrSum += it; dHrN++ } else { sHrSum += it; sHrN++ }
        }
    }
    val hasData = (dPaceN > 0 && sPaceN > 0) || (dHrN > 0 && sHrN > 0)
    return AlcoholInsight(
        afterDrinkPace = if (dPaceN > 0) (dPaceSum / dPaceN).roundToInt() else null,
        afterSoberPace = if (sPaceN > 0) (sPaceSum / sPaceN).roundToInt() else null,
        afterDrinkHr = if (dHrN > 0) dHrSum / dHrN else null,
        afterSoberHr = if (sHrN > 0) sHrSum / sHrN else null,
        hasData = hasData
    )
}

/** Chronological data points for a trend chart: (short date label, bar value, value text). */
private fun trendPoints(
    data: Map<String, DayRecord>,
    value: (DayRecord) -> Float?,
    text: (DayRecord) -> String
): List<Triple<String, Float, String>> =
    data.entries
        .mapNotNull { e -> value(e.value)?.let { v -> Triple(LocalDate.parse(e.key), v, text(e.value)) } }
        .sortedBy { it.first }
        .map { (d, v, t) -> Triple("${d.dayOfMonth}/${d.monthValue}", v, t) }

private fun statsFrom(label: String, recs: Collection<DayRecord>): PeriodStats {
    var ay = 0; var an = 0; var r = 0; var w = 0; var rest = 0
    var km = 0.0; var min = 0; var kcal = 0; var elev = 0; var units = 0; var akcal = 0
    var hrWeighted = 0.0; var hrMin = 0
    recs.forEach {
        when (it.alcohol) { "yes" -> ay++; "no" -> an++ }
        when (it.activity) { "run" -> r++; "walk" -> w++; "rest" -> rest++ }
        km += it.distanceKm ?: 0.0
        min += it.durationMin ?: 0
        kcal += it.calories ?: 0
        elev += it.elevationM ?: 0
        units += unitsOf(it).roundToInt()
        akcal += alcoholKcalOf(it)
        val hr = it.avgHr
        val dm = it.durationMin ?: 0
        if (hr != null && dm > 0) { hrWeighted += hr.toDouble() * dm; hrMin += dm }
    }
    val avgHr = if (hrMin > 0) (hrWeighted / hrMin).roundToInt() else null
    return PeriodStats(label, recs.size, ay, an, r, w, rest, km, min, kcal, avgHr, elev, units, akcal)
}

private fun monthlyStats(data: Map<String, DayRecord>): List<PeriodStats> {
    val result = ArrayList<PeriodStats>()
    for (m in 1..12) {
        val prefix = "%04d-%02d".format(YEAR, m)
        val recs = data.filterKeys { it.startsWith(prefix) }.values
        if (recs.isNotEmpty()) result.add(statsFrom(MONTHS[m - 1], recs))
    }
    return result
}

private fun weeklyStats(data: Map<String, DayRecord>): List<PeriodStats> {
    val byWeek = LinkedHashMap<LocalDate, MutableList<DayRecord>>()
    data.entries
        .map { LocalDate.parse(it.key) to it.value }
        .sortedBy { it.first }
        .forEach { (date, rec) ->
            val monday = date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())
            byWeek.getOrPut(monday) { mutableListOf() }.add(rec)
        }
    return byWeek.map { (monday, recs) ->
        val sunday = monday.plusDays(6)
        val label = "${monday.dayOfMonth} ${MONTHS_ABBR[monday.monthValue - 1]} – " +
            "${sunday.dayOfMonth} ${MONTHS_ABBR[sunday.monthValue - 1]}"
        statsFrom(label, recs)
    }
}

private fun labelForFull(key: String): String {
    val d = LocalDate.parse(key)
    return "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
}

private fun keyOf(month: Int, day: Int): String =
    "%04d-%02d-%02d".format(YEAR, month, day)

private fun keyOfDate(d: LocalDate): String =
    "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

private fun weekStarts(start: LocalDate, end: LocalDate): List<LocalDate> {
    val list = ArrayList<LocalDate>()
    var d = start
    while (!d.isAfter(end)) {
        list.add(d)
        d = d.plusDays(7)
    }
    return list
}

/** Etichetta mese per la riga-settimana che contiene il 1° del mese. */
private fun monthLabelForWeek(monday: LocalDate): String? {
    for (i in 0..6) {
        val d = monday.plusDays(i.toLong())
        if (d.year == YEAR && d.dayOfMonth == 1) return MONTHS_ABBR[d.monthValue - 1]
    }
    return null
}

private fun labelFor(key: String): String {
    val parts = key.split("-")
    val month = parts[1].toInt()
    val day = parts[2].toInt()
    return "$day ${MONTHS[month - 1]} $YEAR"
}
