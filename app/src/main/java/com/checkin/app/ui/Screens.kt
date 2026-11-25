package com.checkin.app.ui

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.checkin.app.data.CheckinLog
import com.checkin.app.data.Site
import com.checkin.app.web.AdBlocker
import com.checkin.app.web.AutoCheckinJs
import com.checkin.app.web.ProxyHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** WebView 页：打开站点的首页（登录）或签到页（自动签到）。
 *  auto=true 时，页面加载完注入 JS 自动点签到按钮，2 秒后按成功关键词判定，
 *  回调 onResult(success, message)。登录成功后把 Cookie 快照回传 onCookieSaved，
 *  供后台 HTTP 签到用。 */
@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    site: Site,
    auto: Boolean,
    onResult: (Boolean, String) -> Unit,
    onCookieSaved: (String) -> Unit,
    onClose: () -> Unit,
    onBlockAdsChanged: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf(
            if (site.hasProxy() && site.proxyType == "SOCKS5")
                "注意：WebView 不支持 SOCKS5，这个站前台打开可能打不开，后台 HTTP 签到会走 SOCKS5"
            else if (auto) "正在打开签到页…" else "请先登录该网站"
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Cloudflare 验证、续期确认这类小页面：网站用 window.open 弹出来的，
    // 之前没接 onCreateWindow，所以根本不出现。现在接到 popupWebView 里单独显示。
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    // 屏蔽开关：AtomicBoolean 给后台线程的拦截器读，Ui 状态给开关显示，
    // 两边一起改，改完立即刷新页面生效，并存进数据库。
    val blockAdsOn = remember { java.util.concurrent.atomic.AtomicBoolean(site.blockAds) }
    var blockAdsUi by remember { mutableStateOf(site.blockAds) }
    fun setBlockAds(v: Boolean) {
        blockAdsOn.set(v); blockAdsUi = v; onBlockAdsChanged(v)
        webView?.reload()
    }
    var finished by remember { mutableStateOf(false) }
    var resultOk by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf("") }
    val url = if (auto) site.checkinUrl.ifBlank { site.homeUrl } else site.homeUrl
    val proxyNote = if (site.hasProxy()) " · 代理：${site.proxyLabel()}" else ""

    // 系统返回键：先关小页面，再回首页，不用退出 App 再进来。
    BackHandler(onBack = { if (popupWebView != null) popupWebView = null else onClose() })

    // 关页面时把 Cookie 写到磁盘，否则登录态只在内存里，杀掉 App 就没了。
    DisposableEffect(Unit) {
        onDispose { CookieManager.getInstance().flush() }
    }

    fun handleResult(ok: Boolean, msg: String) {
        resultOk = ok; resultMsg = msg; finished = true
        onResult(ok, msg)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(site.name, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onClose) { Text("← 返回首页") }
        }
        Text(status + proxyNote, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(if (blockAdsUi) "屏蔽广告：开" else "屏蔽广告：关（广告原样显示）")
            Switch(checked = blockAdsUi, onCheckedChange = { setBlockAds(it) })
        }
        // 签到出结果（成功或失败）后，在页面上方给一个大按钮回首页，
        // 不用再找右上角那个小“关闭”。
        if (finished) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (resultOk)
                        androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                    else androidx.compose.ui.graphics.Color(0xFFFCE8E8)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (resultOk) "✅ 签到成功" else "❌ 签到失败", style = MaterialTheme.typography.titleMedium)
                    Text(resultMsg, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onClose, Modifier.padding(top = 8.dp)) { Text("返回首页，签下一个网站") }
                }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 弹窗支持：没有这两行，window.open 出来的验证小页面永远不会出现。
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    // 去掉 WebView 标志里的 "wv"，用普通手机 Chrome 的 UA，
                    // 有些站（包括 Cloudflare）对 WebView 会更严、更容易弹验证。
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    // 登录记不住的常见原因之一：第三方 Cookie 被禁，
                    // 有的站登录要经过另一个域名，不开就登不上、也留不住。
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                            resultMsg: Message
                        ): Boolean {
                            val popup = WebView(view.context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.userAgentString = view.settings.userAgentString
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    // Telegram 登录 / Cloudflare 验证完成后，小页面有时会
                                    // 跳回本站（比如 hax.co.id）：这时关掉小页面，
                                    // 让主页面接手，登录态才算真正落到主页面。
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView, request: WebResourceRequest
                                    ): Boolean {
                                        val host = request.url.host ?: return false
                                        // 小页面跳回本站（比如登录完成）：关掉小页面，
                                        // 让主页面接手，登录态才算真正落到主页面。
                                        val siteHost = try {
                                            java.net.URI(site.homeUrl).host
                                        } catch (e: Exception) { null } ?: return false
                                        if (host == siteHost || host.endsWith(".$siteHost")) {
                                            popupWebView = null
                                            webView?.loadUrl(request.url.toString())
                                            return true
                                        }
                                        return false
                                    }
                                    override fun onPageFinished(v: WebView, url: String?) {
                                        super.onPageFinished(v, url)
                                        CookieManager.getInstance().flush()
                                        CookieManager.getInstance().getCookie(site.homeUrl)
                                            ?.let { onCookieSaved(it) }
                                        status = "验证小页面已打开，请在里面完成验证"
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView) {
                                        popupWebView = null
                                    }
                                }
                            }
                            popupWebView = popup
                            (resultMsg.obj as WebView.WebViewTransport).webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                        override fun onCloseWindow(window: WebView) {
                            popupWebView = null
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // 广告屏蔽：在后台线程拦截广告请求，返回空内容。
                        override fun shouldInterceptRequest(
                            view: WebView, request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (blockAdsOn.get() && AdBlocker.shouldBlock(request)) {
                                return AdBlocker.emptyResponse()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                        // 代理需要账号密码时自动回填；不是代理的认证不要在这里填。
                        override fun onReceivedHttpAuthRequest(
                            view: WebView, handler: HttpAuthHandler, host: String, realm: String
                        ) {
                            if (site.hasProxy() && site.proxyUser.isNotBlank()
                                && host == site.proxyHost
                            ) handler.proceed(site.proxyUser, site.proxyPass)
                            else super.onReceivedHttpAuthRequest(view, handler, host, realm)
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            // 存一份 Cookie 快照，供后台 HTTP 用；写盘，否则只在内存里。
                            CookieManager.getInstance().flush()
                            // 首页和签到页可能在不同子域，两个都取，合起来存，
                            // 只取首页会丢签到页的登录 Cookie。
                            val cookies = listOfNotNull(
                                CookieManager.getInstance().getCookie(site.homeUrl),
                                site.checkinUrl.ifBlank { null }
                                    ?.let { CookieManager.getInstance().getCookie(it) }
                            ).flatMap { it.split(";").map { c -> c.trim() } }
                                .distinct().joinToString("; ")
                            if (cookies.isNotBlank()) onCookieSaved(cookies)
                            // 屏蔽广告：把页面里常见的广告容器藏起来。
                            if (blockAdsOn.get()) view.evaluateJavascript(AdBlocker.hideAdsJs(), null)
                            if (!auto) { status = "已打开，请手动登录"; return }
                            scope.launch {
                                delay(1500)
                                view.evaluateJavascript(AutoCheckinJs.clickScript(site.buttonText, site.buttonSelector)) { r ->
                                    if (r?.contains("not_found") == true) {
                                        status = "未找到签到按钮（文字“${site.buttonText}”）"
                                        handleResult(false, "按钮未找到：请检查按钮文字/选择器，或手动点")
                                    } else {
                                        status = "已点击签到，正在确认结果…"
                                        // evaluateJavascript 的回调不是协程，delay 必须再包一层 launch。
                                        scope.launch {
                                            delay(2000)
                                            view.evaluateJavascript(AutoCheckinJs.successCheckScript(site.successKeyword)) { r2 ->
                                                val ok = r2?.contains("success") == true
                                                status = if (ok) "签到成功" else "点了，但没看到“${site.successKeyword}”"
                                                handleResult(ok, if (ok) "成功：出现“${site.successKeyword}”" else "已点击，未确认成功，请看页面")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 先切代理（WebView 代理是 App 全局的，切站时先改再加载），
                    // 再加载页面；代理切换是异步的，等 300ms 更稳。
                    ProxyHelper.applyToWebView(site)
                    postDelayed({ loadUrl(url) }, 300)
                    webView = this
                }
            }
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onClose) { Text("← 返回首页") }
            OutlinedButton(onClick = {
                webView?.evaluateJavascript(AutoCheckinJs.clickScript(site.buttonText, site.buttonSelector), null)
            }) { Text("手动点签到") }
            OutlinedButton(onClick = { webView?.reload() }) { Text("刷新") }
        }
    }

    // Cloudflare 验证小页面：全屏显示，完成后点关闭回到主页面。
    // 小页面里的操作（比如验证通过）会写回同一个 CookieManager，
    // 关掉后主页面刷新一次就能接着续期。
    if (popupWebView != null) {
        Dialog(
            onDismissRequest = { popupWebView = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .padding(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("验证小页面", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        popupWebView = null
                        webView?.reload()
                    }) { Text("关闭，回到主页面") }
                }
                Text(
                    "请在里面完成 Cloudflare 验证，完成后点上面“关闭”回到主页面。",
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                popupWebView?.let { popup ->
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { popup }
                    )
                }
            }
        }
    }
}

@Composable
fun LogScreen(logs: List<CheckinLog>) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        if (logs.isEmpty()) item { Text("暂无签到记录。定时跑完后这里会有结果。") }
        items(logs) { log ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${if (log.success) "✅ 成功" else "❌ 失败"}  ${log.siteName}  · ${log.mode}")
                    Text(log.message, style = MaterialTheme.typography.bodySmall)
                    Text(
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(log.time)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
