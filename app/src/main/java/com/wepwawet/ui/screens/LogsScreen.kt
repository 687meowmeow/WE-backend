package com.wepwawet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.log.LogEntry
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val events = remember { mutableStateListOf<LogEntry>() }
    val tick = remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            events.clear()
            events.addAll(WepWawetLogger.snapshot())
            tick.intValue++
            delay(500)
        }
    }

    fun copyLogs() {
        val text = events.reversed().joinToString("\n") { entry ->
            buildString {
                append("[${entry.domain.tag}] ${entry.level.name}: ${entry.message}")
                if (entry.context.isNotEmpty()) {
                    append(" ")
                    append(entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" })
                }
                if (entry.exceptionStackTrace != null) {
                    append("\n")
                    append(entry.exceptionStackTrace.take(500))
                }
            }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WepWawet Logs", text))
        Toast.makeText(context, "Copied ${events.size} log entries", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${events.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { copyLogs() }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.clear()
                        events.clear()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "No logs yet. Start the pairing proxy from the Pairing tab to see protocol messages.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(events.reversed(), key = { it.seq }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        WepWawetLogger.Level.TRACE, WepWawetLogger.Level.DEBUG -> Color(0xFF6B7280)
        WepWawetLogger.Level.INFO -> Color(0xFFE6E6E9)
        WepWawetLogger.Level.WARN -> Color(0xFFFFB72E)
        WepWawetLogger.Level.ERROR -> Color(0xFFFF6B5C)
        WepWawetLogger.Level.FATAL -> Color(0xFFFFB72E)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (entry.level) {
                WepWawetLogger.Level.ERROR, WepWawetLogger.Level.FATAL -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.domain.tag,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.level.name,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.message,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (entry.context.isNotEmpty()) {
                Text(
                    text = entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (entry.exceptionStackTrace != null) {
                Text(
                    text = entry.exceptionStackTrace.take(2000),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
