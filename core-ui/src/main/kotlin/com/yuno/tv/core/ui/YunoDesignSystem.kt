package com.yuno.tv.core.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.request.ImageRequest

object YunoColors {
    val BackgroundPrimary = Color(0xFF0E101A)
    val BackgroundSecondary = Color(0xFF141724)
    val Surface = Color(0xFF1C2030)
    val SurfaceElevated = Color(0xFF252A3D)
    val SurfaceFocused = Color(0xFFDDD5EF)
    val Primary = Color(0xFFB8ADDA)
    val Secondary = Color(0xFFC8A497)
    val TextPrimary = Color(0xFFF4F2FA)
    val TextSecondary = Color(0xFFA9AEC3)
    val TextDisabled = Color(0xFF656B80)
    val Focus = Color(0xFFE8DFFF)
    val Error = Color(0xFFF49A9B)
    val Success = Color(0xFF9ACAB3)
    val Warning = Color(0xFFE7C694)
}
object YunoShapes {
    val S = RoundedCornerShape(6.dp)
    val M = RoundedCornerShape(10.dp)
    val L = RoundedCornerShape(16.dp)
    val XL = RoundedCornerShape(24.dp)
}
object YunoSpacing { val XS = 4.dp; val S = 8.dp; val M = 16.dp; val L = 24.dp; val XL = 40.dp }
object YunoDimensions { val PosterWidth = 132.dp; val HeroHeight = 276.dp; val SafeHorizontal = 40.dp; val ControlHeight = 44.dp }
object YunoMotion { const val FocusDuration = 160; const val PageDuration = 180; fun focus() = tween<Float>(FocusDuration) }
object YunoElevation { val Normal = 0.dp; val Focused = 8.dp; val Dialog = 12.dp }
object YunoFocusStyle { const val PosterScale = 1.06f; const val ActionScale = 1.04f; val BorderWidth = 2.dp }
object YunoTypography {
    val styles = androidx.tv.material3.Typography(
        headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    )
}

@Composable
fun YunoBrand(modifier: Modifier = Modifier, large: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.yuno_logo), stringResource(R.string.app_brand), Modifier.size(if (large) 64.dp else 40.dp).clip(YunoShapes.M))
        Text(stringResource(R.string.app_brand), color = YunoColors.TextPrimary,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun rememberYunoImage(url: String, width: Int, height: Int): ImageRequest {
    val context = LocalContext.current
    return remember(context, url, width, height) { ImageRequest.Builder(context).data(url.takeIf { it.isNotBlank() })
        .size(width, height).crossfade(false).allowHardware(true).build() }
}

@Composable
fun YunoLoadingState(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(YunoSpacing.S)) {
        Text(message, color = YunoColors.TextSecondary)
        androidx.compose.material3.LinearProgressIndicator(Modifier.width(200.dp), color = YunoColors.Primary, trackColor = YunoColors.Surface)
    }
}
