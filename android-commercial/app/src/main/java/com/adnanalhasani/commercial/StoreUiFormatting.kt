package com.adnanalhasani.commercial

import java.util.Locale

internal fun money(value: Int): String = String.format(Locale.US, "%,d ر.ي", value)
