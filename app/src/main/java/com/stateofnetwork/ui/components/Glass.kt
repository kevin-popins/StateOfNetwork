package com.stateofnetwork.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stateofnetwork.ui.theme.AppColors
import kotlin.math.min

/**
 * Базовый набор компонентов в стиле liquid glass.
 *
 * Намеренно без blur/renderEffect: на разных устройствах это выглядит по-разному
 * и иногда просаживает производительность. Вместо этого: полупрозрачная заливка,
 * тонкая рамка, мягкий хайлайт, аккуратный фон.
 */

@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush = glassBackdropBrush())
    ) {
        GlassBlobs()
        // Важно: НЕ рисуем повторяющийся noise поверх всего экрана.
        // На части устройств (масштабы/драйверы) повтор текстуры начинает читаться
        // как прямоугольные «плитки» внутри светлых стеклянных карточек.
        content()
    }
}

@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showMark: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .glassContainer(shape = shape, elevate = true)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        val scope = this
        CompositionLocalProvider(LocalContentColor provides AppColors.OnSurface) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    leading != null -> {
                        leading(this)
                        Spacer(Modifier.width(10.dp))
                    }
                    showMark -> {
                        AppMark(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                }

                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing(this)
            }
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier.glassContainer(shape = shape, elevate = true)) {
        val scope = this
        // Важный момент: стеклянная поверхность должна задавать читаемый цвет контента внутри.
        // Иначе часть Text/Icons начнет наследовать не тот LocalContentColor и может стать "чёрной" на тёмном фоне.
        CompositionLocalProvider(LocalContentColor provides AppColors.OnSurface) {
            scope.content()
        }
    }
}

@Composable
fun GlassModalCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Box(modifier = modifier.glassContainer(shape = shape, base = AppColors.GlassFillModal)) {
        val scope = this
        CompositionLocalProvider(LocalContentColor provides AppColors.OnSurface) {
            scope.content()
        }
    }
}

@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            // Чип должен быть «воздушным»: текст не должен упираться в контур.
            .heightIn(min = 34.dp)
            // Чипы: лёгкое "glass"-заполнение + контур.
            // Ключевое: без shadow и без отдельных прямоугольных оверлеев, чтобы не возвращались артефакты.
            .clip(shape)
            .background(brush = glassFillBrush(AppColors.GlassFillStrong), shape = shape)
            .border(1.dp, AppColors.GlassBorder, shape)
            // Визуально центрируем текст: чуть меньше вертикальных отступов + выравнивание по центру.
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor ?: AppColors.OnSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
fun GlassChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 34.dp)
            // Кнопочный чип: тот же стиль, что и у обычных чипов, но с кликом.
            // Без shadow.
            .clip(shape)
            .background(brush = glassFillBrush(AppColors.GlassFillStrong), shape = shape)
            .border(1.dp, AppColors.GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.OnSurface
        )
    }
}

@Composable
fun GlassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 54.dp
) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .shadow(18.dp, shape)
            .clip(shape)
            .background(brush = primaryButtonBrush())
            .border(1.dp, AppColors.GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Лёгкий «глянец» сверху, чтобы кнопка выглядела как системная (iOS/Win11).
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
fun GlassSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 54.dp
) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .glassContainer(shape = shape, elevate = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.OnSurface,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

private fun Modifier.glassContainer(shape: RoundedCornerShape, base: Color): Modifier {
    return this
        // Важно: на полупрозрачном «стекле» системная тень часто начинает просвечивать
        // внутрь карточки и читается как большой прямоугольный слой (особенно на некоторых
        // GPU/драйверах). Это ровно тот «внутренний прямоугольник», который ты видишь.
        // Поэтому для glass-карточек тень не используем: глубину даём рамкой и мягким градиентом.
        // (Если позже понадобится глубина, лучше делать лёгкий внешний glow через drawBehind,
        // но не стандартный shadow.)
        // Ключевое: никаких дополнительных "внутренних подложек" в карточках.
        // На ряде устройств любые прямоугольные оверлеи (highlight/noise) начинают читаться
        // как отдельные поля внутри rounded-карточки. Оставляем только заливку + рамку.
        .clip(shape)
        .background(brush = glassFillBrush(base), shape = shape)
        .border(1.dp, AppColors.GlassBorder, shape)
}

private fun Modifier.glassContainer(shape: RoundedCornerShape, elevate: Boolean): Modifier {
    val base = if (elevate) AppColors.GlassFillStrong else AppColors.GlassFill
    return this.glassContainer(shape = shape, base = base)
}


@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AppColors.GlassFillStrong,
            unfocusedContainerColor = AppColors.GlassFillStrong,
            disabledContainerColor = AppColors.GlassFillStrong,
            focusedBorderColor = AppColors.Primary.copy(alpha = 0.70f),
            unfocusedBorderColor = AppColors.GlassBorder,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = AppColors.Primary
        )
    )
}

