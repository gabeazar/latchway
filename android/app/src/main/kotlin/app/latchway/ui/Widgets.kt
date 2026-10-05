package app.latchway.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The gate-latch mark, drawn in theme colours. */
@Composable
fun LatchMark(modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onBackground, brass: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier = modifier.size(40.dp)) {
        val u = size.width / 64f
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) =
            drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u, r * u))
        rr(6f, 9f, 8f, 46f, 4f, ink)
        rr(50f, 9f, 8f, 46f, 4f, ink)
        val keeper = Path().apply {
            moveTo(40f * u, 23.5f * u)
            lineTo(51.5f * u, 23.5f * u)
            quadraticTo(55f * u, 23.5f * u, 55f * u, 27f * u)
            lineTo(55f * u, 37f * u)
            quadraticTo(55f * u, 40.5f * u, 51.5f * u, 40.5f * u)
            lineTo(40f * u, 40.5f * u)
        }
        drawPath(keeper, ink, style = Stroke(width = 4f * u, cap = StrokeCap.Round))
        rr(12f, 27.5f, 37f, 9f, 4.5f, brass)
        drawCircle(ink, 2.8f * u, Offset(19f * u, 32f * u))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
}

enum class Dot { IDLE, GOOD, BAD, BUSY }

@Composable
fun StatusLine(text: String, dot: Dot, modifier: Modifier = Modifier) {
    val c = when (dot) {
        Dot.IDLE -> MaterialTheme.colorScheme.outline
        Dot.GOOD -> MaterialTheme.colorScheme.tertiary
        Dot.BAD -> MaterialTheme.colorScheme.error
        Dot.BUSY -> MaterialTheme.colorScheme.primary
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A QR code for a link. Quiet zone included; scales to the width given. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { qrBitmap(text) }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Image(bitmap.asImageBitmap(), contentDescription = "QR code of the link", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    }
}

private fun qrBitmap(text: String): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = m.width
    val h = m.height
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) 0xFF1D2A3A.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

@Composable
fun Centered(text: String) {
    Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.padding(top = h.dp))

@Composable
fun SectionTitle(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}
