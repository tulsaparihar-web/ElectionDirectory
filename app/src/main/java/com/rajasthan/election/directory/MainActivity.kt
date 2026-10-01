package com.rajasthan.election.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "officers")
data class Officer(
    @PrimaryKey val id: Int,
    val officerName: String,
    val designation: String,
    val officeDepartment: String,
    val district: String,
    val subLocation: String = "",
    val sectionCell: String = "",
    val employeeId: String = "",
    val dob: String = "",
    val contactNumbers: String = "",
    val email: String = "",
    val remark: String = "",
    val isFavorite: Boolean = false,
    val seniorityOrder: Int = DEFAULT_SENIORITY_ORDER
) {
    fun phones(): List<String> = contactNumbers.split("|").map { it.trim() }.filter { it.isNotBlank() }
    fun mobile(): String? = phones().firstOrNull { it.filter(Char::isDigit).length >= 10 }
    fun officeNumbers(): List<String> = phones().filter { it != mobile() }
    fun locationLabel(): String = listOf(subLocation, district)
        .filter { it.isNotBlank() }.joinToString(", ")
}

@Dao
interface OfficerDao {
    @Query("SELECT * FROM officers ORDER BY officerName COLLATE NOCASE")
    fun observeAll(): Flow<List<Officer>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Officer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Officer>)
    @Query("DELETE FROM officers")
    suspend fun deleteAll()
    @Query("UPDATE officers SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Int, favorite: Boolean)
}

@Database(entities = [Officer::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun officerDao(): OfficerDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE officers ADD COLUMN sectionCell TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE officers ADD COLUMN employeeId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE officers ADD COLUMN dob TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE officers ADD COLUMN seniorityOrder INTEGER NOT NULL DEFAULT $DEFAULT_SENIORITY_ORDER")
    }
}

class OfficerRepository(private val dao: OfficerDao) {
    val officers = dao.observeAll()
    suspend fun setFavorite(id: Int, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun replace(items: List<Officer>) { dao.deleteAll(); dao.insertAll(items) }
    suspend fun upsert(items: List<Officer>) { dao.upsertAll(items) }
}

enum class AppTab(val label: String) { HOME("Home"), DIRECTORY("Directory"), OFFICES("Offices"), BIRTHDAYS("Birthdays"), MORE("More") }

data class DirectoryUiState(
    val query: String = "",
    // Staff tab opens directly on Jaipur contacts; users can still choose "All" from the District filter.
    val district: String = "Jaipur",
    val designation: String = "All",
    val sectionCell: String = "All",
    val tab: AppTab = AppTab.HOME,
    val darkMode: Boolean = false
)

class DirectoryViewModel(private val repo: OfficerRepository) : ViewModel() {
    suspend fun syncFromOfficialDirectory(): Int {
        val items = fetchOfficialDirectory()
        if (items.isEmpty()) throw IllegalStateException("The official directory returned no usable records.")
        repo.replace(items)
        return items.size
    }
    val officers = repo.officers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _state = MutableStateFlow(DirectoryUiState())
    val state = _state.asStateFlow()

    val districts: StateFlow<List<String>> = officers.map { list ->
        listOf("All") + list.flatMap { it.district.split(",").map(String::trim) }
            .filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("All"))

    val designations: StateFlow<List<String>> = officers.map { list ->
        listOf("All") + list.map { it.designation }.filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("All"))

    val sectionCells: StateFlow<List<String>> = officers.map { list ->
        listOf("All") + list.map { it.sectionCell }.filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("All"))

