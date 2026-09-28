package com.bothostinger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothostinger.app.bot.LogLine

@Composable
fun ConsoleScreen(logs: List<LogLine>, onBack: () -> Unit, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Console", onBack) {
            Box(
                Modifier
                    .height(44.dp)
                    .clip(ButtonShape)
                    .background(Palette.SecondaryButton)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Effacer", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxSize()
                .background(Palette.Surface)
                .border(1.dp, Palette.Border)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "> en attente…\n> lance le bot pour voir son activité ici",
                    color = Palette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(logs) { line ->
                    Row {
                        Text(
                            line.time,
                            color = Palette.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(
                            line.text,
                            color = if (line.isError) Palette.Red else Palette.Text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
