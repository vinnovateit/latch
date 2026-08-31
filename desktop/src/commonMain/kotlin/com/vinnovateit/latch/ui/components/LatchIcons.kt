package com.vinnovateit.latch.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material symbol icons not available in material-icons-core, vendored as
 * ImageVector path data so the 36 MB material-icons-extended jar is never needed.
 *
 * Every fill is black; Icon's tint parameter overrides it at the call site.
 */
internal object LatchIcons {

    val WifiLock: ImageVector by lazy {
        ImageVector.Builder(
            name = "android_wifi_3_bar_lock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.33f, 20.78f)
                quadToRelative(-0.38f, 0f, -0.63f, -0.29f)
                reflectiveQuadTo(17.45f, 19.83f)
                verticalLineTo(16.27f)
                quadToRelative(0f, -0.37f, 0.25f, -0.64f)
                quadToRelative(0.25f, -0.26f, 0.63f, -0.26f)
                horizontalLineToRelative(0.13f)
                verticalLineToRelative(-1f)
                quadToRelative(0f, -0.9f, 0.65f, -1.53f)
                reflectiveQuadToRelative(1.55f, -0.63f)
                reflectiveQuadToRelative(1.55f, 0.63f)
                reflectiveQuadToRelative(0.65f, 1.53f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(0.13f)
                quadToRelative(0.38f, 0f, 0.63f, 0.26f)
                reflectiveQuadToRelative(0.25f, 0.64f)
                verticalLineToRelative(3.55f)
                quadToRelative(0f, 0.38f, -0.25f, 0.66f)
                reflectiveQuadToRelative(-0.63f, 0.29f)
                horizontalLineTo(18.33f)
                close()
                moveToRelative(1.33f, -5.4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-1f)
                quadToRelative(0f, -0.43f, -0.29f, -0.71f)
                reflectiveQuadTo(20.65f, 13.38f)
                reflectiveQuadToRelative(-0.71f, 0.29f)
                reflectiveQuadToRelative(-0.29f, 0.71f)
                verticalLineToRelative(1f)
                close()
                moveToRelative(-9.48f, 4.65f)
                quadTo(9.43f, 19.27f, 9.43f, 18.2f)
                reflectiveQuadToRelative(0.75f, -1.82f)
                reflectiveQuadTo(12f, 15.63f)
                reflectiveQuadToRelative(1.83f, 0.75f)
                reflectiveQuadToRelative(0.75f, 1.82f)
                reflectiveQuadToRelative(-0.75f, 1.82f)
                reflectiveQuadTo(12f, 20.78f)
                reflectiveQuadTo(10.18f, 20.02f)
                close()
                moveTo(12f, 13.48f)
                quadToRelative(-0.9f, 0f, -1.76f, 0.24f)
                reflectiveQuadTo(8.6f, 14.38f)
                quadTo(7.85f, 14.8f, 7f, 14.79f)
                reflectiveQuadTo(5.58f, 14.18f)
                quadTo(4.98f, 13.55f, 5f, 12.71f)
                quadTo(5.03f, 11.88f, 5.7f, 11.38f)
                quadTo(7.08f, 10.4f, 8.68f, 9.88f)
                reflectiveQuadTo(12f, 9.35f)
                quadToRelative(1.5f, 0f, 2.95f, 0.41f)
                reflectiveQuadToRelative(2.72f, 1.21f)
                quadTo(16.73f, 11.48f, 16f, 12.29f)
                quadTo(15.28f, 13.1f, 14.85f, 14.1f)
                quadToRelative(-0.68f, -0.3f, -1.4f, -0.46f)
                reflectiveQuadTo(12f, 13.48f)
                close()
                moveTo(7.9f, 7.95f)
                quadTo(5.93f, 8.63f, 4.28f, 9.9f)
                quadTo(3.6f, 10.43f, 2.75f, 10.41f)
                reflectiveQuadTo(1.33f, 9.8f)
                quadTo(0.73f, 9.17f, 0.75f, 8.32f)
                quadTo(0.78f, 7.47f, 1.43f, 6.95f)
                quadTo(3.68f, 5.13f, 6.38f, 4.14f)
                quadTo(9.08f, 3.15f, 12f, 3.15f)
                reflectiveQuadToRelative(5.63f, 0.99f)
                reflectiveQuadToRelative(4.95f, 2.81f)
                quadToRelative(0.65f, 0.52f, 0.67f, 1.38f)
                quadTo(23.28f, 9.17f, 22.68f, 9.8f)
                quadToRelative(-0.57f, 0.6f, -1.43f, 0.61f)
                reflectiveQuadTo(19.73f, 9.9f)
                quadTo(18.08f, 8.63f, 16.1f, 7.95f)
                reflectiveQuadTo(12f, 7.27f)
                reflectiveQuadTo(7.9f, 7.95f)
                close()
            }
        }.build()
    }

