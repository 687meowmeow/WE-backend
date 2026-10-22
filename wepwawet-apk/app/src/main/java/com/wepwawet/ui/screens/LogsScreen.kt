package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val events = remember { mutableListOf<WepWawetLogger.LogEntry>() }
    var tick by remember { mutableStateOf(0) }

    // Poll the logger's snapshot every 500ms — the logger's SharedFlow could also be
    // used directly, but polling is simpler and we want historical context (last 2000
    // entries), not just new ones.
    LaunchedEffect(Unit) {
        while (true) {
            events.clear()
            events += WepWawetLogger.snapshot()
            tick++
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs ($tick)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Reverse so newest at top
            items(events.reversed(), key = { it.seq }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: WepWawetLogger.LogEntry) {
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
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
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
