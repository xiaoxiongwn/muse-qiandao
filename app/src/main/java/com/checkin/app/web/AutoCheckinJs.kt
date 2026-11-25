package com.checkin.app.web

/** 自动签到注入的 JS：先按按钮文字找，找不到再按 CSS 选择器找。
 *  找到就点，返回 "clicked:<按钮文字>"；找不到返回 "not_found"。
 *  页面加载完等 1.5 秒再跑，给 SPA 渲染留时间。 */
object AutoCheckinJs {
    fun clickScript(buttonText: String, selector: String): String = """
        (function() {
          var text = ${jsString(buttonText)};
          var sel = ${jsString(selector)};
          function visible(el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
          }
          var btn = null;
          if (text) {
            var els = document.querySelectorAll('button, a, input[type=submit], div[role=button], span');
            for (var i = 0; i < els.length; i++) {
              var t = (els[i].innerText || els[i].value || '').trim();
              if (t && t.indexOf(text) !== -1 && visible(els[i])) { btn = els[i]; break; }
            }
          }
          if (!btn && sel) btn = document.querySelector(sel);
          if (!btn) return 'not_found';
          btn.scrollIntoView({block: 'center'});
          btn.click();
          return 'clicked:' + ((btn.innerText || btn.value || '').trim());
        })();
    """.trimIndent()

    /** 签到后 2 秒检查页面是否出现成功关键词。 */
    fun successCheckScript(keyword: String): String = """
        (function() {
          var kw = ${jsString(keyword)};
          if (!kw) return 'no_keyword';
          return document.body.innerText.indexOf(kw) !== -1 ? 'success' : 'not_yet';
        })();
    """.trimIndent()

    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