    val PowerSettingsNew: ImageVector by lazy {
        icon(
            "PowerSettingsNew",
            "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12" +
                "c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17" +
                "C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9" +
                "c0-2.74-1.23-5.18-3.17-6.83z",
        )
    }

    val Wifi: ImageVector by lazy {
        icon(
            "Wifi",
            "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9z" +
                "m8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0z" +
                "m-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z",
        )
    }

    val Speed: ImageVector by lazy {
        icon(
            "Speed",
            "M20.38 8.57l-1.23 1.85a8 8 0 01-.22 7.58H5.07A8 8 0 0115.58 6.85l1.85-1.23" +
                "A10 10 0 003.35 19a2 2 0 001.72 1h13.85a2 2 0 001.74-1" +
                " 10 10 0 00-.28-11.43zm-9.79 6.84a2 2 0 002.83 0l5.66-8.49-8.49 5.66a2 2 0 000 2.83z",
        )
    }

    val DarkMode: ImageVector by lazy {
        icon(
            "DarkMode",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9" +
                "c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26" +
                "-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z",
        )
    }

    val LightMode: ImageVector by lazy {
        icon(
            "LightMode",
            "M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79zM4 10.5H1v2h3zm9-9.95h-2V3.5h2z" +
                "m7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41zM17.24 19.16l1.79 1.8 1.41-1.41-1.8-1.79z" +
                "M20 10.5v2h3v-2zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6-2.69-6-6-6z" +
                "m-1 16.95h2V19.5h-2zm-7.45-3.91l1.41 1.41 1.79-1.8-1.41-1.41z",
        )
    }

    val DesktopWindows: ImageVector by lazy {
        icon(
            "DesktopWindows",
            "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7l-2 3v1h8v-1l-2-3h7" +
                "c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 12H3V4h18v10z",
        )
    }

    val InvertColors: ImageVector by lazy {
        icon(
            "InvertColors",
            "M17.66 7.93L12 2.27 6.34 7.93c-3.12 3.12-3.12 8.19 0 11.31" +
                "C7.9 20.8 9.95 21.58 12 21.58c2.05 0 4.1-.78 5.66-2.34" +
                " 3.12-3.12 3.12-8.19 0-11.31zM12 19.59c-1.6 0-3.11-.62-4.24-1.76" +
                "C6.62 16.69 6 15.19 6 13.59s.62-3.11 1.76-4.24L12 5.1v14.49z",
        )
    }

    val Autorenew: ImageVector by lazy {
        icon(
            "Autorenew",
            "M12 6v3l4-4-4-4v3c-4.42 0-8 3.58-8 8 0 1.57.46 3.03 1.24 4.26" +
                "L6.7 14.8c-.45-.83-.7-1.79-.7-2.8 0-3.31 2.69-6 6-6z" +
                "m6.76 1.74L17.3 9.2c.44.84.7 1.79.7 2.8 0 3.31-2.69 6-6 6v-3l-4 4 4 4v-3" +
                "c4.42 0 8-3.58 8-8 0-1.57-.46-3.03-1.24-4.26z",
        )
    }

