package com.rajasthan.election.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
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
    val isFavorite: Boolean = false
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
    @Query("DELETE FROM officers")
    suspend fun deleteAll()
    @Query("UPDATE officers SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Int, favorite: Boolean)
}

@Database(entities = [Officer::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun officerDao(): OfficerDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE officers ADD COLUMN sectionCell TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE officers ADD COLUMN employeeId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE officers ADD COLUMN dob TEXT NOT NULL DEFAULT ''")
    }
}

class OfficerRepository(private val dao: OfficerDao) {
    val officers = dao.observeAll()
    suspend fun setFavorite(id: Int, favorite: Boolean) = dao.setFavorite(id, favorite)
    suspend fun replace(items: List<Officer>) { dao.deleteAll(); dao.insertAll(items) }
}

enum class AppTab(val label:String){HOME("Home"),DIRECTORY("Directory"),SEARCH("Search"),FAVORITES("Favorites"),MORE("More")}
data class DirectoryUiState(
 val query:String="",val district:String="Jaipur",val designation:String="All",val department:String="All",val office:String="All",val sectionCell:String="All",val tab:AppTab=AppTab.HOME,val darkMode:Boolean=false)
class DirectoryViewModel(private val repo:OfficerRepository):ViewModel(){
 val officers=repo.officers.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 private val _state=MutableStateFlow(DirectoryUiState());val state=_state.asStateFlow()
 private val _recent=MutableStateFlow<List<Int>>(emptyList());val recentlyViewed=_recent.asStateFlow()
 val districts=officers.map{listOf("All")+it.flatMap{v->v.district.split(",").map(String::trim)}.filter(String::isNotBlank).distinct().sorted()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),listOf("All"))
 val designations=officers.map{listOf("All")+it.map{v->v.designation.trim()}.filter(String::isNotBlank).distinct().sorted()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),listOf("All"))
 val departments=officers.map{listOf("All")+it.map{v->v.officeDepartment.trim()}.filter(String::isNotBlank).distinct().sorted()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),listOf("All"))
 val offices=officers.map{listOf("All")+it.map{v->v.subLocation.trim()}.filter(String::isNotBlank).distinct().sorted()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),listOf("All"))
 val sectionCells=officers.map{listOf("All")+it.map{v->v.sectionCell.trim()}.filter(String::isNotBlank).distinct().sorted()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),listOf("All"))
 val filtered=combine(officers,state){all,st->val q=st.query.trim().lowercase(Locale.getDefault());all.filter{o->
  val fields=listOf(o.officerName,o.designation,o.officeDepartment,o.district,o.subLocation,o.sectionCell,o.employeeId,o.dob,o.email,o.contactNumbers,o.remark)
  (q.isBlank()||fields.any{it.lowercase(Locale.getDefault()).contains(q)}) &&
  (st.district=="All"||o.district.split(",").any{it.trim().equals(st.district,true)}) &&
  (st.designation=="All"||o.designation.equals(st.designation,true)) &&
  (st.department=="All"||o.officeDepartment.equals(st.department,true)) &&
  (st.office=="All"||o.subLocation.equals(st.office,true)) &&
  (st.sectionCell=="All"||o.sectionCell.equals(st.sectionCell,true))
 }}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val favorites=officers.map{it.filter(Officer::isFavorite)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 fun setQuery(v:String)=_state.update{it.copy(query=v)};fun setDistrict(v:String)=_state.update{it.copy(district=v)}
 fun setDesignation(v:String)=_state.update{it.copy(designation=v)};fun setDepartment(v:String)=_state.update{it.copy(department=v)}
 fun setOffice(v:String)=_state.update{it.copy(office=v)};fun setSectionCell(v:String)=_state.update{it.copy(sectionCell=v)}
 fun setTab(v:AppTab)=_state.update{it.copy(tab=v)}
 fun clearFilters()=_state.update{it.copy(query="",district="All",designation="All",department="All",office="All",sectionCell="All")}
 fun clearFilterOnly()=_state.update{it.copy(query="",district="Jaipur",designation="All",department="All",office="All",sectionCell="All")}
 fun setDarkMode(v:Boolean)=_state.update{it.copy(darkMode=v)};fun replaceData(items:List<Officer>)=viewModelScope.launch{repo.replace(items)}
 fun toggleFavorite(o:Officer)=viewModelScope.launch{repo.setFavorite(o.id,!o.isFavorite)}
 fun recordViewed(o:Officer){_recent.update{(listOf(o.id)+it.filter{v->v!=o.id}).take(8)}}
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ElectionDirectoryApp() }
    }
}