    val filtered: StateFlow<List<Officer>> = combine(officers, state) { all, s ->
        all.filter { o ->
            val q = s.query.trim().lowercase(Locale.getDefault())
            val searchable = listOf(o.officerName, o.designation, o.officeDepartment, o.district,
                o.subLocation, o.sectionCell, o.employeeId, o.dob, o.email, o.contactNumbers, o.remark)
            val matchesQuery = q.isBlank() || searchable.any { it.lowercase(Locale.getDefault()).contains(q) }
            val matchesDistrict = s.district == "All" || o.district.split(",").any { it.trim().equals(s.district, true) }
            val matchesDesignation = s.designation == "All" || o.designation.equals(s.designation, true)
            val matchesSection = s.sectionCell == "All" || o.sectionCell.equals(s.sectionCell, true)
            matchesQuery && matchesDistrict && matchesDesignation && matchesSection
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    fun setQuery(v: String) { _state.update { it.copy(query = v) } }
    fun setDistrict(v: String) { _state.update { it.copy(district = v) } }
    fun setDesignation(v: String) { _state.update { it.copy(designation = v) } }
    fun setSectionCell(v: String) { _state.update { it.copy(sectionCell = v) } }
    fun setTab(v: AppTab) { _state.update { it.copy(tab = v) } }
    fun clearFilters() { _state.update { it.copy(query = "", district = "All", designation = "All", sectionCell = "All") } }
    fun clearFilterOnly() { _state.update { it.copy(query = "", district = "Jaipur", designation = "All", sectionCell = "All") } }
    fun setDarkMode(v: Boolean) { _state.update { it.copy(darkMode = v) } }
    fun replaceData(items: List<Officer>) = viewModelScope.launch { repo.replace(items) }
}

private val Navy = Color(0xFF173B7A)
private val NavyDark = Color(0xFF0B2857)
private val Gold = Color(0xFFF4A000)
private val Green = Color(0xFF198754)
private val Background = Color(0xFFF4F6F9)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val Border = Color(0xFFD9E0EA)
private val TextDark = Color(0xFF182235)
private val TextMuted = Color(0xFF667085)

private const val DEFAULT_SENIORITY_ORDER = 999999
private const val DIRECTORY_SYNC_URL = "https://election.rajasthan.gov.in/ED_Directory_Web/data/directory.json"

private const val ELECTION_LOGO_WEBP_BASE64 = "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"

@Composable
fun ElectionLogo(modifier: Modifier = Modifier) {
    val image = remember {
        val bytes = Base64.decode(ELECTION_LOGO_WEBP_BASE64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
    }
    Image(bitmap = image, contentDescription = "Election Commission of India", modifier = modifier)
}

fun isCeoHqStaff(o: Officer): Boolean = o.dob.isNotBlank()

// CEO HQ seniority order supplied for the directory.
fun normalizedPersonName(value: String): String =
    value.trim().uppercase(Locale.getDefault())
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

fun ceoHqSeniorityRank(o: Officer): Int {
    val d = normalizedPersonName(o.designation)
    val n = normalizedPersonName(o.officerName)

    // Fallback for the bundled directory. Older bundled records do not contain
    // seniorityOrder, so use the current CEO Office organization as the
    // authoritative role mapping when the value is not available.
    return when {
        n == "NAVEEN MAHAJAN" ||
            d == "CEO" ||
            d == "CHIEF ELECTORAL OFFICER" -> 0

        n == "SURESH CHANDRA" ||
            n == "RENU POONIA" ||
            d == "OSD" ||
            d.contains("OFFICER ON SPECIAL DUTY") -> 1

        n == "MUKUL MOHAN TIWARI" ||
            n == "M M TIWARI" ||
            n == "MM TIWARI" ||
            d.contains("JOINT CEO IT") ||
            d.contains("JOINT CHIEF ELECTORAL OFFICER IT") -> 2

        n == "RAUNAQUE BAIRAGI" ||
            d.contains("JOINT CEO") ||
            d.contains("JOINT CHIEF ELECTORAL OFFICER") -> 3

        n == "MAHAVEER PRASAD MEENA" ||
            n == "MAHAVEER MEENA" ||
            d == "FA" ||
            d.contains("FINANCIAL ADVISER") ||
            d.contains("FINANCIAL ADVISOR") -> 4

        d.contains("ACEO") ||
            d.contains("ADDITIONAL CHIEF ELECTORAL OFFICER") -> 5

        n == "SOMDATT DIXIT" ||
            d.contains("DY CEO") ||
            d.contains("DEPUTY CEO") ||
            d.contains("DEPUTY CHIEF ELECTORAL OFFICER") -> 6

        n == "PUNEET MEERWAL" ||
            d.contains("DY CEO IT") ||
            d.contains("DEPUTY CEO IT") ||
            d.contains("DEPUTY CHIEF ELECTORAL OFFICER IT") -> 7

        else -> 8
    }
}

fun sortBySeniorityOrder(list: List<Officer>): List<Officer> = list.sortedWith(
    compareBy<Officer>(
        // CEO HQ staff (DOB present) always come before ordinary staff.
        { if (isCeoHqStaff(it)) 0 else 1 },
        // Requested CEO HQ hierarchy is applied before alphabetical/designation
        // ordering, even when the bundled/live record has no usable order value.
        { if (isCeoHqStaff(it)) ceoHqSeniorityRank(it) else 0 },
        // Preserve explicit seniorityOrder for records that are not covered by
        // the CEO HQ role hierarchy.
        { if (it.seniorityOrder == DEFAULT_SENIORITY_ORDER) DEFAULT_SENIORITY_ORDER else it.seniorityOrder },
        { it.designation.lowercase(Locale.getDefault()) },
        { it.officerName.lowercase(Locale.getDefault()) }
    )
)

fun sortCeoHqBySeniority(list: List<Officer>): List<Officer> = sortBySeniorityOrder(list)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ElectionDirectoryApp() }
    }
}

@Composable
fun ElectionDirectoryApp() {
    val vm = rememberDirectoryViewModel()
    val state by vm.state.collectAsState()
    val all by vm.officers.collectAsState()
    val filtered by vm.filtered.collectAsState()
    var selected by remember { mutableStateOf<Officer?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = state.tab != AppTab.HOME && selected == null && !showFilters && !showAbout && !showSettings) {
        vm.setTab(AppTab.HOME)
    }

    val scheme = if (state.darkMode) darkColorScheme(primary = Color(0xFF9FAFFF), secondary = Color(0xFFFFB74D), background = Color(0xFF101318)) else lightColorScheme(primary = Navy, secondary = Gold, background = Background)
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(state.tab == AppTab.HOME, { vm.setTab(AppTab.HOME) },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(state.tab == AppTab.DIRECTORY, { vm.setTab(AppTab.DIRECTORY) },
                        icon = { Icon(Icons.Default.Person, null) }, label = { Text("Directory") })
                    NavigationBarItem(state.tab == AppTab.BIRTHDAYS, { vm.setTab(AppTab.BIRTHDAYS) },
                        icon = { Icon(Icons.Default.Cake, null) }, label = { Text("Birthdays") })
                    NavigationBarItem(state.tab == AppTab.MORE, { vm.setTab(AppTab.MORE) },
                        icon = { Icon(Icons.Default.MoreHoriz, null) }, label = { Text("More") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header(onAbout = { showAbout = true }, onSettings = { showSettings = true })
                when (state.tab) {
                    AppTab.HOME -> HomeScreen(vm, all, onOpenOfficer = { selected = it })
                    AppTab.DIRECTORY -> StaffScreen(vm, all, filtered, state, onOpen = { selected = it }, onFilter = { showFilters = true })
                    AppTab.OFFICES -> OfficesScreen(all, onOpen = { vm.setTab(AppTab.DIRECTORY); vm.setDistrict(it) }, onContact = { selected = it })
                    AppTab.BIRTHDAYS -> BirthdaysScreen(all, onOpen = { selected = it })
                    AppTab.MORE -> MoreScreen(
                        onOffices = { vm.setTab(AppTab.OFFICES) },
                        onSettings = { showSettings = true },
                        onAbout = { showAbout = true }
                    )
                }
            }
        }
    }

    selected?.let { OfficerDetails(it, onDismiss = { selected = null }) }
    if (showFilters) FilterSheet(vm, all, onDismiss = { showFilters = false })
    if (showAbout) AboutSheet(all.size, onDismiss = { showAbout = false })
    if (showSettings) SettingsSheet(
        darkMode = state.darkMode,
        onDarkMode = vm::setDarkMode,
        onSync = { vm.syncFromOfficialDirectory() },
        onDismiss = { showSettings = false }
    )
}

@Composable
fun Header(onAbout: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Navy, shadowElevation = 4.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Gold))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clickable { onAbout() },
                    contentAlignment = Alignment.Center
                ) {
                    ElectionLogo(Modifier.fillMaxSize().padding(2.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("RAJASTHAN GOVERNMENT", color = Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("Election Department Directory", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("राजस्थान सरकार • निर्वाचन विभाग", color = Color.White.copy(.78f), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "About", tint = Color.White) }
            }
            Row(Modifier.fillMaxWidth().height(3.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Gold))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(.9f)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Green))
            }
        }
    }
}

