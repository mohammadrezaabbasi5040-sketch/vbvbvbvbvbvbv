package ir.dkhunter

import org.json.JSONArray
import org.json.JSONTokener

/** اسکریپت‌های JS که داخل WebView اجرا می‌شوند + تبدیل نتیجه‌شان به مقدار کاتلین. */
object PageScripts {

    /** اسکرول تدریجی برای بارگذاری تصاویر lazy-load (حدود ۵ ثانیه). */
    val SCROLL = """
        (function(){
          let n = 0;
          const t = setInterval(function(){
            window.scrollBy(0, Math.max(500, window.innerHeight * 0.8));
            n++;
            if (n >= 12) { clearInterval(t); window.scrollTo(0, 0); }
          }, 400);
          return 'ok';
        })();
    """.trimIndent()
    const val SCROLL_WAIT_MS = 5500L

    /** آدرس تصاویر صفحه را به‌صورت JSON برمی‌گرداند (بدون پل JavascriptInterface). */
    val COLLECT = """
        (function(){
          const out = new Set();
          const add = function(u){
            try {
              if (!u) return;
              const h = new URL(String(u).trim(), document.baseURI).href;
              if (h.indexOf('https://') === 0) out.add(h);
            } catch (e) {}
          };
          document.querySelectorAll('img').forEach(function(i){
            if (i.complete && i.naturalWidth > 0 && i.naturalWidth < 100) return; // آیکن‌های کوچک
            let best = '';
            const s = i.getAttribute('srcset');
            if (s) {
              const parts = s.split(',');
              best = parts[parts.length - 1].trim().split(/\s+/)[0];
            }
            best = best || i.getAttribute('data-src') || i.getAttribute('data-original')
                        || i.getAttribute('data-lazy-src') || i.currentSrc || i.src;
            add(best);
          });
          document.querySelectorAll('picture source[srcset]').forEach(function(el){
            const parts = el.getAttribute('srcset').split(',');
            add(parts[parts.length - 1].trim().split(/\s+/)[0]);
          });
          document.querySelectorAll('[style*="background"]').forEach(function(el){
            const m = /url\(["']?([^"')]+)["']?\)/.exec(el.getAttribute('style') || '');
            if (m) add(m[1]);
          });
          return JSON.stringify(Array.from(out));
        })();
    """.trimIndent()

    /** آدرس لینک «بعدی» یا رشتهٔ خالی. */
    val NEXT = """
        (function(){
          const links = Array.from(document.querySelectorAll('a[href]'));
          const byRel = links.find(function(x){ return x.rel === 'next'; });
          const a = byRel || links.find(function(x){
            const t = ((x.innerText || '') + ' ' + (x.getAttribute('aria-label') || '') + ' ' + (x.title || '')).toLowerCase();
            return t.indexOf('بعدی') >= 0 || t.indexOf('صفحه بعد') >= 0 || t.trim() === 'next';
          });
          return a ? a.href : '';
        })();
    """.trimIndent()

    /** نتیجهٔ evaluateJavascript یک رشتهٔ JSON-escape شده است؛ اینجا باز می‌شود. */
    fun unwrapString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    fun parseUrls(raw: String?): List<String> {
        val inner = unwrapString(raw) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(inner)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}
