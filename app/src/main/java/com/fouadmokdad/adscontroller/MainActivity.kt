package com.fouadmokdad.adscontroller

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

data class InstalledApp(val label: String, val packageName: String, val paths: List<String>)

class MainActivity : ComponentActivity() {
    private val apps = mutableStateListOf<InstalledApp>()
    private var report by mutableStateOf("Choose a file or an installed app to begin.")
    private var busy by mutableStateOf(false)
    private var showApps by mutableStateOf(false)
    private var query by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scan("Selected file") {
                        listOf(contentResolver.openInputStream(uri) ?: error("Cannot open selected file"))
                    }
                }
                Scaffold(topBar = { Surface(tonalElevation = 3.dp) {
                    Text("FM Ads Controller", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                } }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                        Text("On-device APK inspector • No root", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Choose file") }
                            OutlinedButton(onClick = { loadApps() }, enabled = !busy) { Text("Installed apps") }
                        }
                        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working…") }
                        Spacer(Modifier.height(12.dp))
                        if (showApps) {
                            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Tap an app to inspect its base and split APKs.", style = MaterialTheme.typography.bodySmall)
                            LazyColumn {
                                items(apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        showApps = false
                                        scan(app.label) { app.paths.map { FileInputStream(it) } }
                                    }.padding(vertical = 10.dp)) {
                                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                        HorizontalDivider()
                                    }
                                }
                            }
                        } else {
                            LazyColumn { item { Text(report) } }
                        }
                    }
                }
            }
        }
    }

    private fun loadApps() {
        busy = true
        thread {
            try {
                @Suppress("DEPRECATION")
                val found = packageManager.getInstalledApplications(0)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { app ->
                        InstalledApp(packageManager.getApplicationLabel(app).toString(), app.packageName,
                            listOfNotNull(app.publicSourceDir) + (app.splitPublicSourceDirs?.toList() ?: emptyList()))
                    }.sortedBy { it.label.lowercase() }
                runOnUiThread { apps.clear(); apps.addAll(found); query = ""; showApps = true; busy = false }
            } catch (e: Exception) {
                runOnUiThread { report = "Cannot list apps: ${e.message}"; showApps = false; busy = false }
            }
        }
    }

    private fun scan(name: String, streams: () -> List<InputStream>) {
        busy = true
        report = "Scanning $name…"
        thread {
            try {
                val found = sortedSetOf<String>()
                val rewards = sortedSetOf<String>()
                var nested = 0
                var dexCount = 0
                var entries = 0
                val markers = mapOf("com/google/android/gms/ads" to "Google Mobile Ads / AdMob",
                    "com.google.android.gms.ads" to "Google Mobile Ads / AdMob",
                    "com/unity3d/ads" to "Unity Ads",
                    "com.unity3d.ads" to "Unity Ads")
                val rewardMarkers = mapOf("onuserearnedreward" to "Google reward callback",
                    "rewardedad" to "Rewarded ad reference",
                    "rewardedinterstitialad" to "Rewarded interstitial reference",
                    "iunityadsloadlistener" to "Unity Ads listener reference")
                fun check(text: String) {
                    markers.forEach { (key, value) -> if (text.contains(key)) found.add(value) }
                    rewardMarkers.forEach { (key, value) -> if (text.contains(key)) rewards.add(value) }
                }
                fun inspect(input: InputStream, allowNested: Boolean) {
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (++entries > 50000) throw IllegalArgumentException("Archive entry limit exceeded")
                            val entryName = entry.name.lowercase()
                            check(entryName)
                            if (!entry.isDirectory && allowNested && entryName.endsWith(".apk")) {
                                nested++
                                // Closing the nested reader must not close the outer archive.
                                val bounded = object : FilterInputStream(zip) {
                                    var total = 0L
                                    override fun read(): Int {
                                        val result = super.read()
                                        if (result >= 0 && ++total > 512L * 1024 * 1024)
                                            throw IllegalArgumentException("Nested APK exceeds 512 MB scan limit")
                                        return result
                                    }
                                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                                        val count = super.read(b, off, len)
                                        if (count > 0) {
                                            total += count
                                            if (total > 512L * 1024 * 1024)
                                                throw IllegalArgumentException("Nested APK exceeds 512 MB scan limit")
                                        }
                                        return count
                                    }
                                    override fun close() {}
                                }
                                inspect(bounded, false)
                            } else if (!entry.isDirectory && entryName.matches(Regex("(^|.*/)classes[0-9]*\\\\.dex"))) {
                                dexCount++
                                val buffer = ByteArray(65536)
                                var tail = ""
                                var total = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count <= 0) break
                                    total += count
                                    if (total > 128L * 1024 * 1024) throw IllegalArgumentException("DEX exceeds 128 MB scan limit")
                                    val text = tail + String(buffer, 0, count, Charsets.ISO_8859_1).lowercase()
                                    check(text)
                                    tail = text.takeLast(64)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                val inputs = streams()
                try { inputs.forEach { inspect(it, true) } }
                finally { inputs.forEach { try { it.close() } catch (_: Exception) {} } }
                val result = buildString {
                    append("Scan: $name\n\n")
                    if (found.isEmpty()) append("No supported ad SDK markers found.\n")
                    else append("Possible SDK markers:\n" + found.joinToString("\n") { "• $it" } + "\n")
                    if (rewards.isNotEmpty()) append("\nPossible reward-related references:\n" + rewards.joinToString("\n") { "• $it" } + "\n")
                    append("\nDEX files scanned: $dexCount\n")
                    if (nested > 0) append("Nested APK files inspected: $nested\n")
                    append("\nMarkers are clues, not proof that rewards are active or modifiable. Native code, server validation and obfuscated code are not analyzed. This version does not modify, sign or install apps.")
                }
                runOnUiThread { report = result; busy = false }
            } catch (e: Exception) {
                runOnUiThread { report = "Scan failed: ${e.message ?: e.javaClass.simpleName}"; busy = false }
            }
        }
    }
}
