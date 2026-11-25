package com.checkin.app.web

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.checkin.app.data.Site
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy

/** 代理：WebView（前台打开/登录/签到）和 OkHttp（后台定时签到）两条路。
 *
 *  限制先说清楚，这是 Android 本身的限制，不是这个 App 偷懒：
 *  1. WebView 的代理是整个 App 共用的（ProxyController），不是每个 WebView
 *     独立的。所以打开 A 站（走代理）再打开 B 站（直连）时，App 会在切站
 *     时先改全局代理再加载页面；不要同时开两个站。
 *  2. WebView 只支持 HTTP 代理，不支持 SOCKS5。SOCKS5 只对后台 HTTP
 *     签到（OkHttp）生效；前台要走 SOCKS5 的站，请在手机上用系统 VPN /
 *     Clash 这类全局代理，或把 SOCKS5 转成 HTTP 代理再填进来。
 *  3. HTTP 代理的账号密码：WebView 靠 onReceivedHttpAuthRequest 回填，
 *     见 Screens.kt；OkHttp 靠 proxyAuthenticator。
 */
object ProxyHelper {

    /** 给后台 HTTP 签到用的 OkHttpClient 加上该站的代理。 */
    fun applyToOkHttp(site: Site, builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (!site.hasProxy()) return builder
        val type = if (site.proxyType == "SOCKS5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        builder.proxy(Proxy(type, InetSocketAddress(site.proxyHost, site.proxyPort)))
        if (site.proxyUser.isNotBlank()) {
            builder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    val credential = okhttp3.Credentials.basic(site.proxyUser, site.proxyPass)
                    return response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            })
        }
        return builder
    }

    /** 打开某个站前调用：有代理就设全局代理，没代理就清除。返回 true=已生效/已清除。 */
    fun applyToWebView(site: Site): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        return try {
            val mainExecutor = java.util.concurrent.Executor { cmd ->
                Handler(Looper.getMainLooper()).post(cmd)
            }
            if (!site.hasProxy() || site.proxyType != "HTTP") {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) {}
                return true
            }
            val config = ProxyConfig.Builder()
                .addProxyRule("${site.proxyHost}:${site.proxyPort}")
                .build()
            // setProxyOverride 是异步的；回调里才真正生效，调用方加载页面
            // 前已经给了系统一点时间，一般够用，见 Screens.kt 的延迟说明。
            ProxyController.getInstance().setProxyOverride(config, mainExecutor) {}
            true
        } catch (e: Exception) {
            false
        }
    }
}
