package com.checkin.app.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** 可选的广告屏蔽：只在网站开了 blockAds 时生效，每个站单独开关。
 *
 *  两层做法，缺一不可：
 *  1. 拦截请求：广告域名直接返回空内容，广告根本下载不下来，
 *     弹窗、横幅也就出不来。只拦已知广告/统计域名，不拦站本身的域名，
 *     所以正常图片、样式和签到按钮不受影响。
 *  2. 隐藏元素：页面加载完用 JS 把常见的广告容器藏起来，
 *     对付那些和本站同域、拦请求拦不掉的广告位。
 *
 *  限制先说清楚：这不是浏览器插件级别的规则库，只是一份常见广告域名
 *  清单 + 通用隐藏规则，能挡住绝大多数横幅、弹窗和视频前贴片，
 *  但个别站把广告和内容混在同一个域名、同一个请求里，就挡不干净。
 *  如果某个站开了屏蔽后签到按钮不见了、页面显示不全，
 *  把这个站的屏蔽关掉就行，不用删站。
 */
object AdBlocker {

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com",
        "adservice.google.com", "ads.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "criteo.com",
        "criteo.net", "outbrain.com", "taboola.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "adsafeprotected.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com", "adform.net",
        "advertising.com", "ads.yahoo.com", "analytics.yahoo.com",
        // 注意：不拦 facebook.net / connect.facebook.net，
        // 有的站用它做第三方登录，拦了会导致登录按钮点不了。
        "baidu.com", // 注意：下面只拦百度广告子域，见 isAdUrl 的精确匹配
        "bdstatic.com", "pos.baidu.com", "cpro.baidu.com", "hm.baidu.com",
        "tanx.com", "alimama.com", "mmstat.com",
        "360.cn", "qchannel03.cn",
        "admob.com", "adcolony.com", "applovin.com", "unityads.unity3d.com",
        "vungle.com", "chartboost.com", "inmobi.com", "mopub.com",
        "popads.net", "popcash.net", "adcash.com", "propellerads.com",
        "exoclick.com", "juicyads.com", "trafficjunky.net",
        "hotjar.com", "mixpanel.com", "segment.com", "intercom.io",
        "doubleverify.com", "iasds01.com"
    )

    // 这些子串出现在 URL 里就拦：广告请求常见的路径/参数。
    private val adUrlHints = listOf(
        "/ads/", "/ad/", "/advert", "/banner", "doubleclick",
        "googlesyndication", "googleadservices", "adservice",
        "popunder", "interstitial"
    )

    // 登录白名单：这些域永远不拦。hax.co.id 就是用 Telegram 登录组件
    // （oauth.telegram.org 的 iframe + 弹窗）登录的，拦了它就登不上、
    // 也记不住登录态；同理 Google / Facebook 登录也不拦。
    private val neverBlockHosts = setOf(
        "telegram.org", "oauth.telegram.org", "t.me",
        "accounts.google.com", "oauth2.googleapis.com",
        "facebook.com", "connect.facebook.net"
    )

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host = try {
            java.net.URI(lower).host ?: lower.substringBefore("/")
        } catch (e: Exception) {
            lower.substringBefore("/")
        } ?: return false
        for (safe in neverBlockHosts) {
            if (host == safe || host.endsWith(".$safe")) return false
        }
        // 精确匹配：host 本身或其父域在清单里才拦。
        // 例如 pos.baidu.com 会被拦，但 www.baidu.com 不会，
        // 避免把整个百度都拦掉。
        for (adHost in adHosts) {
            if (host == adHost || host.endsWith(".$adHost")) {
                // baidu.com / 360.cn 本身是整站，不拦，只拦它们的广告子域
                if (adHost == "baidu.com" && host == "baidu.com") continue
                if (adHost == "360.cn" && host == "360.cn") continue
                return true
            }
        }
        if (adUrlHints.any { lower.contains(it) }) return true
        return false
    }

    /** 拦截时返回的空响应：广告请求拿到空内容就直接失败，不弹窗。 */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // 主页面本身不拦，只拦它加载的子资源（图片、脚本、iframe）。
        if (request.isForMainFrame) return false
        return isAdUrl(url)
    }

    /** 页面加载完注入：隐藏常见广告容器 + 常见弹窗。 */
    fun hideAdsJs(): String = """
        (function() {
          try {
            var sels = [
              '[id*="google_ads"]', '[id*="adsbygoogle"]', '[class*="adsbygoogle"]',
              '[id^="ad-"]', '[id*="-ad-"]', '[id*="_ad_"]', '[class*="ad-container"]',
              '[class*="ad-banner"]', '[class*="advertisement"]', '[class*="adsbox"]',
              'iframe[src*="doubleclick"]', 'iframe[src*="googlesyndication"]',
              'iframe[src*="ads"]', '.popup-ad', '.modal-ad'
              // 注意：不能加 '[id*="popup"]' —— Telegram、Cloudflare 这些
              // 登录/验证弹窗的容器 id 里也带 popup，藏了它就登不上。
            ];
            sels.forEach(function(sel) {
              document.querySelectorAll(sel).forEach(function(el) {
                el.style.display = 'none';
              });
            });
            var style = document.createElement('style');
            style.textContent = '.adsbygoogle, [id*="google_ads"] { display: none !important; }';
            document.head.appendChild(style);
          } catch (e) {}
          return 'done';
        })();
    """.trimIndent()
}