@Composable
fun ElectionDirectoryApp(){
 val vm=rememberDirectoryViewModel();val state by vm.state.collectAsState();val all by vm.officers.collectAsState();val filtered by vm.filtered.collectAsState();val favorites by vm.favorites.collectAsState();val recent by vm.recentlyViewed.collectAsState()
 var selected by remember{mutableStateOf<Officer?>(null)};var showFilters by remember{mutableStateOf(false)};var showAbout by remember{mutableStateOf(false)};var showSettings by remember{mutableStateOf(false)}
 val scheme=if(state.darkMode)darkColorScheme(primary=Color(0xFF9FB5FF),secondary=Color(0xFFFFC15A),background=Color(0xFF0D1320))else lightColorScheme(primary=Navy,secondary=Gold,background=Background,surface=SurfaceWhite)
 MaterialTheme(colorScheme=scheme){Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
  NavigationBar(containerColor=SurfaceWhite,tonalElevation=4.dp){
   NavigationBarItem(state.tab==AppTab.HOME,{vm.setTab(AppTab.HOME)},{Icon(Icons.Default.Home,"Home")},{Text("Home")})
   NavigationBarItem(state.tab==AppTab.DIRECTORY,{vm.setTab(AppTab.DIRECTORY)},{Icon(Icons.Default.People,"Directory")},{Text("Directory")})
   NavigationBarItem(state.tab==AppTab.SEARCH,{vm.setTab(AppTab.SEARCH)},{Icon(Icons.Default.Search,"Search")},{Text("Search")})
   NavigationBarItem(state.tab==AppTab.FAVORITES,{vm.setTab(AppTab.FAVORITES)},{Icon(Icons.Default.Star,"Favorites")},{Text("Favorites")})
   NavigationBarItem(state.tab==AppTab.MORE,{vm.setTab(AppTab.MORE)},{Icon(Icons.Default.MoreHoriz,"More")},{Text("More")})
  }
 }){padding->Column(Modifier.fillMaxSize().padding(padding)){
  Header(onAbout={showAbout=true},onSettings={showSettings=true})
  when(state.tab){
   AppTab.HOME->HomeScreen(vm,all,recent,{vm.setQuery(it);vm.setTab(AppTab.SEARCH)},{vm.setTab(AppTab.DIRECTORY)},{vm.recordViewed(it);selected=it},{showFilters=true})
   AppTab.DIRECTORY->DirectoryScreen(vm,filtered,state,{vm.recordViewed(it);selected=it},{showFilters=true})
   AppTab.SEARCH->SearchScreen(vm,filtered,state,{vm.recordViewed(it);selected=it},{showFilters=true})
   AppTab.FAVORITES->FavoritesScreen(favorites,{vm.recordViewed(it);selected=it},vm::toggleFavorite)
   AppTab.MORE->MoreScreen(all,{vm.setTab(AppTab.DIRECTORY);vm.clearFilterOnly()},{vm.setTab(AppTab.MORE)},{showSettings=true},{showAbout=true})
  }
 }}
 }
 selected?.let{OfficerDetails(it,{selected=null}){vm.toggleFavorite(it)}};if(showFilters)FilterSheet(vm,all){showFilters=false};if(showAbout)AboutSheet(all.size){showAbout=false};if(showSettings)SettingsSheet(state.darkMode,vm::setDarkMode,{vm.replaceData(it)}){showSettings=false}
}
@Composable fun HomeScreen(vm:DirectoryViewModel,all:List<Officer>,recentIds:List<Int>,onSearch:(String)->Unit,onDirectory:()->Unit,onOpen:(Officer)->Unit,onFilters:()->Unit){
 var q by remember{mutableStateOf("")};val recent=recentIds.mapNotNull{id->all.firstOrNull{it.id==id}}.take(4);val today=all.filter{isBirthdayToday(it.dob)}
 val des=all.map{it.designation}.filter(String::isNotBlank).distinct().size;val sec=all.map{it.sectionCell}.filter(String::isNotBlank).distinct().size
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{Surface(Modifier.fillMaxWidth(),color=Navy,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){
   Text("Welcome",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelLarge);Text("Find the right contact, quickly.",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineSmall)
   Spacer(Modifier.height(5.dp));Text("Search staff, offices and official contact details from one place.",color=Color.White.copy(.82f));Spacer(Modifier.height(14.dp))
   OutlinedTextField(q,{q=it;if(it.isNotBlank())vm.setQuery(it)},Modifier.fillMaxWidth().heightIn(min=56.dp),singleLine=true,shape=RoundedCornerShape(16.dp),placeholder={Text("Search name, district, phone, email…")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(q.isNotBlank())IconButton({q="";vm.setQuery("")}){Icon(Icons.Default.Clear,"Clear")}},colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=SurfaceWhite,unfocusedContainerColor=SurfaceWhite,focusedBorderColor=Color.Transparent,unfocusedBorderColor=Color.Transparent))
   Spacer(Modifier.height(6.dp));Text("Offline directory • "+all.size+" records",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelSmall);if(q.isNotBlank())TextButton({onSearch(q)},Modifier.fillMaxWidth().height(44.dp),colors=ButtonDefaults.textButtonColors(contentColor=Color.White)){Text("View search results")}
  }}}
  item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){HomeStatCard(Icons.Default.People,all.size.toString(),"TOTAL STAFF",Navy,Modifier.weight(1f));HomeStatCard(Icons.Default.LocationOn,"41","DISTRICTS",Green,Modifier.weight(1f))}}
  item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){HomeStatCard(Icons.Default.Badge,des.toString(),"DESIGNATIONS",Gold,Modifier.weight(1f));HomeStatCard(Icons.Default.Work,sec.toString(),"SECTIONS / CELLS",Color(0xFF6F42C1),Modifier.weight(1f))}}
  item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){QuickActionCard(Icons.Default.People,"Directory","Browse staff",Modifier.weight(1f),onDirectory);QuickActionCard(Icons.Default.FilterAlt,"Filters","Narrow results",Modifier.weight(1f),onFilters)}}
  item{SectionTitle("Quick access","Common tasks")}
  item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){QuickActionCard(Icons.Default.Cake,"Birthdays","Today & upcoming",Modifier.weight(1f)){vm.setTab(AppTab.MORE)};QuickActionCard(Icons.Default.Star,"Favorites","Saved contacts",Modifier.weight(1f)){vm.setTab(AppTab.FAVORITES)}}}
  if(today.isNotEmpty()){item{SectionTitle("Today's birthdays",today.size.toString()+" contacts")};items(today.take(3),key={it.id}){CompactPreviewCard(it,onOpen)}}
  if(recent.isNotEmpty()){item{SectionTitle("Recently viewed","Your last opened contacts")};items(recent,key={it.id}){CompactPreviewCard(it,onOpen)}}
 }
}
@Composable fun DirectoryScreen(vm:DirectoryViewModel,list:List<Officer>,state:DirectoryUiState,onOpen:(Officer)->Unit,onFilter:()->Unit){
 Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Staff Directory",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=TextDark);Text(if(state.district=="All")"All Rajasthan staff" else state.district+" staff",color=TextMuted,style=MaterialTheme.typography.bodySmall)};Surface(color=Green.copy(.10f),shape=RoundedCornerShape(10.dp)){Text("OFFLINE",color=Green,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp))}}
 SearchField(state.query,vm::setQuery);Spacer(Modifier.height(8.dp));Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){FilterButton("District",state.district,Modifier.widthIn(min=120.dp)){onFilter()};FilterButton("Designation",state.designation,Modifier.widthIn(min=140.dp)){onFilter()};FilterButton("Department",state.department,Modifier.widthIn(min=140.dp)){onFilter()};FilterButton("Office",state.office,Modifier.widthIn(min=120.dp)){onFilter()}}
 Row(Modifier.fillMaxWidth().height(42.dp),verticalAlignment=Alignment.CenterVertically){Text(list.size.toString()+" contacts",color=TextDark,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));if(state.query.isNotBlank()||state.district!="Jaipur"||state.designation!="All"||state.department!="All"||state.office!="All"||state.sectionCell!="All")TextButton(vm::clearFilterOnly,modifier=Modifier.height(40.dp)){Text("Reset")}}
 if(list.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){EmptyState("No contacts found","Try another search or filter.")}else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(top=2.dp,bottom=16.dp)){items(list,key={it.id}){ModernOfficerCard(it,onOpen,vm::toggleFavorite)}}
 }
}
@Composable fun SearchScreen(vm:DirectoryViewModel,list:List<Officer>,state:DirectoryUiState,onOpen:(Officer)->Unit,onFilter:()->Unit){
 Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){Text("Search contacts",Modifier.padding(top=14.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=TextDark);Text("Name, district, designation, department, office, phone or email.",color=TextMuted);Spacer(Modifier.height(12.dp));SearchField(state.query,vm::setQuery)
 Spacer(Modifier.height(9.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onFilter,Modifier.weight(1f).height(48.dp),shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.FilterAlt,null);Spacer(Modifier.width(6.dp));Text("Filters")};OutlinedButton(vm::clearFilters,Modifier.weight(1f).height(48.dp),shape=RoundedCornerShape(13.dp)){Text("Clear search")}}
 Spacer(Modifier.height(8.dp));Text(list.size.toString()+" matching contacts",color=TextMuted,style=MaterialTheme.typography.labelLarge);Spacer(Modifier.height(6.dp));if(list.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){EmptyState("Nothing matched","Try a name, mobile number, district or email address.")}else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(top=2.dp,bottom=16.dp)){items(list,key={it.id}){ModernOfficerCard(it,onOpen,vm::toggleFavorite)}}
 }
}
@Composable fun MoreScreen(all:List<Officer>,onOffices:()->Unit,onBirthdays:()->Unit,onSettings:()->Unit,onAbout:()->Unit){
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("More",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=TextDark);Text("Tools and information",color=TextMuted)};item{MoreOption(Icons.Default.Business,"Offices","Browse staff by district and location",onOffices)};item{MoreOption(Icons.Default.Cake,"Birthdays","Today and upcoming birthdays",onBirthdays)};item{MoreOption(Icons.Default.Settings,"Settings","Appearance and administrator data tools",onSettings)};item{MoreOption(Icons.Default.Info,"About this app",all.size.toString()+" offline directory records",onAbout)}}
}
@Composable fun MoreOption(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,subtitle:String,onClick:()->Unit){Card(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=SurfaceWhite),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Navy.copy(.08f)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Navy)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,color=TextDark,fontWeight=FontWeight.Bold);Text(subtitle,color=TextMuted,style=MaterialTheme.typography.bodySmall)};Icon(Icons.Default.ChevronRight,null,tint=TextMuted)}}}
@Composable fun ModernOfficerCard(o:Officer,onOpen:(Officer)->Unit,onFavorite:(Officer)->Unit){
 val c=LocalContext.current;Card(Modifier.fillMaxWidth().clickable{onOpen(o)},shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=SurfaceWhite),border=androidx.compose.foundation.BorderStroke(1.dp,Border),elevation=CardDefaults.cardElevation(defaultElevation=1.dp)){Column(Modifier.padding(13.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){InitialAvatar(o.officerName);Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(o.officerName,fontWeight=FontWeight.Bold,color=TextDark,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(o.designation.ifBlank{"Designation not available"},color=Navy,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)};IconButton({onFavorite(o)},modifier=Modifier.size(48.dp)){Icon(if(o.isFavorite)Icons.Default.Star else Icons.Default.StarBorder,"Favorite",tint=if(o.isFavorite)Gold else TextMuted)}}
  Spacer(Modifier.height(7.dp));Text("• "+o.locationLabel(),color=TextMuted,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis);if(o.officeDepartment.isNotBlank())Text("• "+o.officeDepartment,color=TextMuted,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis);if(o.sectionCell.isNotBlank())Text("• "+o.sectionCell,color=TextMuted,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
  Spacer(Modifier.height(8.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){ActionMini(Icons.Default.Call,"Call",o.mobile()!=null,Green){o.mobile()?.let{dial(c,it)}};ActionMini(Icons.Default.Email,"Email",o.email.isNotBlank(),Navy){if(o.email.isNotBlank())email(c,o.email)};ActionMini(Icons.Default.Navigation,"Directions",true,Navy){directions(c,o)}};Spacer(Modifier.height(7.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){ActionMini(Icons.Default.Share,"Share",true,Gold){shareContact(c,o)};ActionMini(Icons.Default.OpenInNew,"Details",true,Navy){onOpen(o)}}
 }}
}
@Composable fun ActionMini(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,enabled:Boolean,tint:Color,onClick:()->Unit){FilledTonalButton(onClick,enabled=enabled,modifier=Modifier.weight(1f).height(44.dp),shape=RoundedCornerShape(11.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=tint.copy(.10f),contentColor=tint),contentPadding=PaddingValues(horizontal=6.dp)){Icon(icon,label,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)}}
(all: List<Officer>, showing: Int, favorites: Int) {
    val statItems = listOf(
        Triple(Icons.Default.Person, all.size.toString(), "STAFF"),
        Triple(Icons.Default.LocationOn, 41.toString(), "DISTRICTS"),
        Triple(Icons.Default.Star, favorites.toString(), "FAVORITES"),
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
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Navy,
            unfocusedBorderColor = Border,
            focusedLeadingIconColor = Navy,
            unfocusedLeadingIconColor = TextMuted,
            cursorColor = Navy
        ),
        placeholder = { Text("Search name, mobile, office, email or ID...", color = TextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Default.Clear, "Clear") } }
    )
}