@Composable
fun HomeScreen(vm: DirectoryViewModel, all: List<Officer>, onOpenOfficer: (Officer) -> Unit) {
    val today = all.filter { isBirthdayToday(it.dob) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Navy,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 2.dp
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(78.dp).clip(RoundedCornerShape(18.dp)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        ElectionLogo(Modifier.fillMaxSize().padding(3.dp))
                    }
                    Spacer(Modifier.width(15.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Official Directory", color = Gold, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("Election Department", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Rajasthan Government", color = Color.White.copy(.78f), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Color.White.copy(.10f), shape = RoundedCornerShape(10.dp)) {
                            Text("$" + "{all.size} contacts currently available", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        }

        item {
            Text("Quick access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(Modifier.height(7.dp))
            HomeActionRow(Icons.Default.People, "Staff Directory", "Search staff by name, mobile, office, designation or Employee ID") {
                vm.setQuery("")
                vm.setDistrict("All")
                vm.setDesignation("All")
                vm.setSectionCell("All")
                vm.setTab(AppTab.DIRECTORY)
            }
            Spacer(Modifier.height(8.dp))
            HomeActionRow(Icons.Default.Business, "Office Directory", "Browse offices and staff by district") {
                vm.setTab(AppTab.OFFICES)
            }
            Spacer(Modifier.height(8.dp))
            HomeActionRow(Icons.Default.Cake, "Birthdays", "View today's and upcoming staff birthdays") {
                vm.setTab(AppTab.BIRTHDAYS)
            }
        }

        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = SurfaceWhite,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Navy.copy(.08f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, null, tint = Navy)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Find contacts quickly", color = TextDark, fontWeight = FontWeight.Bold)
                        Text("Use the Directory tab to search and apply filters.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                }
            }
        }

        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = SurfaceWhite,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Directory status", fontWeight = FontWeight.Bold, color = TextDark)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The directory currently contains " + all.size + " contacts. Updated records are available after Sync from Settings.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = SurfaceWhite,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cake, null, tint = Gold)
                        Spacer(Modifier.width(8.dp))
                        Text("Today's birthdays", fontWeight = FontWeight.Bold, color = TextDark)
                        Spacer(Modifier.weight(1f))
                        if (today.isNotEmpty()) {
                            Text(today.size.toString(), color = Navy, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (today.isEmpty()) {
                        Text("No staff birthday today.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        today.take(3).forEach { person ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenOfficer(person) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                InitialAvatar(person.officerName)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(person.officerName, fontWeight = FontWeight.Bold, color = TextDark)
                                    Text(person.designation, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = SurfaceWhite,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Navy.copy(.08f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Navy, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextDark, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
fun StaffScreen(vm: DirectoryViewModel, all: List<Officer>, list: List<Officer>, state: DirectoryUiState, onOpen: (Officer) -> Unit, onFilter: () -> Unit) {
    val displayList = sortBySeniorityOrder(list)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Directory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
                Text(
                    if (state.district.equals("Jaipur", true)) "CEO Office HQ • Jaipur" else if (state.district == "All") "All Rajasthan staff" else state.district + " staff",
                    color = TextMuted, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SearchField(state.query, vm::setQuery)
        Spacer(Modifier.height(7.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterButton("District", state.district, Modifier.weight(1f)) { onFilter() }
            FilterButton("Designation", state.designation, Modifier.weight(1f)) { onFilter() }
            FilterButton("Section / Cell", state.sectionCell, Modifier.weight(1f)) { onFilter() }
        }

        val hasFilters = state.district != "All" || state.designation != "All" || state.sectionCell != "All" || state.query.isNotBlank()
        if (hasFilters) {
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.district != "All") ActiveChip("District: " + state.district) { vm.setDistrict("All") }
                if (state.designation != "All") ActiveChip("Designation: " + state.designation) { vm.setDesignation("All") }
                if (state.sectionCell != "All") ActiveChip("Section: " + state.sectionCell) { vm.setSectionCell("All") }
                if (state.query.isNotBlank()) ActiveChip("Search") { vm.setQuery("") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.clearFilterOnly() }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.height(30.dp)) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    list.isEmpty() -> "No staff found"
                    list.size == 1 -> "1 staff member"
                    else -> list.size.toString() + " staff members"
                },
                color = TextDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.weight(1f))
            if (state.district != "All") {
                TextButton(onClick = { vm.setDistrict("All") }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text("All Rajasthan", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No matching staff", if (state.query.isNotBlank()) "Try a name, phone, email, employee ID or office name." else "Try changing the filters.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)
            ) {
                items(displayList, key = { it.id }) { CompactOfficerCard(it, onOpen) }
            }
        }
    }
}

@Composable
fun ActiveChip(text: String, onRemove: () -> Unit) {
    Surface(color = Navy.copy(.08f), shape = RoundedCornerShape(9.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Navy.copy(.16f))) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Navy, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Close, "Remove filter", tint = Navy, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun DashboardStats(all: List<Officer>, showing: Int, favorites: Int) {
    val statItems = listOf(
        Triple(Icons.Default.Person, all.size.toString(), "STAFF"),
        Triple(Icons.Default.LocationOn, 41.toString(), "DISTRICTS"),
        Triple(Icons.Default.Cake, all.count { isBirthdayToday(it.dob) }.toString(), "TODAY"),
        Triple(Icons.Default.FilterAlt, showing.toString(), "SHOWING")
    )
    Surface(Modifier.fillMaxWidth(), color = NavyDark, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            statItems.forEachIndexed { index, item ->
                if (index > 0) VerticalDivider(Modifier.height(42.dp), color = Color.White.copy(.13f))
                StatItem(item.first, item.second, item.third)
            }
        }
    }
}

@Composable
fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Gold, modifier = Modifier.size(19.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(label, color = Color.White.copy(.72f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .semantics { contentDescription = "Search directory. Enter name, mobile, office, email or employee ID." },
        color = SurfaceWhite,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (value.isNotBlank()) Navy else Border)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = if (value.isNotBlank()) Navy else TextMuted, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (value.isBlank()) {
                    Text("Search directory", color = TextMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDark),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Navy)
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextMuted, modifier = Modifier.size(25.dp))
                }
            }
        }
    }
}

@Composable
fun FilterButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = value != "All"
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Navy else Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) Navy.copy(.06f) else SurfaceWhite,
            contentColor = if (active) Navy else TextDark
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Icon(if (label == "District") Icons.Default.LocationOn else if (label == "Designation") Icons.Default.Badge else Icons.Default.Work, null, Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(if (value == "All") label else value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
    }
}

@Composable
fun CompactOfficerCard(o: Officer, onOpen: (Officer) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { onOpen(o) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceWhite), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(o.officerName)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.officerName, fontWeight = FontWeight.Bold, color = TextDark, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            o.designation.ifBlank { "Designation not available" },
                            color = Navy,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCeoHqStaff(o)) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = Gold.copy(.14f), shape = RoundedCornerShape(7.dp)) {
                                Text(
                                    "CEO HQ",
                                    color = Color(0xFF8A5A00),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = Navy)
                Spacer(Modifier.width(5.dp))
                Text(o.locationLabel().ifBlank { "Location not available" }, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (o.sectionCell.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Work, null, Modifier.size(14.dp), tint = TextMuted)
                    Spacer(Modifier.width(5.dp))
                    Text(o.sectionCell, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                o.mobile()?.let { SmallAction("Call", Icons.Default.Call) { dial(context, it) } }
                o.mobile()?.let { SmallAction("WhatsApp", Icons.Default.Chat) { whatsapp(context, it) } }
                if (o.email.isNotBlank()) SmallAction("Email", Icons.Default.Email) { email(context, o.email) }
                SmallAction("Details", Icons.Default.Visibility) { onOpen(o) }
            }
        }
    }
}

@Composable
fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Navy.copy(.08f), contentColor = Navy),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InitialAvatar(name: String) {
    val initials = name.split(" ").filter(String::isNotBlank).take(2).joinToString("") { it.first().uppercase() }
    Box(Modifier.size(52.dp).clip(CircleShape).background(avatarColor(name)), contentAlignment = Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
}
fun avatarColor(name: String): Color = listOf(Color(0xFF2979FF), Color(0xFF7E57C2), Color(0xFF00897B), Color(0xFFE91E63), Color(0xFFEF6C00))[name.hashCode().and(Int.MAX_VALUE) % 5]
fun primaryDistrict(o: Officer): String = if (o.subLocation.isNotBlank()) o.district.split(",").last().trim() else o.district.split(",").last().trim()

@Composable
fun MoreScreen(onOffices: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
        Text("Other directory and application options", color = TextMuted)
        Spacer(Modifier.height(14.dp))
        MoreOption(Icons.Default.Business, "Office Directory", "Browse staff by district and office", onOffices)
        MoreOption(Icons.Default.Settings, "Settings", "Application preferences and data management", onSettings)
        MoreOption(Icons.Default.Info, "About", "Application information and directory status", onAbout)
    }
}

