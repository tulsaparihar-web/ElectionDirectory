package com.rajasthan.election.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.zip.ZipInputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.security.MessageDigest

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
    val tab: AppTab = AppTab.DIRECTORY,
    val darkMode: Boolean = false
)

class DirectoryViewModel(private val repo: OfficerRepository) : ViewModel() {
    suspend fun syncFromOfficialDirectory(): Int {
        val items = fetchOfficialDirectory()
        if (items.isEmpty()) throw IllegalStateException("The official directory returned no usable records.")
        repo.upsert(items)
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
                    AppTab.HOME -> HomeScreen(vm, all, onOpenStaff = { vm.setTab(AppTab.DIRECTORY) }, onOpenOfficer = { selected = it })
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
        onImport = { items -> vm.replaceData(items) },
        onSync = { vm.syncFromOfficialDirectory() },
        onDismiss = { showSettings = false }
    )
}

@Composable
fun Header(onAbout: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Navy, shadowElevation = 4.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Gold))
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(.12f)).clickable { onAbout() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AccountBalance, "Rajasthan Government", tint = Gold, modifier = Modifier.size(29.dp))
                }
                Spacer(Modifier.width(12.dp))
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
fun HomeScreen(vm: DirectoryViewModel, all: List<Officer>, onOpenStaff: () -> Unit, onOpenOfficer: (Officer) -> Unit) {
    val today = all.filter { isBirthdayToday(it.dob) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp)
    ) {
        item {
            Text("Find an officer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(Modifier.height(2.dp))
            Text("Search the official Rajasthan Election Department directory", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            var homeQuery by remember { mutableStateOf("") }
            SearchField(homeQuery, { q ->
                homeQuery = q
                vm.setQuery(q)
                if (q.isNotBlank()) {
                    vm.setDistrict("All")
                    vm.setTab(AppTab.DIRECTORY)
                }
            })
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStatCard(Icons.Default.Person, all.size.toString(), "Total Staff", Navy, Modifier.weight(1f))
                HomeStatCard(Icons.Default.LocationOn, "41", "Districts", Green, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStatCard(Icons.Default.Badge, all.map { it.designation }.filter(String::isNotBlank).distinct().size.toString(), "Designations", Gold, Modifier.weight(1f))
                HomeStatCard(Icons.Default.Work, all.map { it.sectionCell }.filter(String::isNotBlank).distinct().size.toString(), "Sections / Cells", Color(0xFF6F42C1), Modifier.weight(1f))
            }
        }
        item {
            Text("Quick access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextDark)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeQuickCard(Icons.Default.People, "All Staff", "Browse directory", Modifier.weight(1f)) {
                    vm.setQuery(""); vm.setDistrict("All"); vm.setTab(AppTab.DIRECTORY)
                }
                HomeQuickCard(Icons.Default.Business, "Offices", "Browse locations", Modifier.weight(1f)) {
                    vm.setTab(AppTab.OFFICES)
                }
                HomeQuickCard(Icons.Default.Cake, "Birthdays", "Upcoming dates", Modifier.weight(1f)) {
                    vm.setTab(AppTab.BIRTHDAYS)
                }
            }
        }
        item {
            if (today.isNotEmpty()) {
                Surface(Modifier.fillMaxWidth(), color = SurfaceWhite, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cake, null, tint = Gold)
                            Spacer(Modifier.width(7.dp))
                            Text("Today's Birthdays", fontWeight = FontWeight.Bold, color = TextDark)
                            Spacer(Modifier.weight(1f))
                            Text(today.size.toString(), color = Navy, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
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
            } else {
                Surface(Modifier.fillMaxWidth(), color = SurfaceWhite, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cake, null, tint = Gold)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Birthdays", fontWeight = FontWeight.Bold, color = TextDark)
                            Text("No birthday today", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeStatCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = SurfaceWhite, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(.10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(value, color = TextDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun HomeQuickCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = SurfaceWhite, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Navy, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(5.dp))
            Text(title, color = TextDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Text(subtitle, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
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
            Surface(color = Green.copy(.10f), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Green))
                    Spacer(Modifier.width(5.dp))
                    Text("OFFLINE READY", color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
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
    onImport: (List<Officer>) -> Unit,
    onSync: suspend () -> Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showAdminLogin by remember { mutableStateOf(false) }
    var adminAuthenticated by remember { mutableStateOf(false) }
    var pendingImportType by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun startImport(type: String) {
        pendingImportType = type
        showAdminLogin = true
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { parseXlsx(context, uri) }.onSuccess { items ->
                if (items.isNotEmpty()) { onImport(items); toast(context, "Imported ${items.size} contacts") }
                else toast(context, "No usable contacts found in the first sheet")
            }.onFailure { toast(context, "Could not import Excel file: ${it.message ?: "invalid file"}") }
        }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { parseJson(context, uri) }.onSuccess { items ->
                if (items.isNotEmpty()) { onImport(items); toast(context, "Imported ${items.size} contacts") }
                else toast(context, "No usable contacts found in JSON")
            }.onFailure { toast(context, "Could not import JSON file: ${it.message ?: "invalid file"}") }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (adminAuthenticated) {
                    AssistChip(onClick = { adminAuthenticated = false }, label = { Text("Admin logout") }, leadingIcon = { Icon(Icons.Default.LockOpen, null, Modifier.size(16.dp)) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Dark mode", fontWeight = FontWeight.SemiBold); Text("Use a darker interface in low light", color = Color.Gray) }
                Switch(darkMode, onDarkMode)
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = if (adminAuthenticated) Green.copy(.10f) else Navy.copy(.08f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (adminAuthenticated) Icons.Default.LockOpen else Icons.Default.Lock, null, tint = if (adminAuthenticated) Green else Navy)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(if (adminAuthenticated) "Admin access enabled" else "Admin access required", fontWeight = FontWeight.SemiBold)
                        Text(if (adminAuthenticated) "You can now import directory data." else "Excel and JSON imports are restricted to the administrator.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (syncing) return@Button
                    syncing = true
                    syncMessage = ""
                    scope.launch {
                        runCatching { onSync() }
                            .onSuccess { count ->
                                syncMessage = if (count > 0) "Synced " + count + " records from the official directory." else "No records were received."
                                toast(context, syncMessage)
                            }
                            .onFailure {
                                syncMessage = "Sync failed. Could not reach the official directory."
                                toast(context, syncMessage)
                            }
                        syncing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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
                Text(if (syncing) "Syncing…" else "Sync")
            }
            if (syncMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(syncMessage, color = if (syncMessage.startsWith("Sync failed")) MaterialTheme.colorScheme.error else Green, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton({ startImport("excel") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Default.Upload, null); Spacer(Modifier.width(7.dp)); Text("Import Directory Excel")
            }
            Spacer(Modifier.height(7.dp))
            OutlinedButton({ startImport("json") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Default.Code, null); Spacer(Modifier.width(7.dp)); Text("Import Directory JSON")
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "Sync updates/adds records from the official online directory. No login is required. Your local data remains available when offline.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Manual Excel/JSON import replaces the current local directory and remains administrator-only.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showAdminLogin) {
        AdminLoginSheet(
            onDismiss = { showAdminLogin = false },
            onAuthenticated = {
                adminAuthenticated = true
                showAdminLogin = false
                if (pendingImportType == "excel") launcher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel"))
                else jsonLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminLoginSheet(onDismiss: () -> Unit, onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, Modifier.size(30.dp), tint = Navy)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Administrator Login", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Required before importing directory data", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(username, { username = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") }, leadingIcon = { Icon(Icons.Default.Person, null) })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                password,
                { password = it; error = "" },
                Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password") } }
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            Button({
                if (verifyAdminCredentials(username, password)) onAuthenticated()
                else { error = "Invalid administrator username or password"; password = ""; toast(context, "Admin authentication failed") }
            }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Login, null); Spacer(Modifier.width(7.dp)); Text("Authenticate & Continue")
            }
            Spacer(Modifier.height(8.dp))
            Text("Only the administrator can change the local directory data.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val ADMIN_USERNAME = "tulsaparihar.doit"
private const val ADMIN_PASSWORD_SHA256 = "59f5fc5d0e7d4b0cdf2fc1a67784b1aab951fb44064c32f9fe846dc06a7d7997"

fun verifyAdminCredentials(username: String, password: String): Boolean {
    if (username.trim() != ADMIN_USERNAME) return false
    val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { "%02x".format(it) }
    return hash == ADMIN_PASSWORD_SHA256
}

fun parseJson(context: Context, uri: Uri): List<Officer> {
    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return emptyList()
    val arr = JSONArray(text)
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("officerName", o.optString("name")).trim(); if (name.isBlank()) continue
            val rawDistrict = o.optString("district").trim()
            var district = rawDistrict; var sub = o.optString("subLocation").trim()
            if (district.contains(",") && sub.isBlank()) { val parts = district.split(",").map { it.trim() }.filter { it.isNotBlank() }; if (parts.size >= 2) { sub = parts.dropLast(1).joinToString(", "); district = parts.last() } }
            val contacts = o.optJSONArray("contactNumbers")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).joinToString("|") } ?: normalizeContacts(o.optString("contactNumbers", o.optString("contactNo")))
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
                    isFavorite = o.optBoolean("isFavorite", false),
                    seniorityOrder = readSeniorityOrder(o)
                )
            )
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

fun parseXlsx(context: Context, uri: Uri): List<Officer> {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zis ->
        while (true) {
            val e = zis.nextEntry ?: break
            if (!e.isDirectory) entries[e.name] = zis.readBytes()
        }
    }
    val shared = mutableListOf<String>()
    entries["xl/sharedStrings.xml"]?.let { data ->
        val p = android.util.Xml.newPullParser(); p.setInput(data.inputStream(), "UTF-8")
        var event = p.eventType; var current = StringBuilder(); var inSi = false
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "si") { current = StringBuilder(); inSi = true }
            else if (inSi && event == org.xmlpull.v1.XmlPullParser.TEXT) current.append(p.text)
            else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && p.name == "si") { shared.add(current.toString()); inSi = false }
            event = p.next()
        }
    }
    val sheetName = entries.keys.firstOrNull { it == "xl/worksheets/sheet1.xml" } ?: return emptyList()
    val parser = android.util.Xml.newPullParser(); parser.setInput(entries[sheetName]!!.inputStream(), "UTF-8")
    val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); var cellType = ""; var cellValue = ""; var cellColumn = 0; var inV = false; var inT = false
    var event = parser.eventType
    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        when (event) {
            org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> row = mutableListOf()
                "c" -> { cellType = parser.getAttributeValue(null, "t") ?: ""; cellColumn = excelColumnIndex(parser.getAttributeValue(null, "r") ?: "A1") }
                "v" -> { inV = true; cellValue = "" }
                "t" -> { inT = true; cellValue = "" }
            }
            org.xmlpull.v1.XmlPullParser.TEXT -> if (inV || inT) cellValue += parser.text
            org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                "v", "t" -> { inV = false; inT = false }
                "c" -> { while (row.size <= cellColumn) row.add(""); row[cellColumn] = if (cellType == "s") shared.getOrNull(cellValue.toIntOrNull() ?: -1) ?: "" else cellValue; cellType = "" }
                "row" -> if (row.isNotEmpty()) rows.add(row)
            }
        }
        event = parser.next()
    }
    if (rows.isEmpty()) return emptyList()
    val headers = rows.first().map { normalizeHeader(it) }
    fun col(vararg names: String): Int = names.map { normalizeHeader(it) }.firstNotNullOfOrNull { headers.indexOf(it).takeIf { n -> n >= 0 } } ?: -1
    val nameI = col("officerName", "officer name", "name", "employee name")
    val desI = col("designation", "post")
    val deptI = col("officeDepartment", "office department", "department")
    val distI = col("district", "district name")
    val subI = col("subLocation", "sub location", "office", "location")
    val contactI = col("contactNo", "contact no", "contact number", "mobile", "phone")
    val emailI = col("email", "email id", "email address")
    val remarkI = col("remark", "remarks", "notes")
    val sectionI = col("sectionCell", "section cell", "section", "cell", "section / cell")
    val employeeIdI = col("employeeId", "employee id", "employeeid", "emp id", "empid")
    val dobI = col("dob", "date of birth", "birth date")
    if (nameI < 0) return emptyList()
    return rows.drop(1).mapIndexedNotNull { index, r ->
        fun value(i: Int) = if (i in r.indices) r[i].trim() else ""
        val name = value(nameI); if (name.isBlank()) return@mapIndexedNotNull null
        var district = value(distI)
        var subLocation = value(subI)
        if (district.contains(",") && subLocation.isBlank()) {
            val parts = district.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) { subLocation = parts.dropLast(1).joinToString(", "); district = parts.last() }
        }
        Officer(index + 1, name, value(desI), value(deptI).ifBlank { "ELECTION DEPARTMENT" }, district, subLocation, value(sectionI), value(employeeIdI), value(dobI), normalizeContacts(value(contactI)), value(emailI), value(remarkI))
    }
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
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(o.officerName); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Text(o.officerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.designation, color = Navy, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (isCeoHqStaff(o)) {
                        Spacer(Modifier.width(7.dp))
                        Surface(color = Gold.copy(.14f), shape = RoundedCornerShape(7.dp)) {
                            Text("CEO HQ", color = Color(0xFF8A5A00), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(12.dp)); if (o.sectionCell.isNotBlank()) DetailRow(Icons.Default.Work, "Section / Cell: ${o.sectionCell}")
            if (o.employeeId.isNotBlank()) DetailRow(Icons.Default.Person, "Employee ID: ${o.employeeId}")
            if (o.dob.isNotBlank()) DetailRow(Icons.Default.Cake, "DOB: ${o.dob}")
            Spacer(Modifier.height(12.dp)); Surface(color = Gold.copy(.13f), shape = RoundedCornerShape(9.dp)) { Text(o.officeDepartment, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF704000), style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.height(12.dp)); DetailRow(Icons.Default.LocationOn, "${o.locationLabel()}" )
            Spacer(Modifier.height(10.dp)); Text("Contact", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            o.mobile()?.let { ContactLine("Mobile", it, Icons.Default.Phone) { dial(context, it) } }
            o.officeNumbers().forEach { ContactLine("Office / EPABX", it, Icons.Default.Phone) { dial(context, it) } }
            if (o.email.isNotBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Email, null, tint = Navy); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Email", color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(o.email) }; IconButton({ clipboard.setText(AnnotatedString(o.email)); toast(context, "Email copied") }) { Icon(Icons.Default.ContentCopy, "Copy email") } }
            if (o.remark.isNotBlank()) { Spacer(Modifier.height(7.dp)); Text("Remarks", fontWeight = FontWeight.Bold); Text(o.remark, color = Color.Gray) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton({ openDirections(context, o.locationLabel()) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = o.locationLabel().isNotBlank()) {
                Icon(Icons.Default.Navigation, null)
                Spacer(Modifier.width(7.dp))
                Text("Directions")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Call", Icons.Default.Call, Green, o.mobile() != null) { o.mobile()?.let { dial(context, it) } }
                ActionButton("Email", Icons.Default.Email, Navy, o.email.isNotBlank()) { email(context, o.email) }
                ActionButton("Share", Icons.Default.Share, Gold, true) { shareContact(context, o) }
                ActionButton("Save", Icons.Default.PersonAdd, Color(0xFF596574), true) { saveContact(context, o) }
            }
            if (o.mobile() != null) { Spacer(Modifier.height(8.dp)); OutlinedButton({ whatsapp(context, o.mobile()!!) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Icon(Icons.Default.Chat, null); Spacer(Modifier.width(7.dp)); Text("WhatsApp", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

@Composable
fun ContactLine(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Navy); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.SemiBold) }; IconButton(onCall) { Icon(Icons.Default.Call, "Call") } }
}

@Composable
fun RowScope.ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
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

fun openDirections(context: Context, location: String) {
    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(location))
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { toast(context, "Maps is not available") }
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