package com.hazia.emivita.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The floor for dose amounts, times and schedule text: never smaller than
 * [Typography.labelMedium] (12sp).
 *
 * Spec section 7 T6 flagged that nothing stopped dose/schedule-critical text
 * rendering at `labelSmall` (11sp, the smallest style in the ramp), "given the
 * app's own safety-critical-adherence purpose (section 1)", and left it "not
 * yet made a hard rule". Auditing the tree found the rule already holds -- the
 * only `labelSmall` call sites are weekday-column headers, a "no barcode
 * scanner installed" notice, and a storage note, none of which is a dose, a
 * time or a schedule. It held by accident, though: nothing recorded it, so the
 * next screen to reuse `labelSmall` for a chip label was free to reach for it
 * for a dose amount too.
 *
 * Naming it here puts the rule at the point of decision -- beside the ramp
 * anyone picking a style is reading -- rather than in a document they are not.
 * Use this in preference to writing `labelMedium` inline when the text is a
 * dose, a time, or a schedule, so the choice stays greppable and a future
 * change of floor is one edit. See docs/SPEC.md section 7 R42.
 */
val Typography.doseCritical: TextStyle get() = labelMedium

val EmivitaTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)
