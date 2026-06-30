package earth.worldwind.util

import android.os.Build
import android.os.Trace

actual inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

actual fun traceAsyncBegin(name: String, cookie: Int) {
    Trace.beginAsyncSection(name, cookie)
}

actual fun traceAsyncEnd(name: String, cookie: Int) {
    Trace.endAsyncSection(name, cookie)
}

actual fun traceCounter(name: String, value: Long) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.setCounter(name, value)
}