@Composable
fun MoreOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Navy.copy(.08f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Navy)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = TextDark)
                Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
fun OfficesScreen(all: List<Officer>, onOpen: (String) -> Unit, onContact: (Officer) -> Unit) {
    val grouped = all.groupBy { primaryDistrict(it) }.toSortedMap()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Office Directory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
        Text("Browse staff by district and location", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            grouped.forEach { (district, staff) ->
                item {
                    OfficeCard(district, staff, onOpen, onContact)
                }
            }
        }
    }
}

@Composable
fun OfficeCard(district: String, staff: List<Officer>, onOpen: (String) -> Unit, onContact: (Officer) -> Unit) {
    val sub = staff.filter { it.subLocation.isNotBlank() }.groupBy { it.subLocation }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Navy.copy(.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Business, null, tint = Navy) }
                Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(district, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text("${staff.size} staff", color = Color.Gray) }
                IconButton({ onOpen(district) }) { Icon(Icons.Default.ChevronRight, "View staff") }
            }
            if (sub.isNotEmpty()) {
                Divider(Modifier.padding(vertical = 8.dp))
                sub.forEach { (location, people) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(district) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationCity, null, Modifier.size(18.dp), tint = Gold); Spacer(Modifier.width(7.dp)); Text(location, Modifier.weight(1f)); Text("${people.size}", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun BirthdaysScreen(list: List<Officer>, onOpen: (Officer) -> Unit) {
    val today = list.filter { isBirthdayToday(it.dob) }
        .sortedBy { it.officerName.lowercase(Locale.getDefault()) }
    val upcoming = list.filter { it.dob.isNotBlank() && !isBirthdayToday(it.dob) }
        .sortedBy { daysUntilBirthday(it.dob) }
        .take(20)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)
    ) {
        item {
            Text("Birthdays", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(Modifier.height(2.dp))
            Text("Birth dates are matched by day and month.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        if (today.isNotEmpty()) {
            item {
                Surface(color = Gold.copy(.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cake, "Today's birthdays", tint = Gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Today's Birthdays", fontWeight = FontWeight.Bold, color = TextDark)
                        Spacer(Modifier.weight(1f))
                        Text(today.size.toString(), color = Navy, fontWeight = FontWeight.Bold)
                    }
                }
            }
            items(today, key = { "today-" + it.id }) { BirthdayRow(it, onOpen) }
        } else {
            item {
                Surface(color = SurfaceWhite, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cake, "Birthdays", tint = Gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text("No birthday today", fontWeight = FontWeight.Bold, color = TextDark)
                            if (upcoming.isNotEmpty()) Text("Upcoming birthdays are shown below.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Upcoming", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextDark)
            }
            items(upcoming, key = { "upcoming-" + it.id }) { BirthdayRow(it, onOpen) }
        } else if (today.isEmpty()) {
            item { Text("No DOB information is available yet.", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
        }
    }
}
@Composable
fun BirthdayRow(o: Officer, onOpen: (Officer) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(o) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(o.officerName); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                Text(o.officerName, fontWeight = FontWeight.Bold); Text(o.designation, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatDob(o.dob), color = Navy, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = Navy); Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(vm: DirectoryViewModel, all: List<Officer>, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()

    var localDistrict by remember(state.district) { mutableStateOf(state.district) }
    var localDesignation by remember(state.designation) { mutableStateOf(state.designation) }
    var localSection by remember(state.sectionCell) { mutableStateOf(state.sectionCell) }

    // Cascading filters: every next filter is based on the selections above it.
    val districtValues = remember(all) {
        listOf("All") + all.flatMap { it.district.split(",").map(String::trim) }
            .filter { it.isNotBlank() }.distinct().sorted()
    }

    val designationValues = remember(all, localDistrict) {
        val source = if (localDistrict == "All") all else all.filter {
            it.district.split(",").any { d -> d.trim().equals(localDistrict, true) }
        }
        listOf("All") + source.map { it.designation.trim() }
            .filter { it.isNotBlank() }.distinct().sorted()
    }

    val sectionValues = remember(all, localDistrict, localDesignation) {
        val source = all.filter { officer ->
            val districtMatch = localDistrict == "All" ||
                officer.district.split(",").any { d -> d.trim().equals(localDistrict, true) }
            val designationMatch = localDesignation == "All" ||
                officer.designation.equals(localDesignation, true)
            districtMatch && designationMatch
        }
        listOf("All") + source.map { it.sectionCell.trim() }
            .filter { it.isNotBlank() }.distinct().sorted()
    }

    // Keep selections valid when a parent filter changes.
    LaunchedEffect(localDistrict) {
        if (localDesignation != "All" && localDesignation !in designationValues) {
            localDesignation = "All"
        }
    }
    LaunchedEffect(localDistrict, localDesignation) {
        if (localSection != "All" && localSection !in sectionValues) {
            localSection = "All"
        }
    }

    val matchingCount = remember(all, localDistrict, localDesignation, localSection) {
        all.count { officer ->
            val districtMatch = localDistrict == "All" ||
                officer.district.split(",").any { d -> d.trim().equals(localDistrict, true) }
            val designationMatch = localDesignation == "All" ||
                officer.designation.equals(localDesignation, true)
            val sectionMatch = localSection == "All" ||
                officer.sectionCell.equals(localSection, true)
            districtMatch && designationMatch && sectionMatch
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Filter Staff",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$matchingCount staff match these filters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton({
                    localDistrict = "All"
                    localDesignation = "All"
                    localSection = "All"
                    vm.clearFilterOnly()
                }) { Text("Clear all") }
            }

            Spacer(Modifier.height(16.dp))

            FilterSelector(
                label = "District",
                selected = localDistrict,
                values = districtValues,
                onSelect = {
                    localDistrict = it
                    localDesignation = "All"
                    localSection = "All"
                }
            )

            Spacer(Modifier.height(10.dp))

            FilterSelector(
                label = "Designation",
                selected = localDesignation,
                values = designationValues,
                onSelect = {
                    localDesignation = it
                    localSection = "All"
                }
            )

            Spacer(Modifier.height(10.dp))

            FilterSelector(
                label = "Section / Cell",
                selected = localSection,
                values = sectionValues,
                onSelect = { localSection = it }
            )

            Spacer(Modifier.height(18.dp))

            Button(
                {
                    vm.setDistrict(localDistrict)
                    vm.setDesignation(localDesignation)
                    vm.setSectionCell(localSection)
                    onDismiss()
                },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.FilterAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Show $matchingCount Staff")
            }
        }
    }
}

@Composable
fun FilterSelector(
    label: String,
    selected: String,
    values: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val displaySelected = when {
        selected != "All" -> selected
        label == "District" -> "All Districts"
        label == "Designation" -> "All Designations"
        else -> "All Sections / Cells"
    }
    val shown = remember(values, search) {
        val q = search.trim()
        if (q.isBlank()) values else values.filter { it.contains(q, ignoreCase = true) }
    }
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
            Text(displaySelected, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; search = "" }, modifier = Modifier.heightIn(max = 430.dp)) {
            if (values.size > 8) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth(),
                    singleLine = true, placeholder = { Text("Search " + label) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (search.isNotBlank()) IconButton({ search = "" }) { Icon(Icons.Default.Clear, "Clear search") } }
                )
                HorizontalDivider()
            }
            shown.forEach { value ->
                val display = if (value == "All") {
                    when (label) {
                        "District" -> "All Districts"
                        "Designation" -> "All Designations"
                        else -> "All Sections / Cells"
                    }
                } else value
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (value == selected) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = Navy)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { onSelect(value); expanded = false; search = "" }
                )
            }
            if (shown.isEmpty()) DropdownMenuItem(text = { Text("No matching " + label) }, onClick = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    darkMode: Boolean,
    onDarkMode: (Boolean) -> Unit,
    onSync: suspend () -> Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = Navy, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Directory preferences and data sync", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark mode", fontWeight = FontWeight.SemiBold)
                    Text("Use a darker interface in low light", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                Switch(darkMode, onDarkMode)
            }

            Spacer(Modifier.height(16.dp))
            Surface(color = Navy.copy(.06f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, null, tint = Navy)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Official directory sync", fontWeight = FontWeight.Bold, color = TextDark)
                        Text("Refresh the local directory from the official online source. No login is required.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (syncing) return@Button
                    syncing = true
                    syncMessage = ""
                    scope.launch {
                        runCatching { onSync() }
                            .onSuccess { count ->
                                syncMessage = if (count > 0) "Directory updated successfully: " + count + " contacts." else "No records were received."
                                toast(context, syncMessage)
                            }
                            .onFailure {
                                syncMessage = "Sync failed. Could not reach the official directory."
                                toast(context, syncMessage)
                            }
                        syncing = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(13.dp),
                enabled = !syncing,
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.Sync, null)
                }
                Spacer(Modifier.width(7.dp))
                Text(if (syncing) "Syncing…" else "Sync Official Directory")
            }

            if (syncMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(syncMessage, color = if (syncMessage.startsWith("Sync failed")) MaterialTheme.colorScheme.error else Green, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Text("The directory remains available offline using the data stored on this device.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun readSeniorityOrder(o: org.json.JSONObject): Int {
    val raw = o.opt("seniorityOrder")
    return when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: DEFAULT_SENIORITY_ORDER
        else -> DEFAULT_SENIORITY_ORDER
    }
}

suspend fun fetchOfficialDirectory(): List<Officer> = withContext(Dispatchers.IO) {
    val connection = (URL(DIRECTORY_SYNC_URL + "?ts=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Cache-Control", "no-cache")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("Server returned HTTP " + code)
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseOfficialDirectoryJson(text)
    } finally {
        connection.disconnect()
    }
}

fun parseOfficialDirectoryJson(text: String): List<Officer> {
    val arr = JSONArray(text)
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("officerName", o.optString("name")).trim()
            if (name.isBlank()) continue
            val rawDistrict = o.optString("district").trim()
            var district = rawDistrict
            var sub = o.optString("subLocation").trim()
            if (district.contains(",") && sub.isBlank()) {
                val parts = district.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    sub = parts.dropLast(1).joinToString(", ")
                    district = parts.last()
                }
            }
            val contacts = o.optJSONArray("contactNumbers")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).joinToString("|")
            } ?: normalizeContacts(o.optString("contactNumbers", o.optString("contactNo")))
            add(
                Officer(
                    id = o.optInt("id", i + 1),
                    officerName = name,
                    designation = o.optString("designation"),
                    officeDepartment = o.optString("officeDepartment", "ELECTION DEPARTMENT").ifBlank { "ELECTION DEPARTMENT" },
                    district = district,
                    subLocation = sub,
                    sectionCell = o.optString("sectionCell", o.optString("section")),
                    employeeId = o.optString("employeeId", o.optString("employeeID")),
                    dob = o.optString("dob"),
                    contactNumbers = contacts,
                    email = o.optString("email"),
                    remark = o.optString("remark"),
                    seniorityOrder = readSeniorityOrder(o)
                )
            )
        }
    }.sortedWith(compareBy<Officer>({ it.seniorityOrder }, { it.officerName.lowercase(Locale.getDefault()) }))
}

