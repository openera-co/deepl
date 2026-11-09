package app.carnet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Press feedback used by every tappable element: a quick spring scale-down and a light haptic tick.
 */
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    haptic: Boolean = false,
    scaleTo: Float = 0.96f,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) scaleTo else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val h = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role) {
            if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().fillMaxHeight().background(pal.bg).statusBarsPadding(), content = content)
}

@Composable
fun TopBar(title: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, center: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(Ic.Back, "Back", onClick = onBack) else Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = if (center != null) Alignment.Center else Alignment.CenterStart) {
            if (center != null) center()
            else if (title != null) Text(title, style = Type.screenTitle, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (onBack == null) 8.dp else 4.dp))
        }
        actions()
    }
}

@Composable
fun IconBtn(icon: ImageVector, label: String, tint: Color = pal.ink, size: Dp = 44.dp, bg: Color = Color.Transparent, border: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (border) Modifier.border(1.dp, pal.line, RoundedCornerShape(12.dp)) else Modifier)
            .semantics { contentDescription = label }
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp)) }
}

enum class BtnKind { Primary, Secondary, Soft, Danger, Ghost }

@Composable
fun Btn(
    text: String,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val (bg, fg) = when (kind) {
        BtnKind.Primary -> pal.accent to pal.accentInk
        BtnKind.Secondary -> pal.surface to pal.ink
        BtnKind.Soft -> pal.accentSoft to pal.accent
        BtnKind.Danger -> pal.dangerSoft to pal.danger
        BtnKind.Ghost -> Color.Transparent to pal.accent
    }
    val a by animateFloatAsState(if (enabled) 1f else 0.45f, tween(150), label = "en")
    Row(
        modifier.height(height).clip(RoundedCornerShape(14.dp)).background(bg)
            .then(if (kind == BtnKind.Secondary) Modifier.border(1.dp, pal.line, RoundedCornerShape(14.dp)) else Modifier)
            .tap(enabled = enabled, haptic = kind == BtnKind.Primary, onClick = onClick)
            .graphicsLayer { alpha = a }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), color: Color = pal.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(color).border(1.dp, if (color == pal.surface) pal.line else Color.Transparent, RoundedCornerShape(18.dp)).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.section, color = pal.muted, modifier = modifier.padding(top = 10.dp, bottom = 6.dp, start = 2.dp))
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line)) }

@Composable
fun SettingRow(label: String, value: String? = null, chevron: Boolean = true, labelColor: Color = pal.ink, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).then(if (onClick != null) Modifier.tap(scaleTo = 0.985f, onClick = onClick) else Modifier).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body, fontWeight = FontWeight.Medium, color = labelColor, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (value != null) Text(value, style = Type.body, color = pal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        if (trailing != null) trailing()
        else if (chevron && onClick != null) Icon(Ic.Chevron, null, tint = pal.faint, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (checked) 20f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "knob")
    val c by animateColorAsState(if (checked) pal.accent else pal.line.copy(alpha = 1f).let { if (pal.dark) Color(0xFF3A3E46) else Color(0xFFD3D5D9) }, label = "track")
    Box(
        Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(c).tap(role = Role.Switch, scaleTo = 0.94f) { onChange(!checked) }
            .semantics { contentDescription = if (checked) "On" else "Off" },
    ) {
        Box(Modifier.offset(x = (3 + x).dp, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label, chevron = false, onClick = { onChange(!checked) }) { Toggle(checked, onChange) }
}

/** Segmented control (Original / English / Text…). The selection pill slides between options. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Box(modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.surfaceAlt).padding(3.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth / options.size
            val x by animateFloatAsState((w * selected).value, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), label = "seg")
            Box(Modifier.offset(x = x.dp).width(w).height(34.dp).clip(RoundedCornerShape(9.dp)).background(pal.surface))
            Row(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(9.dp)).tap(role = Role.Tab, scaleTo = 0.97f) { onSelect(i) }, contentAlignment = Alignment.Center) {
                        Text(o, fontSize = 14.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, color = if (i == selected) pal.ink else pal.muted)
                    }
                }
            }
        }
    }
}

/** File-type badge (DOCX, PDF…), colours per type. */
@Composable
fun FileBadge(type: String, size: Dp = 40.dp, fontSize: Int = 10) {
    val (bg, fg) = badgeColors(type)
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(type.uppercase().take(4), color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun badgeColors(type: String): Pair<Color, Color> {
    val dark = pal.dark
    fun c(l: Long, d: Long) = Color(if (dark) d else l)
    return when (type.lowercase()) {
        "docx", "doc", "word" -> c(0xFFE6ECFA, 0xFF1E2A4F) to c(0xFF1D47B8, 0xFF9DB2EA)
        "pdf" -> c(0xFFFBE9E7, 0xFF3A1C1A) to c(0xFFA8301F, 0xFFFF9C8F)
        "pptx", "ppt" -> c(0xFFFCEDE3, 0xFF3A2414) to c(0xFF9A4112, 0xFFF5A970)
        "xlsx", "xls", "csv" -> c(0xFFE4F3EA, 0xFF15301F) to c(0xFF1C5E33, 0xFF7FD39A)
        "jpg", "png", "scan", "img", "webp", "heic" -> c(0xFFEFEAFB, 0xFF2A2140) to c(0xFF5B3BB0, 0xFFC0A8FF)
        "rec", "m4a", "wav" -> c(0xFFFBE9E7, 0xFF3A1C1A) to c(0xFFA8301F, 0xFFFF9C8F)
        else -> c(0xFFE9EBEE, 0xFF26292F) to c(0xFF3D424B, 0xFFC4C8CF)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, track: Color = pal.accentSoft, color: Color = pal.accent) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(350), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
fun ListItemRow(badge: String, title: String, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(62.dp).tap(scaleTo = 0.985f, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        FileBadge(badge)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = Type.bodyStrong, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.small, color = pal.muted, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) pal.ink else pal.surface, label = "chip")
    val fg by animateColorAsState(if (selected) pal.bg else pal.ink, label = "chipfg")
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(bg).then(if (!selected) Modifier.border(BorderStroke(1.dp, pal.line), CircleShape) else Modifier)
            .tap(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = fg) }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(pal.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = pal.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(text, style = Type.body, color = pal.muted)
    }
}

/** Bottom navigation with a sliding indicator. */
@Composable
fun BottomNav(current: String, onSelect: (String) -> Unit) {
    val items = listOf(Triple("home", "Translate", Ic.Translate), Triple("scan", "Scan", Ic.Camera), Triple("record", "Record", Ic.Mic), Triple("library", "Library", Ic.Library))
    Column(Modifier.fillMaxWidth().background(pal.surface).navigationBarsPadding()) {
        Divider()
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            items.forEach { (route, label, icon) ->
                val sel = current == route
                val tint by animateColorAsState(if (sel) pal.accent else pal.muted, label = "nav")
                val sc by animateFloatAsState(if (sel) 1.08f else 1f, spring(dampingRatio = 0.5f), label = "navs")
                Column(
                    Modifier.weight(1f).fillMaxHeight().tap(role = Role.Tab, scaleTo = 0.92f) { onSelect(route) }.semantics { contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp).scale(sc))
                    Spacer(Modifier.height(4.dp))
                    Text(label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = tint)
                }
            }
        }
    }
}
