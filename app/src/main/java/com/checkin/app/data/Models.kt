package com.checkin.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 一个签到网站。Cookie 不存在这里：登录态在系统 WebView CookieManager 里，
 *  按域名隔离；这里只存一份 Cookie 快照，供后台 HTTP 签到用。 */
@Entity(tableName = "sites")
data class Site(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,               // 显示名，如 FreeCloud
    val homeUrl: String,            // 首页/登录页，如 https://panel.freecloud.ltd/
    val checkinUrl: String,         // 签到页地址（空 = 用首页）
    val buttonText: String = "签到", // 优先按文字找按钮
    val buttonSelector: String = "",// 文字找不到时用的 CSS 选择器，如 #checkin-btn
    val successKeyword: String = "已签到", // 成功后页面出现的字
    val httpMethod: String = "POST",// 后台 HTTP 签到用：GET 或 POST
    val httpBody: String = "",      // POST 时的 body，空 = 无 body
    val cookieSnapshot: String = "",// 登录后从 CookieManager 拷来的 Cookie，供后台用
    // 代理：只对这个站生效。NONE=直连，HTTP=HTTP 代理，SOCKS5=SOCKS5 代理。
    // 注意：WebView 只支持 HTTP 代理，SOCKS5 只对后台 HTTP 签到生效，见 ProxyHelper。
    // 账号密码明文存在本地数据库里，只存在你自己手机上，不要把数据库文件发给别人。
    val proxyType: String = "NONE",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    // 屏蔽广告：每个站单独开关，默认开。关掉后广告原样显示（有的站屏蔽后会
    // 误伤正常按钮，关掉就能恢复，不用删站重加）。
    val blockAds: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun hasProxy(): Boolean =
        proxyType != "NONE" && proxyHost.isNotBlank() && proxyPort in 1..65535
    fun proxyLabel(): String =
        if (hasProxy()) "$proxyType $proxyHost:$proxyPort" else "直连"
}

@Entity(tableName = "checkin_logs")
data class CheckinLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteId: Long,
    val siteName: String,
    val time: Long = System.currentTimeMillis(),
    val success: Boolean,
    val mode: String,   // AUTO_WEBVIEW / AUTO_HTTP / MANUAL
    val message: String // 成功关键词 / 失败原因，如 网络异常、按钮未找到、需重新登录
)

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY createdAt ASC")
    fun observeSites(): Flow<List<Site>>

    @Query("SELECT * FROM sites WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun enabledSites(): List<Site>

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun get(id: Long): Site?

    @Insert suspend fun insert(site: Site): Long
    @Update suspend fun update(site: Site)
    @Query("DELETE FROM sites WHERE id = :id") suspend fun delete(id: Long)

    @Insert suspend fun insertLog(log: CheckinLog)
    @Query("SELECT * FROM checkin_logs ORDER BY time DESC LIMIT 200")
    fun observeLogs(): Flow<List<CheckinLog>>

    @Query("SELECT * FROM checkin_logs WHERE siteId = :siteId ORDER BY time DESC LIMIT 1")
    suspend fun lastLog(siteId: Long): CheckinLog?
}

@Database(entities = [Site::class, CheckinLog::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao

    companion object {
        // v1 -> v2：只加 blockAds 一列，已有网站和登录 Cookie 都保留，
        // 老版本升级上来不会丢数据、不会闪退。
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sites ADD COLUMN blockAds INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