@Composable
fun FilterButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = value != "All"
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
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
fun CompactOfficerCard(o: Officer, onOpen: (Officer) -> Unit, onFavorite: (Officer) -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(o) },
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(o.officerName)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.officerName, fontWeight = FontWeight.Bold, color = TextDark, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(o.designation.ifBlank { "Designation not available" }, color = Navy, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onFavorite(o) }, modifier = Modifier.size(38.dp)) {
                    Icon(if (o.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (o.isFavorite) Gold else Color(0xFF7A8494), modifier = Modifier.size(21.dp))
                }
                Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = Navy)
                Spacer(Modifier.width(5.dp))
                Text(o.locationLabel().ifBlank { "Location not available" }, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                o.mobile()?.let { SmallAction("Call", Icons.Default.Call) { dial(context, it) } }
                if (o.email.isNotBlank()) {
                    Spacer(Modifier.width(5.dp))
                    SmallAction("Email", Icons.Default.Email) { email(context, o.email) }
                }
            }

            if (o.sectionCell.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Work, null, Modifier.size(15.dp), tint = TextMuted)
                    Spacer(Modifier.width(5.dp))
                    Text(o.sectionCell, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@Composable
fun OfficerCard(o: Officer, onOpen: (Officer) -> Unit, onFavorite: (Officer) -> Unit) {
    CompactOfficerCard(o, onOpen, onFavorite)
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
fun BirthdaysScreen(list: List<Officer>, onOpen: (Officer) -> Unit, onFavorite: (Officer) -> Unit) {
    val today = list.filter { isBirthdayToday(it.dob) }.sortedBy { it.officerName.lowercase(Locale.getDefault()) }
    val upcoming = list.filter { it.dob.isNotBlank() && !isBirthdayToday(it.dob) }
        .sortedBy { daysUntilBirthday(it.dob) }
        .take(20)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Birthdays", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
        Text("Birth dates are matched by day and month; the birth year is not required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (today.isNotEmpty()) {
            Surface(color = Gold.copy(.14f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Cake, null, tint = Gold); Spacer(Modifier.width(8.dp)); Text("Today's Birthdays", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(8.dp))
                    today.forEach { BirthdayRow(it, onOpen, onFavorite) }
                }
            }
        } else {
            EmptyState("No birthday today", if (upcoming.isEmpty()) "No DOB information is available yet." else "No birthday today. Upcoming birthdays are shown below.")
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(18.dp)); Text("Upcoming", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(7.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(upcoming, key = { "b-${it.id}" }) { BirthdayRow(it, onOpen, onFavorite) }
            }
        }
    }
}