    val Refresh: ImageVector get() = Autorenew

    val ArrowOutward: ImageVector by lazy {
        icon("ArrowOutward", "M6 6v2h8.59L5 17.59 6.41 19 16 9.41V18h2V6z")
    }

    val ArrowDownward: ImageVector by lazy {
        icon(
            "ArrowDownward",
            "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z",
        )
    }

    val ArrowUpward: ImageVector by lazy {
        icon(
            "ArrowUpward",
            "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z",
        )
    }

    val Update: ImageVector by lazy {
        icon(
            "Update",
            "M21 10.12h-6.78l2.74-2.82c-2.73-2.7-7.15-2.8-9.88-.1" +
                "a7 7 0 000 9.79 7 7 0 009.88 0C18.32 15.65 19 14.08 19 12.1h2" +
                "c0 1.98-.88 4.55-2.64 6.29-3.51 3.48-9.21 3.48-12.72 0" +
                "-3.5-3.47-3.53-9.11-.02-12.58 3.51-3.47 9.14-3.47 12.65 0L21 3v7.12z" +
                "M12.5 8v4.25l3.5 2.08-.72 1.21L11 13V8h1.5z",
        )
    }

    val Info: ImageVector by lazy {
        icon(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
        )
    }

    val InfoOutline: ImageVector by lazy {
        icon(
            "InfoOutline",
            "M11 17h2v-6h-2v6zm1-15C6.48 2 2 6.48 2 12s4.48 10 10" +
                " 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8" +
                "s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-12h2V7h-2v1zm0 2h2" +
                "c1.1 0 2 .9 2 2v6h-2v-6h-2v-2z",
        )
    }

