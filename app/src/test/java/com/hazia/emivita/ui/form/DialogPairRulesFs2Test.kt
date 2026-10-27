package com.hazia.emivita.ui.form

import org.robolectric.annotation.Config

/**
 * Every dialog pair of [DialogPairRulesTest] at font 2.0 (§7 R128): at 1.0 alone, "Usa questo colore"
 * and "Elimina tutto" were cut short at large font with every test green.
 */
@Config(fontScale = 2f)
class DialogPairRulesFs2Test : DialogPairRulesTest()
