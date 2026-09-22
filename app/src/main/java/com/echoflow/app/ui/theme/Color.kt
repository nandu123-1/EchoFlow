package com.echoflow.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * EchoFlow V5.1 "Ocean Breeze" Color System.
 *
 * Core Palette:
 * - PRIMARY (#0B3D91): Primary CTA, headers, active navigation, identity, action buttons
 * - SECONDARY (#3BA7F2): Listening state, AI processing, interactive elements, progress, ambient glow
 * - TERTIARY / SUCCESS (#7FE7D6): Success, completed, active scheduled, positive results, subtle glow
 * - BACKGROUND / SOFT SURFACE (#E8F6FF): Soft backgrounds, cards, surfaces, input areas, timeline surfaces
 */

// --- Ocean Breeze Brand Palette ---
val OceanPrimary = Color(0xFF0B3D91)          // Deep Navy Identity / Primary CTA
val OceanSecondary = Color(0xFF3BA7F2)        // Electric Sky Blue / AI Glow
val OceanTertiary = Color(0xFF7FE7D6)         // Mint / Success / Active Schedule
val OceanBackground = Color(0xFFE8F6FF)       // Soft Breezy Blue-White Surface
val OceanSurface = Color(0xFFFFFFFF)          // Crisp Card White
val OceanSurfaceVariant = Color(0xFFDDF0FD)   // Input areas, pill backdrops
val OceanCardBorder = Color(0xFFBCE3FD)       // Elegant subtle card outline

// --- Surface Mapping ---
val EchoDarkBg = OceanBackground
val EchoDarkSurface = OceanSurface
val EchoSurface = OceanSurface
val EchoSurfaceVariant = OceanSurfaceVariant
val EchoBlack = OceanPrimary

// --- Primary Accent ---
val EchoPrimary = OceanPrimary
val EchoAccent = OceanPrimary
val EchoPrimaryLight = Color(0xFF1E52B7)
val EchoPrimaryDark = Color(0xFF062863)
val EchoPrimaryContainer = Color(0xFFD6E8FD)

// --- Secondary Accent (AI & Glow) ---
val EchoSecondary = OceanSecondary
val EchoSecondaryLight = Color(0xFF64BEFA)
val EchoSecondaryContainer = Color(0xFFC7E7FE)

// --- Tertiary & Semantic Colors ---
val EchoTertiary = OceanTertiary
val EchoSuccess = Color(0xFF0D9488)           // Rich Teal for text contrast
val EchoSuccessMint = OceanTertiary           // Mint #7FE7D6 for chips/glow
val EchoSuccessContainer = Color(0xFFD1FAF4)
val EchoWarning = Color(0xFFD97706)           // Amber
val EchoWarningContainer = Color(0xFFFEF3C7)
val EchoError = Color(0xFFE11D48)             // Coral Rose
val EchoErrorContainer = Color(0xFFFFE4E6)

// --- High Contrast Typography ---
val EchoTextPrimary = Color(0xFF0B1B3D)       // High emphasis deep navy
val EchoTextSecondary = Color(0xFF334E68)     // Medium emphasis slate
val EchoTextTertiary = Color(0xFF627D98)      // Low emphasis / hints
val EchoTextOnPrimary = Color(0xFFFFFFFF)     // Clean white on primary

// --- Action Type Accents ---
val CalendarColor = OceanPrimary              // #0B3D91
val EchoCalendar = CalendarColor
val ReminderColor = Color(0xFFD97706)         // Amber
val EchoReminder = ReminderColor
val MessageColor = OceanSecondary             // #3BA7F2
val EchoMessage = MessageColor
val NoteColor = OceanTertiary
val EchoNote = NoteColor
val CallColor = Color(0xFF0284C7)             // Deep Cyan
val UnknownColor = Color(0xFF64748B)          // Slate

// --- Gradients ---
val EchoGradientStart = Color(0xFFE8F6FF)
val EchoGradientMid = Color(0xFFD8EEFF)
val EchoGradientEnd = Color(0xFFCBE7FD)

// --- Ambient Scrim & Glass ---
val EchoGlass = Color(0xB3FFFFFF)             // 70% white translucent
val EchoGlassStroke = Color(0x403BA7F2)       // 25% secondary blue outline
val EchoOverlayScrim = Color(0x660B1B3D)      // Translucent deep navy scrim
