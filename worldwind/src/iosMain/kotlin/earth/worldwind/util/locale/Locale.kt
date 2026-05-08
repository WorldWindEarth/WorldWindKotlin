package earth.worldwind.util.locale

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

actual val language: String get() = NSLocale.currentLocale.languageCode
actual val country: String get() = NSLocale.currentLocale.countryCode ?: ""
