@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.davide.diario

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

const val YEAR = 2026

@Serializable
data class DayRecord(val alcohol: String? = null, val activity: String? = null) {
    val isEmpty: Boolean get() = alcohol == null && activity == null
}

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

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    Toast.makeText(context, "Dati ripristinati (${restored.size} giorni)", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "File di backup non valido", Toast.LENGTH_SHORT).show()
                }
            }
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
                editingKey = null
            },
            onClear = {
                data.remove(key)
                store.save(data)
                editingKey = null
            },
            onDismiss = { editingKey = null }
        )
    }

    filter?.let { f ->
        FilterScreen(filter = f, data = data, onClose = { filter = null })
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
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        // Icon just below the number with a small gap: a middle ground between
        // dead-centre (overlapped the number) and centred-in-lower-space (too low).
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
                Text(icons, modifier = Modifier.padding(top = 4.dp), fontSize = 13.sp)
            } else if (rec?.activity == "rest") {
                // logged rest day: a small dot, no icon
                Box(Modifier.padding(top = 4.dp).size(7.dp).clip(CircleShape).background(CRest))
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
    onDismiss: () -> Unit
) {
    var alcohol by remember { mutableStateOf(record.alcohol) }
    var activity by remember { mutableStateOf(record.activity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registra giornata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

                Spacer(Modifier.height(4.dp))
                SectionLabel("ALCOOL")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("🍺 Sì", alcohol == "yes", CAlcohol) {
                        alcohol = if (alcohol == "yes") null else "yes"
                    }
                    ChoiceChip("No", alcohol == "no", CWater) {
                        alcohol = if (alcohol == "no") null else "no"
                    }
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(DayRecord(alcohol, activity)) }) { Text("Fatto") }
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
        "yes" -> parts.add("🍺 Alcool")
        "no" -> parts.add("💧 Niente alcool")
    }
    when (rec.activity) {
        "run" -> parts.add("🏃 Corsa")
        "walk" -> parts.add("🚶 Camminata")
        "rest" -> parts.add("😴 Riposo")
    }
    return if (parts.isEmpty()) "—" else parts.joinToString("    ")
}

private val WEEKDAYS = arrayOf(
    "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica"
)

private fun labelForFull(key: String): String {
    val d = LocalDate.parse(key)
    return "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
}

private fun keyOf(month: Int, day: Int): String =
    "%04d-%02d-%02d".format(YEAR, month, day)

private fun labelFor(key: String): String {
    val parts = key.split("-")
    val month = parts[1].toInt()
    val day = parts[2].toInt()
    return "$day ${MONTHS[month - 1]} $YEAR"
}
