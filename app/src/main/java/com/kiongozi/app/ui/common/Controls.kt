package com.kiongozi.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiongozi.app.ui.theme.Neon

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text, color = Neon.Magenta, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    Spacer(Modifier.height(8.dp))
}

/** Selectable pill; [sub] is an optional second line (e.g. a star rating). */
@Composable
fun RowScope.NeonChip(label: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val c = if (selected) Neon.Magenta else Neon.TextDim
    Column(
        Modifier
            .weight(1f)
            .border(if (selected) 2.dp else 1.dp, c, RoundedCornerShape(10.dp))
            .background(if (selected) Neon.Magenta.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = if (selected) Neon.Lime else Neon.TextDim, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Slider that only saves when the finger lifts (no storage write on every pixel of the drag). */
@Composable
fun NeonSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    display: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(display(v), color = Neon.Cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
fun NeonToggle(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            if (sub != null) Text(sub, color = Neon.TextDim, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Small outlined text button. */
@Composable
fun NeonTextButton(label: String, color: Color = Neon.Cyan, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}