fun excelColumnIndex(reference: String): Int {
    val letters = reference.takeWhile { it.isLetter() }.uppercase(Locale.getDefault())
    var result = 0
    for (c in letters) result = result * 26 + (c - 'A' + 1)
    return (result - 1).coerceAtLeast(0)
}

fun normalizeHeader(value: String): String = value.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
fun normalizeContacts(value: String): String = value.replace("\n", " ").split(Regex("[,; ]+")).filter { it.isNotBlank() }.joinToString("|")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficerDetails(o: Officer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
                Text("Contact Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(o.officerName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.officerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.designation, color = Navy, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (isCeoHqStaff(o)) {
                            Spacer(Modifier.width(7.dp))
                            Surface(color = Gold.copy(.14f), shape = RoundedCornerShape(7.dp)) {
                                Text("CEO HQ", color = Color(0xFF8A5A00), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (o.sectionCell.isNotBlank()) DetailRow(Icons.Default.Work, "Section / Cell: " + o.sectionCell)
            if (o.employeeId.isNotBlank()) DetailRow(Icons.Default.Person, "Employee ID: " + o.employeeId)
            if (o.dob.isNotBlank()) DetailRow(Icons.Default.Cake, "DOB: " + o.dob)
            Spacer(Modifier.height(12.dp))
            Surface(color = Gold.copy(.13f), shape = RoundedCornerShape(9.dp)) {
                Text(o.officeDepartment, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF704000), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            DetailRow(Icons.Default.LocationOn, o.locationLabel())
            Spacer(Modifier.height(10.dp))
            Text("Contact", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            o.mobile()?.let { ContactLine("Mobile", it, Icons.Default.Phone) { dial(context, it) } }
            o.officeNumbers().forEach { ContactLine("Office / EPABX", it, Icons.Default.Phone) { dial(context, it) } }

            if (o.email.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Email, null, tint = Navy)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Email", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(o.email)
                    }
                    IconButton({
                        clipboard.setText(AnnotatedString(o.email))
                        toast(context, "Email copied")
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy email")
                    }
                }
            }

            if (o.remark.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text("Remarks", fontWeight = FontWeight.Bold)
                Text(o.remark, color = Color.Gray)
            }

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Call", Icons.Default.Call, Green, o.mobile() != null) { o.mobile()?.let { dial(context, it) } }
                    ActionButton("Email", Icons.Default.Email, Navy, o.email.isNotBlank()) { email(context, o.email) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Share", Icons.Default.Share, Gold, true) { shareContact(context, o) }
                    ActionButton("Save Contact", Icons.Default.PersonAdd, Color(0xFF596574), true) { saveContact(context, o) }
                }
            }

            if (o.mobile() != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ whatsapp(context, o.mobile()!!) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.Chat, null)
                    Spacer(Modifier.width(7.dp))
                    Text("WhatsApp", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ContactLine(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Navy); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.SemiBold) }; IconButton(onCall) { Icon(Icons.Default.Call, "Call") } }
}

