package com.stateofnetwork.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Цветовые токены под стиль liquid glass.
 *
 * Принцип: выразительный градиентный фон + полупрозрачные «стеклянные» поверхности
 * со светлой окантовкой и мягкими хайлайтами.
 */
object AppColors {
    // База (mica-подложка: тёмная, но не «чёрная пустота»)
    // Идея: ближе к iOS/Windows 11 (мягкая тёмно-сине-серая основа), чтобы стекло
    // выглядело объёмно, а текст сохранял контраст.
    val Background = Color(0xFF0B0F1A)
    val Surface = Color(0xFF0E1424)
    val SurfaceVariant = Color(0xFF121A30)

    // Брендовые акценты.
    // Primary сдвинут в «iOS-blue», чтобы основная кнопка выглядела как системный акцент.
    val Primary = Color(0xFF4D8DFF)
    val PrimaryDeep = Color(0xFF2D66FF)
    val Secondary = Color(0xFF2CE4C8) // бирюзовый (оставляем для вторичных акцентов)

    // Контуры/разделители для тёмного интерфейса
    val Outline = Color(0x33FFFFFF)

    // Текст
    val OnBackground = Color(0xFFF2F4FF)
    val OnSurface = Color(0xFFF2F4FF)
    val OnSurfaceVariant = Color(0xB3F2F4FF)

    // Статусы
    val Success = Color(0xFF3DFFB5)
    val Warning = Color(0xFFFFD36E)
    val Error = Color(0xFFFF5C7A)

    // «Стекло» (чуть плотнее и мягче, чтобы не «теряться» на фоне и не давать артефактов)
    val GlassFill = Color(0x24FFFFFF)      // ~14%
    val GlassFillStrong = Color(0x2EFFFFFF) // ~18%
    // Для модальных окон/оверлеев: чуть плотнее, чтобы не сливаться с фоном
    val GlassFillModal = Color(0x66FFFFFF)  // ~40%
    val GlassBorder = Color(0x38FFFFFF)    // ~22%
    val GlassHighlight = Color(0x40FFFFFF) // ~25%
}
