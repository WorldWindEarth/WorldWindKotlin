package earth.worldwind.util

actual inline fun <T> traceSection(name: String, block: () -> T): T = block()
actual fun traceAsyncBegin(name: String, cookie: Int) {}
actual fun traceAsyncEnd(name: String, cookie: Int) {}
actual fun traceCounter(name: String, value: Long) {}
