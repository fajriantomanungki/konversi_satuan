package id.go.konversisatuan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import id.go.konversisatuan.data.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlinx.coroutines.launch
import java.util.UUID

private val Navy = Color(0xFF142B4A)
private val Teal = Color(0xFF138B83)
private val Canvas = Color(0xFFF4F7FA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = LocalDb.get(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Teal, background = Canvas)) { KonversiApp(db) } }
    }
}

@Composable
private fun KonversiApp(db: LocalDb) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = db.conversions()
    val all by dao.all().collectAsStateWithLifecycle(initialValue = emptyList())
    var role by remember { mutableStateOf(UserRole.ENUMERATOR) }
    var kabupaten by remember { mutableStateOf("Palu") }
    var page by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Conversion?>(null) }
    val preferences = remember { context.getSharedPreferences("konversi-settings", android.content.Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(preferences.getString("server_url", "") ?: "") }
    var accessToken by remember { mutableStateOf(preferences.getString("access_token", "") ?: "") }
    val gson = remember { GsonBuilder().setPrettyPrinting().create() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(gson.toJson(ConversionFile(conversions = all.filter { row -> row.approved }))) } }
                .onSuccess { message = "Katalog berhasil diekspor." }.onFailure { message = "Ekspor gagal: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                gson.fromJson(raw, ConversionFile::class.java).conversions.also { dao.put(it.map { row -> row.copy(approved = true) }) }
            }.onSuccess { message = "Katalog berhasil diimpor dan siap dipakai offline." }.onFailure { message = "File tidak valid: ${it.message}" }
        }
    }

    Scaffold(containerColor = Canvas, bottomBar = {
        NavigationBar {
            NavigationBarItem(page == 0, { page = 0 }, icon = { Icon(Icons.Default.Calculate, null) }, label = { Text("Kalkulator") })
            NavigationBarItem(page == 1, { page = 1 }, icon = { Icon(Icons.Default.Add, null) }, label = { Text(if (role == UserRole.SUPERVISOR) "Pendataan" else "Data") })
            NavigationBarItem(page == 2, { page = 2 }, icon = { Icon(Icons.Default.FactCheck, null) }, label = { Text("Pemeriksaan") })
            NavigationBarItem(page == 3, { page = 3 }, icon = { Icon(Icons.Default.CloudSync, null) }, label = { Text("Sinkron") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(Navy, CircleShape), contentAlignment = Alignment.Center) { Text("KS", color = Color.White, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("Konversi Satuan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text("Offline • $kabupaten", style = MaterialTheme.typography.labelMedium, color = Teal) }
            }
            if (message.isNotBlank()) { AssistChip(onClick = { message = "" }, label = { Text(message) }); Spacer(Modifier.height(8.dp)) }
            when (page) {
                0 -> CalculatorPage(all.filter { it.approved }, selected, { selected = it }, kabupaten)
                1 -> if (role == UserRole.SUPERVISOR) EntryPage(all, kabupaten) { item -> scope.launch { dao.put(listOf(item)); message = "Data tersimpan sebagai draft untuk pemeriksaan admin." } } else CatalogPage(all.filter { it.approved })
                2 -> if (role == UserRole.ADMIN) ReviewPage(all.filter { !it.approved }, { row -> scope.launch {
                    val group = all.filter { !it.approved && it.normalizedName == row.normalizedName && it.quality.equals(row.quality, true) && it.localUnit.equals(row.localUnit, true) && it.standardUnit.equals(row.standardUnit, true) && it.kabupaten == row.kabupaten }
                    val remote = if (serverUrl.startsWith("https://") && accessToken.isNotBlank()) runCatching {
                        val base = serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
                        Retrofit.Builder().baseUrl(base).addConverterFactory(GsonConverterFactory.create()).build().create(SyncApi::class.java).approve("Bearer ${accessToken.trim()}", row.commodity, row.quality, row.localUnit, row.standardUnit).conversions
                    }.getOrNull() else null
                    if (!remote.isNullOrEmpty()) {
                        group.forEach { dao.delete(it.id) }
                        dao.put(remote.map { it.copy(approved = true) })
                        message = "Data server disetujui dan disinkronkan."
                    } else {
                        val mean = group.map { it.standardValue / it.localValue }.average()
                        val canonical = row.copy(standardValue = mean * row.localValue, localPrice = group.map { it.localPrice }.average().toLong(), approved = true, contributors = group.size, updatedAt = System.currentTimeMillis())
                        group.drop(1).forEach { dao.delete(it.id) }
                        dao.put(listOf(canonical))
                        message = "${group.size} catatan digabung dan disetujui secara lokal."
                    } }
                }, { row -> scope.launch { dao.delete(row.id) } }) else RoleInfoPage("Pemeriksaan admin", "Admin meninjau catatan pengawas dan mengesahkan nilai konversi sebelum digunakan petugas.")
                else -> SyncPage(role, { newRole -> role = newRole }, kabupaten, { kabupaten = it }, serverUrl, { serverUrl = it; preferences.edit().putString("server_url", it).apply() }, accessToken, { accessToken = it; preferences.edit().putString("access_token", it).apply() }, onExport = { exportLauncher.launch("katalog-konversi.json") }, onImport = { importLauncher.launch(arrayOf("application/json", "text/json")) }, onSync = {
                    val base = serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
                    if (!base.startsWith("https://")) { message = "Masukkan URL server HTTPS terlebih dahulu." }
                    else if (accessToken.isBlank()) { message = "Masukkan token akses dari endpoint login." }
                    else scope.launch {
                        runCatching {
                            val api = Retrofit.Builder().baseUrl(base).addConverterFactory(GsonConverterFactory.create()).build().create(SyncApi::class.java)
                            val bearer = "Bearer ${accessToken.trim()}"
                            when (role) {
                                UserRole.SUPERVISOR -> api.submit(bearer, all.filter { !it.approved && it.kabupaten.equals(kabupaten, true) })
                                UserRole.ADMIN -> { val pending = api.pending(bearer); dao.put(pending.conversions.map { it.copy(approved = false) }); api.approved(bearer).also { dao.put(it.conversions.map { row -> row.copy(approved = true) }) } }
                                UserRole.ENUMERATOR -> api.approved(bearer).also { dao.put(it.conversions.map { row -> row.copy(approved = true) }) }
                            }
                        }.onSuccess { message = "Sinkronisasi berhasil. Katalog lokal diperbarui." }.onFailure { message = "Sinkronisasi gagal: ${it.message}" }
                    }
                })
            }
        }
    }
}

@Composable private fun Heading(kicker: String, title: String, subtitle: String) {
    Text(kicker.uppercase(), color = Teal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Text(title, color = Navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Color(0xFF64748B), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(18.dp))
}

@Composable private fun CalculatorPage(rows: List<Conversion>, selected: Conversion?, onSelect: (Conversion) -> Unit, kabupaten: String) {
    var query by remember { mutableStateOf("") }; var quantity by remember { mutableStateOf("1") }; var price by remember { mutableStateOf("") }
    val filtered = rows.filter { it.kabupaten.equals(kabupaten, true) && (query.isBlank() || it.commodity.contains(query, true)) }
    Heading("Alat hitung", "Kalkulator harga", "Konversi berdasarkan hasil pengukuran yang disetujui.")
    OutlinedTextField(query, { query = it }, label = { Text("Cari komoditas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (filtered.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(selected?.let { "${it.commodity} · ${it.quality}" } ?: "Pilih komoditas", { expanded = true }, readOnly = true, label = { Text("Komoditas") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded, { expanded = false }) { filtered.forEach { item -> DropdownMenuItem(text = { Text("${item.commodity} · ${item.quality}") }, onClick = { onSelect(item); expanded = false }) } }
        }
    } else EmptyCard("Belum ada katalog disetujui untuk $kabupaten. Impor file katalog atau lakukan sinkronisasi.")
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(quantity, { quantity = it }, label = { Text("Jumlah ${selected?.localUnit ?: "lokal"}") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(price, { price = it }, label = { Text("Harga per ${selected?.localUnit ?: "lokal"} (Rp)") }, modifier = Modifier.weight(1.2f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }
    val q = quantity.replace(',', '.').toDoubleOrNull() ?: 0.0
    val p = price.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val ratio = selected?.let { it.standardValue / it.localValue } ?: 0.0
    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("HASIL KONVERSI", color = Color(0xFF9CC9CB), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("${fmt(q * ratio)} ${selected?.standardUnit ?: "satuan baku"}", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = .2f))
            Text("Harga per ${selected?.standardUnit ?: "satuan baku"}", color = Color.White.copy(alpha = .75f))
            Text(if (ratio > 0) "Rp ${fmt(p / ratio)}" else "Rp —", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${fmt(q)} ${selected?.localUnit ?: "lokal"} × ${fmt(ratio)} ${selected?.standardUnit ?: "baku"}/${selected?.localUnit ?: "lokal"}", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(12.dp)); Text("${rows.size} komoditas tersedia offline", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
}

@Composable private fun EntryPage(rows: List<Conversion>, kabupaten: String, onSave: (Conversion) -> Unit) {
    var commodity by remember { mutableStateOf("") }; var quality by remember { mutableStateOf("Sedang") }; var localUnit by remember { mutableStateOf("") }
    var localValue by remember { mutableStateOf("1") }; var standardUnit by remember { mutableStateOf("") }; var standardValue by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }
    val match = rows.firstOrNull { it.commodity.equals(commodity, true) && it.approved }
    LaunchedEffect(match?.id) { if (match != null && standardUnit.isBlank()) standardUnit = match.standardUnit }
    Heading("Pengumpulan data", "Catat satuan lokal", "Data disimpan di perangkat dan menunggu pemeriksaan admin.")
    if (match != null) AssistChip(onClick = { standardUnit = match.standardUnit }, label = { Text("Komoditas katalog · baku ${match.standardUnit}") })
    OutlinedTextField(commodity, { commodity = it }, label = { Text("Nama komoditas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    val suggestions = rows.filter { it.approved && it.kabupaten.equals(kabupaten, true) && commodity.isNotBlank() && it.commodity.contains(commodity, true) }.distinctBy { it.commodity.lowercase() to it.quality.lowercase() }.take(5)
    if (suggestions.isNotEmpty()) {
        Text("Pilih dari katalog komoditas", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
        suggestions.forEach { suggestion -> AssistChip(onClick = { commodity = suggestion.commodity; quality = suggestion.quality; standardUnit = suggestion.standardUnit }, label = { Text("${suggestion.commodity} · baku ${suggestion.standardUnit}") }) }
    }
    OutlinedTextField(quality, { quality = it }, label = { Text("Kualitas barang") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField(localValue, { localValue = it }, "Nilai satuan lokal", Modifier.weight(1f)); OutlinedTextField(localUnit, { localUnit = it }, label = { Text("Satuan lokal") }, modifier = Modifier.weight(1f), singleLine = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField(standardValue, { standardValue = it }, "Nilai konversi", Modifier.weight(1f)); OutlinedTextField(standardUnit, { standardUnit = it }, label = { Text("Satuan baku") }, modifier = Modifier.weight(1f), singleLine = true) }
    NumberField(price, { price = it }, "Harga satuan lokal (Rp)", Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Button(onClick = {
        val lv = localValue.replace(',', '.').toDoubleOrNull() ?: 0.0; val sv = standardValue.replace(',', '.').toDoubleOrNull() ?: 0.0; val pr = price.filter { it.isDigit() }.toLongOrNull() ?: 0L
        if (commodity.isNotBlank() && localUnit.isNotBlank() && standardUnit.isNotBlank() && lv > 0 && sv > 0 && pr > 0) onSave(Conversion(UUID.randomUUID().toString(), commodity.trim(), quality.trim(), localUnit.trim(), lv, standardUnit.trim(), sv, pr, kabupaten))
    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Simpan draft pengawas") }
}

@Composable private fun NumberField(value: String, change: (String) -> Unit, label: String, modifier: Modifier) = OutlinedTextField(value, change, label = { Text(label) }, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)

@Composable private fun ReviewPage(pending: List<Conversion>, onApprove: (Conversion) -> Unit, onDelete: (Conversion) -> Unit) {
    Heading("Validasi", "Pemeriksaan data", "Catatan dengan komoditas dan kabupaten yang sama dirata-ratakan saat disetujui.")
    if (pending.isEmpty()) EmptyCard("Tidak ada kiriman pengawas yang menunggu pemeriksaan.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(pending, key = { it.id }) { row ->
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp)) {
            Text("${row.commodity} · ${row.quality}", color = Navy, fontWeight = FontWeight.Bold)
            Text("${fmt(row.localValue)} ${row.localUnit} = ${fmt(row.standardValue)} ${row.standardUnit} · Rp ${fmt(row.localPrice)} · ${row.kabupaten}", color = Color(0xFF64748B))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onDelete(row) }) { Text("Hapus") }; Button(onClick = { onApprove(row) }) { Text("Setujui") } }
        } }
    } }
}

@Composable private fun CatalogPage(rows: List<Conversion>) {
    Heading("Data lokal", "Katalog konversi", "Daftar komoditas yang telah disetujui dan tersedia di perangkat.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(rows, key = { it.id }) { r -> Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(14.dp)) { Text("${r.commodity} · ${r.quality}", fontWeight = FontWeight.Bold, color = Navy); Text("${fmt(r.localValue)} ${r.localUnit} = ${fmt(r.standardValue)} ${r.standardUnit} · ${r.kabupaten}", color = Color(0xFF64748B)) } } } }
}

@Composable private fun SyncPage(role: UserRole, onRole: (UserRole) -> Unit, kabupaten: String, onKabupaten: (String) -> Unit, serverUrl: String, onServerUrl: (String) -> Unit, accessToken: String, onAccessToken: (String) -> Unit, onExport: () -> Unit, onImport: () -> Unit, onSync: () -> Unit) {
    Heading("Pengaturan perangkat", "Siap tanpa jaringan", "Kelola wilayah dan pertukaran katalog antarperangkat.")
    Text("Peran perangkat", color = Navy, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { UserRole.entries.forEach { r -> FilterChip(selected = role == r, onClick = { onRole(r) }, label = { Text(r.label) }) } }
    OutlinedTextField(kabupaten, onKabupaten, label = { Text("Kabupaten/Kota tugas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Spacer(Modifier.height(12.dp))
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp)) {
        Text("Transfer katalog", color = Navy, fontWeight = FontWeight.Bold)
        Text("Admin ekspor katalog yang disetujui, lalu petugas mengimpornya untuk pemakaian offline.", color = Color(0xFF64748B))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onExport) { Icon(Icons.Default.Download, null); Text("Ekspor") }; OutlinedButton(onClick = onImport) { Icon(Icons.Default.Upload, null); Text("Impor") } }
    } }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(serverUrl, onServerUrl, label = { Text("URL server API (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(accessToken, onAccessToken, label = { Text("Token akses") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CloudSync, null); Text("Sinkronkan dengan server") }
    Text("Sinkronisasi memakai token akses server. Siapkan akun dan API sesuai petunjuk server/README.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
}

@Composable private fun RoleInfoPage(title: String, body: String) { Heading("Peran pengguna", title, body); EmptyCard("Ganti peran perangkat pada menu Sinkron untuk membuka alur terkait.") }
@Composable private fun EmptyCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp), color = Color(0xFF64748B)) } }
private fun fmt(value: Double): String = String.format(java.util.Locale("id", "ID"), "%,.2f", value).trimEnd('0').trimEnd(',').replace('.', ',')
private fun fmt(value: Long): String = String.format(java.util.Locale("id", "ID"), "%,d", value)
