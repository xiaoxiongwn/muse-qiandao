package com.checkin.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.checkin.app.data.*
import com.checkin.app.ui.LogScreen
import com.checkin.app.ui.WebViewScreen
import com.checkin.app.worker.CheckinWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        CheckinWorker.scheduleDaily(this) // 按保存的时间排每天定时，默认 8:00
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "checkin.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        val dao = db.siteDao()
        setContent { CheckinApp(dao) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CheckinApp(dao: SiteDao) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sites by dao.observeSites().collectAsState(initial = emptyList())
    val logs by dao.observeLogs().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editSite by remember { mutableStateOf<Site?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var openSite by remember { mutableStateOf<Site?>(null) }
    var autoMode by remember { mutableStateOf(false) }
    // 不能写成 var (hour, minute) by remember { ... }：解构声明不能用 by 委托。
    var schedHour by remember { mutableStateOf(CheckinWorker.getScheduledTime(context).first) }
    var schedMinute by remember { mutableStateOf(CheckinWorker.getScheduledTime(context).second) }

    fun saveLog(site: Site, ok: Boolean, mode: String, msg: String) {
        scope.launch { dao.insertLog(CheckinLog(siteId = site.id, siteName = site.name, success = ok, mode = mode, message = msg)) }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("每日签到") }) },
            floatingActionButton = {
                if (tab == 0 && openSite == null) {
                    FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
                }
            }
        ) { pad ->
            if (openSite != null) {
                val site = openSite!!
                WebViewScreen(
                    site = site, auto = autoMode,
                    onResult = { ok, msg -> saveLog(site, ok, if (autoMode) "AUTO_WEBVIEW" else "MANUAL", msg) },
                    onCookieSaved = { cookie -> scope.launch { dao.update(site.copy(cookieSnapshot = cookie)) } },
                    onClose = { openSite = null },
                    onBlockAdsChanged = { enabled -> scope.launch { dao.update(site.copy(blockAds = enabled)) } }
                )
                return@Scaffold
            }
            Column(Modifier.padding(pad).fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("网站") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("记录") })
                }
                if (tab == 0) {
                    // 签到时间设置：改完立即重新排定时任务
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("每天签到时间：%02d:%02d".format(schedHour, schedMinute))
                        TextButton(onClick = { showTimePicker = true }) { Text("更改时间") }
                    }
                    Text(
                        "有 Cloudflare 的站请点“打开”先登录，再点“签到”。走代理的站会在卡片上显示代理地址。",
                        Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        if (sites.isEmpty()) item { Text("还没有网站，点右下角 + 添加第一个。") }
                        items(sites) { site ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(site.name, style = MaterialTheme.typography.titleMedium)
                                    Text(site.homeUrl, style = MaterialTheme.typography.bodySmall)
                                    if (site.hasProxy()) Text(
                                        "代理：${site.proxyLabel()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        if (site.blockAds) "屏蔽广告：开" else "屏蔽广告：关",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { openSite = site; autoMode = true }) { Text("签到") }
                                        OutlinedButton(onClick = { openSite = site; autoMode = false }) { Text("打开/登录") }
                                        OutlinedButton(onClick = { editSite = site }) { Text("编辑") }
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                CookieManager.getInstance().removeAllCookies(null)
                                                dao.delete(site.id)
                                            }
                                        }) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LogScreen(logs)
                }
            }
            if (showAdd || editSite != null) AddSiteDialog(
                initial = editSite,
                onDismiss = { showAdd = false; editSite = null },
                onSave = { site ->
                    scope.launch {
                        if (editSite != null) dao.update(site) else dao.insert(site)
                        showAdd = false; editSite = null
                    }
                }
            )
            if (showTimePicker) {
                val state = rememberTimePickerState(
                    initialHour = schedHour, initialMinute = schedMinute, is24Hour = true
                )
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    title = { Text("每天签到时间") },
                    text = { TimePicker(state = state) },
                    confirmButton = {
                        Button(onClick = {
                            schedHour = state.hour; schedMinute = state.minute
                            CheckinWorker.saveScheduledTime(context, state.hour, state.minute)
                            showTimePicker = false
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSiteDialog(initial: Site? = null, onDismiss: () -> Unit, onSave: (Site) -> Unit) {
    // initial 不为空就是编辑：所有输入框回填已有配置，改完保存不会丢登录 Cookie。
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var home by remember { mutableStateOf(initial?.homeUrl ?: "https://") }
    var checkin by remember { mutableStateOf(initial?.checkinUrl ?: "") }
    var btn by remember { mutableStateOf(initial?.buttonText ?: "签到") }
    var selector by remember { mutableStateOf(initial?.buttonSelector ?: "") }
    var keyword by remember { mutableStateOf(initial?.successKeyword ?: "已签到") }
    // 代理
    var proxyType by remember { mutableStateOf(initial?.proxyType ?: "NONE") }
    var proxyHost by remember { mutableStateOf(initial?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(if ((initial?.proxyPort ?: 0) > 0) initial!!.proxyPort.toString() else "") }
    var proxyUser by remember { mutableStateOf(initial?.proxyUser ?: "") }
    var proxyPass by remember { mutableStateOf(initial?.proxyPass ?: "") }
    var blockAds by remember { mutableStateOf(initial?.blockAds ?: true) }
    var proxyMenu by remember { mutableStateOf(false) }
    val proxyTypes = listOf("NONE" to "直连（不走代理）", "HTTP" to "HTTP 代理", "SOCKS5" to "SOCKS5 代理")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加签到网站" else "编辑网站") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名字，如 FreeCloud") })
                OutlinedTextField(home, { home = it }, label = { Text("首页/登录地址") })
                OutlinedTextField(checkin, { checkin = it }, label = { Text("签到页地址（空=用首页）") })
                OutlinedTextField(btn, { btn = it }, label = { Text("签到按钮文字") })
                OutlinedTextField(selector, { selector = it }, label = { Text("按钮 CSS 选择器（可选）") })
                OutlinedTextField(keyword, { keyword = it }, label = { Text("成功关键词") })
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("屏蔽广告")
                    Switch(checked = blockAds, onCheckedChange = { blockAds = it })
                }
                Text(
                    "开：拦广告请求、藏广告位；关：广告原样显示。有的站屏蔽后按钮会不见，关掉再刷新就好。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("代理（可选，只对这个站生效）", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { proxyMenu = true }) {
                        Text(proxyTypes.first { it.first == proxyType }.second)
                    }
                    DropdownMenu(expanded = proxyMenu, onDismissRequest = { proxyMenu = false }) {
                        proxyTypes.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { proxyType = value; proxyMenu = false })
                        }
                    }
                }
                if (proxyType != "NONE") {
                    OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("代理地址，如 127.0.0.1") })
                    OutlinedTextField(proxyPort, { proxyPort = it }, label = { Text("代理端口，如 7890") })
                    OutlinedTextField(proxyUser, { proxyUser = it }, label = { Text("代理账号（无账号可空）") })
                    OutlinedTextField(proxyPass, { proxyPass = it }, label = { Text("代理密码（无密码可空）") })
                    Text(
                        "注意：WebView 前台只支持 HTTP 代理；SOCKS5 只对后台定时签到生效，前台打开 SOCKS5 的站可能打不开，详见 README。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && home.startsWith("http") &&
                    (proxyType == "NONE" || (proxyHost.isNotBlank() && (proxyPort.toIntOrNull() ?: 0) in 1..65535)),
                onClick = {
                    onSave(
                        Site(
                            id = initial?.id ?: 0,
                            name = name.trim(), homeUrl = home.trim(),
                            checkinUrl = checkin.trim(), buttonText = btn.trim(),
                            buttonSelector = selector.trim(), successKeyword = keyword.trim(),
                            // 编辑时保留原值：登录 Cookie、签到方式、创建时间都不重置，
                            // 所以改代理不用重新登录。
                            httpMethod = initial?.httpMethod ?: "POST",
                            httpBody = initial?.httpBody ?: "",
                            cookieSnapshot = initial?.cookieSnapshot ?: "",
                            proxyType = proxyType, proxyHost = proxyHost.trim(),
                            proxyPort = proxyPort.toIntOrNull() ?: 0,
                            proxyUser = proxyUser.trim(), proxyPass = proxyPass,
                            blockAds = blockAds,
                            enabled = initial?.enabled ?: true,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            ) { Text(if (initial == null) "保存" else "保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