@Composable
fun BirthdayRow(o: Officer, onOpen: (Officer) -> Unit, onFavorite: (Officer) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(o) }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(o.officerName); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                Text(o.officerName, fontWeight = FontWeight.Bold); Text(o.designation, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatDob(o.dob), color = Navy, style = MaterialTheme.typography.labelMedium)
            }; IconButton({ onFavorite(o) }) { Icon(if (o.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (o.isFavorite) Gold else Color.Gray) }
        }
    }
}

@Composable
fun FavoritesScreen(list: List<Officer>, onOpen: (Officer) -> Unit, onFavorite: (Officer) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Favorite Contacts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextDark)
        Text("Quick access to frequently contacted staff", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) EmptyState("No favorites yet", "Tap the star on any staff member to add them here.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(list, key = { it.id }) { OfficerCard(it, onOpen, onFavorite) } }
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
    val displaySelected = when {
        selected != "All" -> selected
        label == "District" -> "All Districts"
        label == "Designation" -> "All Designations"
        else -> "All Sections / Cells"
    }

    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Box {
            OutlinedButton(
                { expanded = true },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    displaySelected,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Default.ExpandMore, null)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                values.forEach { value ->
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
                                Text(display)
                            }
                        },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(darkMode: Boolean, onDarkMode: (Boolean) -> Unit, onImport: (List<Officer>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showAdminLogin by remember { mutableStateOf(false) }
    var adminAuthenticated by remember { mutableStateOf(false) }
    var pendingImportType by remember { mutableStateOf("") }

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
            OutlinedButton({ startImport("excel") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Default.Upload, null); Spacer(Modifier.width(7.dp)); Text("Import Directory Excel")
            }
            Spacer(Modifier.height(7.dp))
            OutlinedButton({ startImport("json") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Default.Code, null); Spacer(Modifier.width(7.dp)); Text("Import Directory JSON")
            }
            Spacer(Modifier.height(7.dp))
            Text("Import replaces the current local directory. The first worksheet is matched by header names. Optional fields supported: Section / Cell, Employee ID, DOB, Email and Remark.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
            add(Officer(o.optInt("id", i + 1), name, o.optString("designation"), o.optString("officeDepartment", "ELECTION DEPARTMENT").ifBlank { "ELECTION DEPARTMENT" }, district, sub, o.optString("sectionCell", o.optString("section")), o.optString("employeeId", o.optString("employeeID")), o.optString("dob"), contacts, o.optString("email"), o.optString("remark"), o.optBoolean("isFavorite", false)))
        }
    }
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
fun OfficerDetails(o: Officer, onDismiss: () -> Unit, onFavorite: () -> Unit) {
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(o.officerName); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(o.officerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(o.designation, color = Navy, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                IconButton(onFavorite) { Icon(if (o.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (o.isFavorite) Gold else Color.Gray) }
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
            Spacer(Modifier.height(15.dp))
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
    FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text(label, style = MaterialTheme.typography.labelMedium) }
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

fun directions(context:Context,o:Officer){val destination=Uri.encode(o.locationLabel().ifBlank{o.officeDepartment});runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+destination)))}.onFailure{toast(context,"No maps application is available")}}

fun dial(context: Context, number: String) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}"))) }
fun email(context: Context, address: String) { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address"))) }
fun whatsapp(context: Context, number: String) { val clean = number.filter(Char::isDigit); try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$clean"))) } catch (_: Exception) { toast(context, "WhatsApp is not available") } }
fun shareContact(context: Context, o: Officer) { val body = buildString { append(o.officerName).append('\n'); append(o.designation).append('\n'); append(o.officeDepartment).append('\n'); append("Location: ${o.locationLabel()}\n"); if (o.phones().isNotEmpty()) append("Phone: ${o.phones().joinToString(", ")}\n"); if (o.email.isNotBlank()) append("Email: ${o.email}\n"); if (o.remark.isNotBlank()) append("Remarks: ${o.remark}") }; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, "Share Contact")) }
fun saveContact(context: Context, o: Officer) { val i = Intent(Intent.ACTION_INSERT).apply { type = ContactsContract.RawContacts.CONTENT_TYPE; putExtra(ContactsContract.Intents.Insert.NAME, o.officerName); putExtra(ContactsContract.Intents.Insert.PHONE, o.mobile()); putExtra(ContactsContract.Intents.Insert.EMAIL, o.email); putExtra(ContactsContract.Intents.Insert.COMPANY, o.officeDepartment); putExtra(ContactsContract.Intents.Insert.JOB_TITLE, o.designation) }; context.startActivity(i) }
fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
fun rememberDirectoryViewModel(): DirectoryViewModel {
    val context = LocalContext.current
    val db = remember { Room.databaseBuilder(context, AppDatabase::class.java, "election_directory.db").addMigrations(MIGRATION_1_2).build() }
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
            add(Officer(x.getInt("id"), x.getString("officerName"), x.getString("designation"), x.getString("officeDepartment"), x.getString("district"), x.optString("subLocation"), x.optString("sectionCell"), x.optString("employeeId"), x.optString("dob"), buildList { for (j in 0 until nums.length()) add(nums.getString(j)) }.joinToString("|"), x.optString("email"), x.optString("remark")))
        }
    }
    repo.replace(seed)
}