    val Help: ImageVector by lazy {
        ImageVector.Builder(
            name = "help",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.95f, 17.55f)
                quadTo(13.4f, 17.1f, 13.4f, 16.48f)
                reflectiveQuadTo(12.95f, 15.41f)
                reflectiveQuadTo(11.88f, 14.98f)
                reflectiveQuadTo(10.8f, 15.41f)
                reflectiveQuadToRelative(-0.45f, 1.06f)
                reflectiveQuadToRelative(0.45f, 1.07f)
                reflectiveQuadTo(11.88f, 18f)
                reflectiveQuadToRelative(1.08f, -0.45f)
                close()
                moveTo(12f, 22.85f)
                quadTo(9.73f, 22.85f, 7.75f, 22f)
                quadTo(5.78f, 21.15f, 4.31f, 19.69f)
                reflectiveQuadTo(2f, 16.25f)
                reflectiveQuadTo(1.15f, 12f)
                reflectiveQuadTo(2f, 7.75f)
                quadTo(2.85f, 5.77f, 4.31f, 4.31f)
                reflectiveQuadTo(7.75f, 2f)
                reflectiveQuadTo(12f, 1.15f)
                quadToRelative(2.28f, 0f, 4.25f, 0.85f)
                reflectiveQuadToRelative(3.44f, 2.31f)
                quadTo(21.15f, 5.77f, 22f, 7.75f)
                reflectiveQuadTo(22.85f, 12f)
                quadToRelative(0f, 2.27f, -0.85f, 4.25f)
                reflectiveQuadToRelative(-2.31f, 3.44f)
                quadTo(18.23f, 21.15f, 16.25f, 22f)
                quadTo(14.28f, 22.85f, 12f, 22.85f)
                close()
                moveTo(12.03f, 8.23f)
                quadToRelative(0.43f, 0f, 0.74f, 0.29f)
                reflectiveQuadToRelative(0.31f, 0.71f)
                reflectiveQuadTo(12.83f, 10f)
                reflectiveQuadToRelative(-0.58f, 0.63f)
                quadToRelative(-0.57f, 0.5f, -1.02f, 1.13f)
                reflectiveQuadToRelative(-0.45f, 1.38f)
                quadToRelative(0f, 0.42f, 0.33f, 0.72f)
                reflectiveQuadToRelative(0.77f, 0.3f)
                quadToRelative(0.48f, 0f, 0.81f, -0.31f)
                quadToRelative(0.34f, -0.31f, 0.51f, -0.76f)
                quadToRelative(0.15f, -0.43f, 0.41f, -0.75f)
                reflectiveQuadTo(14.2f, 11.7f)
                quadToRelative(0.57f, -0.53f, 0.94f, -1.21f)
                reflectiveQuadTo(15.5f, 9.02f)
                quadToRelative(0f, -1.32f, -1f, -2.2f)
                reflectiveQuadTo(12.15f, 5.95f)
                quadTo(11.1f, 5.95f, 10.2f, 6.4f)
                reflectiveQuadTo(8.75f, 7.72f)
                quadTo(8.53f, 8.1f, 8.61f, 8.54f)
                reflectiveQuadTo(9.08f, 9.2f)
                quadTo(9.5f, 9.48f, 9.99f, 9.36f)
                reflectiveQuadTo(10.83f, 8.85f)
                quadTo(11.05f, 8.57f, 11.36f, 8.4f)
                quadTo(11.68f, 8.23f, 12.03f, 8.23f)
                close()
            }
        }.build()
    }

    val HelpOutline: ImageVector by lazy {
        icon(
            "HelpOutline",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-3h2v2h-2v-2zm1.71-9.71c-.38-.38-.88-.59-1.42-.59-1.12 0-2 .88-2 2H8c0-2.21 1.79-4 4-4 1.06 0 2.08.42 2.83 1.17.75.75 1.17 1.77 1.17 2.83 0 1.44-.8 2.2-1.49 2.86-.59.57-1.01.98-1.01 1.84V15h-2v-.89c0-1.75.9-2.6 1.65-3.3.52-.5.85-.84.85-1.48 0-.54-.21-1.04-.59-1.42z",
        )
    }

    val SystemUpdateAlt: ImageVector by lazy {
        icon(
            "SystemUpdateAlt",
            "M5 20h14v-2H5v2zm7-18L5.33 8h3.84V14h4.66V8h3.84L12 2z",
        )
    }

    val VersionTag: ImageVector by lazy {
        icon(
            "VersionTag",
            "M7 4h10l3 4v12H4V4h3zm0 2v12h12V8.73L16.05 6H7zm2 2h2v2H9V8zm0 4h6v2H9v-2zm0 4h4v2H9v-2z",
        )
    }

    val BarChart: ImageVector by lazy {
        icon("BarChart", "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z")
    }

    val Restore: ImageVector by lazy {
        icon(
            "Restore",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6" +
                "c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7" +
                "c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21" +
                "c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        )
    }

    val Login: ImageVector by lazy {
        icon(
            "Login",
            "M11 7L9.6 8.4l2.6 2.6H2v2h10.2l-2.6 2.6L11 17l5-5-5-5z" +
                "m9 12h-8v2h8c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-8v2h8v14z",
        )
    }

    val Menu: ImageVector by lazy {
        icon("Menu", "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    }

    val Check: ImageVector by lazy {
        icon("Check", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    val Add: ImageVector by lazy {
        icon("Add", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z")
    }

    val Lightbulb: ImageVector by lazy {
        icon(
            "Lightbulb",
            "M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zM12 2C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7z",
        )
    }

    // Org-level social links on the About screen, vendored from the Android app's
    // github.xml / linkedin.xml / instagram.xml -- same 35x35 source viewport,
    // ported at that size rather than rescaled to the usual 24x24. Each pathData
    // string is kept unbroken (no manual line-wrapping) since the source strings
    // use commas rather than spaces between numbers -- a wrapped concatenation
    // that lands between two numbers with no separator silently corrupts the path.

    val GitHub: ImageVector by lazy {
        socialIcon(
            "GitHub",
            "M17.506,0C27.171,0 35.006,8.032 35.006,17.943C35.006,25.868 29.997,32.592 23.048,34.967C22.161,35.143 21.846,34.583 21.846,34.105C21.846,33.514 21.867,31.582 21.867,29.181C21.867,27.508 21.307,26.416 20.679,25.86C24.576,25.415 28.671,23.898 28.671,17.006C28.671,15.046 27.992,13.447 26.868,12.19C27.05,11.737 27.651,9.912 26.697,7.441C26.697,7.441 25.23,6.96 21.89,9.281C20.491,8.883 18.993,8.684 17.506,8.677C16.018,8.684 14.522,8.883 13.126,9.281C9.781,6.96 8.311,7.441 8.311,7.441C7.361,9.912 7.961,11.737 8.142,12.19C7.023,13.447 6.339,15.046 6.339,17.006C6.339,23.88 10.425,25.421 14.312,25.874C13.812,26.322 13.358,27.112 13.201,28.273C12.203,28.731 9.669,29.525 8.108,26.782C8.108,26.782 7.183,25.058 5.426,24.932C5.426,24.932 3.719,24.91 5.307,26.023C5.307,26.023 6.453,26.574 7.249,28.648C7.249,28.648 8.276,31.85 13.145,30.765C13.154,32.265 13.169,33.678 13.169,34.105C13.169,34.58 12.847,35.135 11.974,34.968C5.02,32.597 0.006,25.87 0.006,17.943C0.006,8.032 7.842,0 17.506,0Z" to PathFillType.EvenOdd,
        )
    }

    val LinkedIn: ImageVector by lazy {
        socialIcon(
            "LinkedIn",
            "M32.266,0H2.734C1.224,0 0,1.224 0,2.734V32.266C0,33.776 1.224,35 2.734,35H32.266C33.776,35 35,33.776 35,32.266V2.734C35,1.224 33.776,0 32.266,0ZM12.606,24.821H9.194V13.904H12.606V24.821ZM10.808,12.537H10.78C9.543,12.537 8.743,11.703 8.743,10.644C8.743,9.563 9.57,8.75 10.828,8.75C12.086,8.75 12.858,9.563 12.886,10.644C12.893,11.696 12.093,12.537 10.808,12.537ZM26.25,24.821H22.381V19.175C22.381,17.698 21.779,16.687 20.446,16.687C19.428,16.687 18.86,17.37 18.601,18.026C18.505,18.259 18.519,18.587 18.519,18.922V24.821H14.684C14.684,24.821 14.731,14.814 14.684,13.904H18.519V15.62C18.744,14.868 19.968,13.802 21.923,13.802C24.35,13.802 26.25,15.374 26.25,18.751V24.821Z" to PathFillType.NonZero,
        )
    }

    val Instagram: ImageVector by lazy {
        socialIcon(
            "Instagram",
            "M17.5,26.25C22.333,26.25 26.25,22.333 26.25,17.5C26.25,12.667 22.333,8.75 17.5,8.75C12.667,8.75 8.75,12.667 8.75,17.5C8.75,22.333 12.667,26.25 17.5,26.25ZM17.5,23.333C20.722,23.333 23.333,20.722 23.333,17.5C23.333,14.278 20.722,11.667 17.5,11.667C14.278,11.667 11.667,14.278 11.667,17.5C11.667,20.722 14.278,23.333 17.5,23.333Z" to PathFillType.EvenOdd,
            "M26.249,7.292C25.444,7.292 24.791,7.945 24.791,8.75C24.791,9.555 25.444,10.208 26.249,10.208C27.055,10.208 27.708,9.555 27.708,8.75C27.708,7.945 27.055,7.292 26.249,7.292Z" to PathFillType.NonZero,
            "M2.413,6.236C1.459,8.108 1.459,10.558 1.459,15.458V19.542C1.459,24.442 1.459,26.892 2.413,28.764C3.252,30.41 4.59,31.749 6.237,32.588C8.108,33.542 10.559,33.542 15.459,33.542H19.542C24.443,33.542 26.893,33.542 28.765,32.588C30.411,31.749 31.75,30.41 32.589,28.764C33.542,26.892 33.542,24.442 33.542,19.542V15.458C33.542,10.558 33.542,8.108 32.589,6.236C31.75,4.589 30.411,3.251 28.765,2.412C26.893,1.458 24.443,1.458 19.542,1.458H15.459C10.559,1.458 8.108,1.458 6.237,2.412C4.59,3.251 3.252,4.589 2.413,6.236ZM19.542,4.375H15.459C12.961,4.375 11.262,4.377 9.95,4.484C8.671,4.589 8.017,4.778 7.561,5.011C6.463,5.57 5.571,6.462 5.011,7.56C4.779,8.016 4.59,8.67 4.485,9.949C4.378,11.262 4.376,12.96 4.376,15.458V19.542C4.376,22.04 4.378,23.738 4.485,25.051C4.59,26.33 4.779,26.984 5.011,27.44C5.571,28.538 6.463,29.43 7.561,29.989C8.017,30.222 8.671,30.411 9.95,30.515C11.262,30.623 12.961,30.625 15.459,30.625H19.542C22.041,30.625 23.739,30.623 25.052,30.515C26.331,30.411 26.985,30.222 27.441,29.989C28.538,29.43 29.431,28.538 29.99,27.44C30.222,26.984 30.412,26.33 30.516,25.051C30.623,23.738 30.626,22.04 30.626,19.542V15.458C30.626,12.96 30.623,11.262 30.516,9.949C30.412,8.67 30.222,8.016 29.99,7.56C29.431,6.462 28.538,5.57 27.441,5.011C26.985,4.778 26.331,4.589 25.052,4.484C23.739,4.377 22.041,4.375 19.542,4.375Z" to PathFillType.EvenOdd,
        )
    }

    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    }

    val Person: ImageVector by lazy {
        icon(
            "Person",
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4z" +
                "m0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
        )
    }

    // Custom window title bar controls.

    val Minimize: ImageVector by lazy {
        icon("Minimize", "M6 11h12v2H6z")
    }

    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41" +
                " 17.59 19 19 17.59 13.41 12z",
        )
    }

    val Lock: ImageVector by lazy {
        icon(
            "Lock",
            "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10" +
                "c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2z" +
                "m-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z" +
                "m3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z",
        )
    }

    // Used as navigation rail icons; vendored to avoid relying on material-icons-core internals.

    val HomeOutlined: ImageVector by lazy {
        icon(
            "Home",
            "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
        )
    }

    val SettingsOutlined: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58" +
                "c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96" +
                "c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84" +
                "c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96" +
                "c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58" +
                "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61" +
                "l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54" +
                "c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54" +
                "c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32" +
                "c.12-.22.07-.47-.12-.61l-2.01-1.58z" +
                "M12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
        )
    }

    val Visibility: ImageVector by lazy {
        icon(
            "visibility",
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
        )
    }

    val VisibilityOff: ImageVector by lazy {
        icon(
            "visibility_off",
            "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.44-4.75C21.27 7.61 17 4.5 12 4.5c-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
        )
    }
}

private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

/** Social icons: 35x35 source viewport, one or more (pathData, fillType) subpaths. */
private fun socialIcon(name: String, vararg paths: Pair<String, PathFillType>): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 35f,
        viewportHeight = 35f,
    )
    paths.forEach { (data, fillType) ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = fillType,
        )
    }
    return builder.build()
}
