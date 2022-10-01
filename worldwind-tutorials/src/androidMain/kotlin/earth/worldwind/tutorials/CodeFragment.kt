package earth.worldwind.tutorials

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment

open class CodeFragment: Fragment() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_code, container, false)
        val webView = rootView.findViewById<WebView>(R.id.code_view)

        // Enable JavaScript (which is off by default)
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        arguments?.getString("url")?.let { webView.loadUrl(it) }
        return rootView
    }
}