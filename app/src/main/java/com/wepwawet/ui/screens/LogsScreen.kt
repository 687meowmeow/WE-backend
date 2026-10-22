package com.wepwawet.ui.screens

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${tick.intValue})") },
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
            items(events.reversed(), key = { it.seq }) { entry ->
                LogEntryRow(entry)
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