@Composable
fun RowScope.ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(52.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(20.dp), tint = Navy); Spacer(Modifier.width(8.dp)); Text(text) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(total: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 28.dp)) {
        Text("About", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text("Election Department Directory", style = MaterialTheme.typography.titleLarge, color = Navy); Text("Election Department • Rajasthan Government", color = Color.Gray); Spacer(Modifier.height(18.dp)); DetailRow(Icons.Default.Person, "$total contacts"); DetailRow(Icons.Default.Info, "Works offline"); DetailRow(Icons.Default.Settings, "Directory updated: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}"); Spacer(Modifier.height(14.dp)); Text("Designed for quick, reliable access to official staff contact information.", color = Color.Gray) }
    }
}

fun parseDob(value: String): java.time.LocalDate? {
    val raw = value.trim(); if (raw.isBlank()) return null
    val patterns = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "dd/MM/yy", "dd-MM-yy", "yyyy/MM/dd", "dd MMM yyyy", "d MMM yyyy", "MM/dd/yyyy")
    for (pattern in patterns) runCatching { return java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) }
    return runCatching { java.time.LocalDate.of(1899, 12, 30).plusDays(raw.toDouble().toLong()) }.getOrNull()
}

fun isBirthdayToday(value: String): Boolean {
    val d = parseDob(value) ?: return false
    val today = java.time.LocalDate.now()
    return d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth
}

