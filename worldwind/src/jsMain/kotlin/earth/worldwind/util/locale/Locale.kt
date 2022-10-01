package earth.worldwind.util.locale

import kotlinx.browser.window

actual val language = window.navigator.language.split('-')[0]
actual val country = window.navigator.language.split('-')[1]