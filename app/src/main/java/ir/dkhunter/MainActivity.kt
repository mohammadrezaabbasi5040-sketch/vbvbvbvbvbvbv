package ir.dkhunter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var multiButton: Button
    private lateinit var pageButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var clearButton: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var pageLoad: CompletableDeferred<Unit>? = null
    private var stopReason: String? = null

    // فقط از ترد اصلی استفاده می‌شوند
    private val seen = HashSet<String>()
    private val found = LinkedHashSet<String>()

    private val prefs by lazy { getSharedPreferences("hunter", Context.MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ocr by lazy { OcrScanner(recognizer) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        // targetSdk 35 یعنی در اندروید ۱۵ برنامه زیر نوار وضعیت/ناوبری کشیده می‌شود؛ فاصله را خودمان می‌دهیم.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), bars.bottom + dp(8))
            insets
        }

        root.addView(TextView(this).apply {
            text = "شکارچی کد دیجی‌کالا ۵"
            textSize = 20f
            setPadding(0, 0, 0, dp(4))
        })
        status = TextView(this).apply {
            text = "صفحه مسابقه در حال باز شدن است."
            textSize = 14f
        }
        root.addView(status)

        multiButton = button("اسکن چندصفحه‌ای")
        pageButton = button("اسکن همین صفحه")
        root.addView(row(multiButton, pageButton))

        stopButton = button("توقف").apply { isEnabled = false }
        copyButton = button("کپی کدها")
        clearButton = button("پاک کردن")
        root.addView(row(stopButton, copyButton, clearButton))

        results = TextView(this).apply {
            text = EMPTY_RESULTS
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(
            ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110))
        )

        web = WebView(this)
        setupWebView()
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        multiButton.setOnClickListener { startScan(multiPage = true) }
        pageButton.setOnClickListener { startScan(multiPage = false) }
        stopButton.setOnClickListener { cancelScan("اسکن به درخواست شما متوقف شد.") }
        copyButton.setOnClickListener { copyCodes() }
        clearButton.setOnClickListener { clearHistory() }

        restoreHistory()
        web.loadUrl(START_URL)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val ok = request.url.scheme == "https" && isDigikalaHost(request.url.host)
                if (!ok) {
                    Toast.makeText(this@MainActivity, "لینک خارج از دیجی‌کالا مسدود شد.", Toast.LENGTH_SHORT).show()
                }
                return !ok
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoad?.complete(Unit)
                if (scanJob?.isActive != true) status.text = "صفحه آماده است."
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                val code = errorResponse.statusCode
                if (request.isForMainFrame && (code == 403 || code == 429)) {
                    cancelScan("محدودیت دسترسی سایت (HTTP $code) دریافت شد؛ برای رعایت آن اسکن متوقف شد.")
                }
            }
        }
    }

    private suspend fun navigate(url: String) {
        val loaded = CompletableDeferred<Unit>()
        pageLoad = loaded
        web.loadUrl(url)
        withTimeoutOrNull(PAGE_TIMEOUT_MS) { loaded.await() }
        pageLoad = null
        delay(1_000)
    }

    /** اجرای JS و گرفتن نتیجه (جایگزین JavascriptInterface؛ هیچ API‌ای به صفحهٔ وب باز نمی‌شود). */
    private suspend fun evalJs(js: String): String? = suspendCancellableCoroutine { c ->
        web.evaluateJavascript(js) { if (c.isActive) c.resume(it) }
    }

    // ------------------------------------------------------------------- scan

    private fun startScan(multiPage: Boolean) {
        if (scanJob?.isActive == true) return
        stopReason = null
        setScanning(true)
        scanJob = scope.launch {
            var message = "اسکن تمام شد."
            try {
                val pages = if (multiPage) MAX_PAGES else 1
                for (p in 1..pages) {
                    scanCurrentPage("صفحه $p از $pages")
                    if (p == pages) break
                    val next = findNextUrl()
                    if (next == null) {
                        message = "لینک صفحه بعدی پیدا نشد."
                        break
                    }
                    status.text = "رفتن به صفحه بعدی..."
                    navigate(next)
                }
            } catch (e: RateLimitedException) {
                message = "محدودیت دسترسی سایت (HTTP ${e.code}) دریافت شد؛ برای رعایت آن اسکن متوقف شد."
            } catch (e: CancellationException) {
                message = stopReason ?: "اسکن متوقف شد."
                throw e
            } finally {
                setScanning(false)
                status.text = "$message تصاویر بررسی‌شده: ${seen.size} | کدهای احتمالی: ${found.size}"
            }
        }
    }

    private fun cancelScan(reason: String) {
        if (stopReason == null) stopReason = reason
        scanJob?.cancel()
    }

    private suspend fun scanCurrentPage(label: String) {
        status.text = "$label؛ در حال بارگذاری تصاویر..."
        web.evaluateJavascript(PageScripts.SCROLL, null)
        delay(PageScripts.SCROLL_WAIT_MS)

        val urls = PageScripts.parseUrls(evalJs(PageScripts.COLLECT))
        // asSequence: فقط تا سقف MAX_IMAGES_PER_PAGE به seen اضافه می‌شود؛ بقیه برای اسکن بعدی می‌مانند.
        val batch = urls.asSequence()
            .filter { isAllowedImage(it) }
            .filter { seen.add(dedupeKey(it)) }
            .take(MAX_IMAGES_PER_PAGE)
            .toList()

        for ((i, imageUrl) in batch.withIndex()) {
            currentCoroutineContext().ensureActive()
            status.text = "$label؛ OCR تصویر ${i + 1} از ${batch.size} | کدها: ${found.size}"
            try {
                val codes = ocr.scan(imageUrl)
                if (!codes.isNullOrEmpty()) addFound(codes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: Exception) {
                seen.remove(dedupeKey(imageUrl)) // خطای شبکه؛ در اسکن بعدی دوباره امتحان شود
            }
            delay(REQUEST_DELAY_MS)
        }
    }

    private suspend fun findNextUrl(): String? {
        val url = PageScripts.unwrapString(evalJs(PageScripts.NEXT))?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol != "https" || !isDigikalaHost(parsed.host)) return null
        if (url == web.url) return null // جلوگیری از حلقه
        return url
    }

    // ---------------------------------------------------------------- helpers

    private fun isDigikalaHost(host: String?): Boolean =
        host != null && (host == "digikala.com" || host.endsWith(".digikala.com"))

    private fun isAllowedImage(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol == "https" && isDigikalaHost(parsed.host)
    }

    /** نسخه‌های مختلف اندازهٔ یک تصویر (پارامترهای resize در query) یک تصویر حساب می‌شوند. */
    private fun dedupeKey(url: String) = url.substringBefore('?').substringBefore('#')

    private fun setScanning(on: Boolean) {
        multiButton.isEnabled = !on
        pageButton.isEnabled = !on
        stopButton.isEnabled = on
        web.keepScreenOn = on
    }

    private fun addFound(codes: Set<String>) {
        var changed = false
        codes.forEach { if (found.add(it)) changed = true }
        if (changed) {
            renderResults()
            prefs.edit().putString(PREF_HISTORY, found.joinToString(",")).apply()
        }
    }

    private fun renderResults() {
        results.text = if (found.isEmpty()) {
            EMPTY_RESULTS
        } else {
            found.toList().asReversed().joinToString("\n") { "• $it" } // تازه‌ترین بالا
        }
    }

    private fun restoreHistory() {
        (prefs.getString(PREF_HISTORY, "") ?: "").split(",")
            .filter { it.isNotBlank() }.forEach { found.add(it) }
        renderResults()
    }

    private fun copyCodes() {
        if (found.isEmpty()) {
            Toast.makeText(this, "هنوز کدی پیدا نشده است.", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("codes", found.toList().asReversed().joinToString("\n")))
        Toast.makeText(this, "${found.size} کد کپی شد.", Toast.LENGTH_SHORT).show()
    }

    private fun clearHistory() {
        found.clear()
        prefs.edit().remove(PREF_HISTORY).apply()
        renderResults()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String) = Button(this).apply { text = label }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    // -------------------------------------------------------------- lifecycle

    override fun onStop() {
        // با رفتن به پس‌زمینه، تایمرهای JS صفحه متوقف می‌شوند و اسکن نتیجهٔ درستی نمی‌دهد.
        if (scanJob?.isActive == true) cancelScan("اسکن به‌دلیل خروج از برنامه متوقف شد.")
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        recognizer.close()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://www.digikala.com/landings/prize-catcher/?&tab=hunt"
        private const val PREF_HISTORY = "history"
        private const val EMPTY_RESULTS = "کدهای احتمالی اینجا نمایش داده می‌شوند."
        private const val MAX_PAGES = 5
        private const val MAX_IMAGES_PER_PAGE = 80
        private const val REQUEST_DELAY_MS = 700L
        private const val PAGE_TIMEOUT_MS = 20_000L
    }
}