@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    val stroke = 2.4f
    val grad = Brush.linearGradient(listOf(AppColors.Primary, AppColors.Secondary))

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p1 = Offset(w * 0.22f, h * 0.55f)
        val p2 = Offset(w * 0.55f, h * 0.25f)
        val p3 = Offset(w * 0.78f, h * 0.70f)

        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
        }

        drawPath(
            path = path,
            brush = grad,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        drawCircle(brush = grad, radius = min(w, h) * 0.12f, center = p1)
        drawCircle(brush = grad, radius = min(w, h) * 0.12f, center = p2)
        drawCircle(brush = grad, radius = min(w, h) * 0.12f, center = p3)
    }
}

@Composable
private fun GlassBlobs() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val r = min(w, h)

        fun blob(center: Offset, radius: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = center,
                    radius = radius,
                    tileMode = TileMode.Clamp
                ),
                radius = radius,
                center = center
            )
        }

        // Два основных акцентных пятна + два второстепенных. По ощущениям ближе к "премиум" UI,
        // чем большие однотонные круги.
        blob(
            center = Offset(w * 0.88f, h * 0.14f),
            radius = r * 0.62f,
            color = AppColors.Primary.copy(alpha = 0.18f)
        )
        blob(
            center = Offset(w * 0.18f, h * 0.42f),
            radius = r * 0.52f,
            color = AppColors.Secondary.copy(alpha = 0.14f)
        )
        blob(
            center = Offset(w * 0.78f, h * 0.78f),
            radius = r * 0.42f,
            color = AppColors.Secondary.copy(alpha = 0.12f)
        )
        blob(
            center = Offset(w * 0.10f, h * 0.90f),
            radius = r * 0.48f,
            color = AppColors.Primary.copy(alpha = 0.10f)
        )

        // Лёгкий верхний «свет» (как отражение на стекле)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                startY = 0f,
                endY = h * 0.55f
            )
        )
    }
}

private fun glassBackdropBrush(): Brush {
    // Mica-подложка: светлее и «воздушнее», чем чистый чёрный.
    // Плюс: менее заметны границы полупрозрачных слоёв.
    return Brush.linearGradient(
        0.0f to Color(0xFF0B0F1A),
        0.30f to Color(0xFF0D1324),
        0.62f to Color(0xFF101A33),
        1.0f to Color(0xFF0B0F1A),
        tileMode = TileMode.Clamp
    )
}

private fun glassHighlightBrush(): Brush {
    return Brush.linearGradient(
        0.0f to AppColors.GlassHighlight,
        0.35f to Color(0x12FFFFFF),
        0.65f to Color.Transparent,
        1.0f to Color.Transparent,
        tileMode = TileMode.Clamp
    )
}

private fun primaryButtonBrush(): Brush {
    // Основная кнопка: ближе к iOS/Win11 системному акценту (синий градиент, без неона).
    return Brush.verticalGradient(
        colors = listOf(
            AppColors.Primary,
            AppColors.PrimaryDeep
        )
    )
}

private fun glassFillBrush(base: Color): Brush {
    // Небольшой вертикальный градиент внутри «стекла» выглядит натуральнее, чем ровная заливка.
    return Brush.verticalGradient(
        colors = listOf(
            base.copy(alpha = base.alpha * 1.08f),
            base.copy(alpha = base.alpha * 0.92f)
        )
    )
}