fun daysUntilBirthday(value: String): Int {
    val d = parseDob(value) ?: return Int.MAX_VALUE
    val today = java.time.LocalDate.now()
    var next = runCatching { java.time.LocalDate.of(today.year, d.monthValue, d.dayOfMonth) }.getOrElse { java.time.LocalDate.of(today.year, 2, 28) }
    if (next.isBefore(today)) {
        val nextYear = today.year + 1
        next = runCatching { java.time.LocalDate.of(nextYear, d.monthValue, d.dayOfMonth) }.getOrElse { java.time.LocalDate.of(nextYear, 2, 28) }
    }
    return java.time.temporal.ChronoUnit.DAYS.between(today, next).toInt()
}

fun formatDob(value: String): String {
    val d = parseDob(value) ?: return value
    return d.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH))
}

fun dial(context: Context, number: String) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}"))) }
fun email(context: Context, address: String) { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address"))) }
fun whatsapp(context: Context, number: String) { val clean = number.filter(Char::isDigit); try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$clean"))) } catch (_: Exception) { toast(context, "WhatsApp is not available") } }
fun shareContact(context: Context, o: Officer) { val body = buildString { append(o.officerName).append('\n'); append(o.designation).append('\n'); append(o.officeDepartment).append('\n'); append("Location: ${o.locationLabel()}\n"); if (o.phones().isNotEmpty()) append("Phone: ${o.phones().joinToString(", ")}\n"); if (o.email.isNotBlank()) append("Email: ${o.email}\n"); if (o.remark.isNotBlank()) append("Remarks: ${o.remark}") }; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, "Share Contact")) }
fun saveContact(context: Context, o: Officer) { val i = Intent(Intent.ACTION_INSERT).apply { type = ContactsContract.RawContacts.CONTENT_TYPE; putExtra(ContactsContract.Intents.Insert.NAME, o.officerName); putExtra(ContactsContract.Intents.Insert.PHONE, o.mobile()); putExtra(ContactsContract.Intents.Insert.EMAIL, o.email); putExtra(ContactsContract.Intents.Insert.COMPANY, o.officeDepartment); putExtra(ContactsContract.Intents.Insert.JOB_TITLE, o.designation) }; context.startActivity(i) }
fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
fun rememberDirectoryViewModel(): DirectoryViewModel {
    val context = LocalContext.current
    val db = remember { Room.databaseBuilder(context, AppDatabase::class.java, "election_directory.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
    val repo = remember { OfficerRepository(db.officerDao()) }
    val vm = remember { DirectoryViewModel(repo) }
    LaunchedEffect(Unit) {
        if (repo.officers.first().isEmpty()) seedDatabase(context, repo)
    }
    return vm
}

suspend fun seedDatabase(context: Context, repo: OfficerRepository) {
    val arr = JSONArray(context.assets.open("directory.json").bufferedReader().readText())
    val seed = buildList {
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i); val nums = x.getJSONArray("contactNumbers")
            add(
                Officer(
                    id = x.getInt("id"),
                    officerName = x.getString("officerName"),
                    designation = x.getString("designation"),
                    officeDepartment = x.getString("officeDepartment"),
                    district = x.getString("district"),
                    subLocation = x.optString("subLocation"),
                    sectionCell = x.optString("sectionCell"),
                    employeeId = x.optString("employeeId"),
                    dob = x.optString("dob"),
                    contactNumbers = buildList { for (j in 0 until nums.length()) add(nums.getString(j)) }.joinToString("|"),
                    email = x.optString("email"),
                    remark = x.optString("remark"),
                    seniorityOrder = readSeniorityOrder(x)
                )
            )
        }
    }
    repo.replace(seed)
}