package com.tgwsproxy.update

import com.tgwsproxy.S
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(val version: String, val url: String, val notes: String, val apkUrl: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NoReleases : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Проверка новой версии на https://github.com/HallowedMold900/tg-ws-proxy-android/releases/latest */
object UpdateChecker {
    const val AUTHOR = "HallowedMold900"
    const val REPO_URL = "https://github.com/HallowedMold900/tg-ws-proxy-android"
    const val UPSTREAM_URL = "https://github.com/Flowseal/tg-ws-proxy"
    const val LATEST_PAGE = "$REPO_URL/releases/latest"
    private const val API = "https://api.github.com/repos/HallowedMold900/tg-ws-proxy-android/releases/latest"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val notifyEnabled = MutableStateFlow(true)
    val lastCheckMs = MutableStateFlow(0L)

    private var prefs: SharedPreferences? = null

    private sealed interface Fetch {
        class Found(val info: ReleaseInfo) : Fetch
        data object NotFound : Fetch
        class Failed(val message: String) : Fetch
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        prefs = p
        notifyEnabled.value = p.getBoolean("enabled", true)
        lastCheckMs.value = p.getLong("lastCheck", 0L)
        // Если раньше уже находили более новую версию — показываем её сразу, без сети
        val v = p.getString("latestVersion", null)
        if (v != null && isNewer(v, currentVersion(context))) {
            state.value = UpdateState.Available(
                ReleaseInfo(v, p.getString("latestUrl", LATEST_PAGE) ?: LATEST_PAGE, p.getString("latestNotes", "") ?: "", p.getString("latestApk", null)),
            )
        }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        notifyEnabled.value = enabled
        prefs?.edit()?.putBoolean("enabled", enabled)?.apply()
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Автопроверка не чаще раза в 6 часов; вызывать из фонового потока. */
    fun checkIfDue(context: Context) {
        if (!notifyEnabled.value) return
        if (System.currentTimeMillis() - lastCheckMs.value < CHECK_INTERVAL_MS) return
        check(context, notify = true)
    }

    @Synchronized
    private fun begin(): Boolean {
        if (state.value is UpdateState.Checking) return false
        return true
    }

    /** Блокирующая проверка; вызывать из фонового потока. */
    fun check(context: Context, notify: Boolean) {
        if (!begin()) return
        val app = context.applicationContext
        val previous = state.value
        state.value = UpdateState.Checking

        var result = fetchApi()
        if (result is Fetch.Failed) {
            val viaRedirect = fetchRedirect()
            if (viaRedirect !is Fetch.Failed) result = viaRedirect
        }

        when (result) {
            is Fetch.Failed -> {
                state.value = if (previous is UpdateState.Available) previous else UpdateState.Error(S.get(R.string.err_check_failed, result.message))
            }
            Fetch.NotFound -> {
                markChecked()
                state.value = UpdateState.NoReleases
            }
            is Fetch.Found -> {
                markChecked()
                val info = result.info
                prefs?.edit()
                    ?.putString("latestVersion", info.version)
                    ?.putString("latestUrl", info.url)
                    ?.putString("latestNotes", info.notes)
                    ?.putString("latestApk", info.apkUrl)
                    ?.apply()
                if (isNewer(info.version, currentVersion(app))) {
                    state.value = UpdateState.Available(info)
                    if (notify && notifyEnabled.value && prefs?.getString("lastNotified", null) != info.version) {
                        if (postNotification(app, info)) prefs?.edit()?.putString("lastNotified", info.version)?.apply()
                    }
                } else {
                    state.value = UpdateState.UpToDate
                }
            }
        }
    }

    private fun markChecked() {
        val now = System.currentTimeMillis()
        lastCheckMs.value = now
        prefs?.edit()?.putLong("lastCheck", now)?.apply()
    }

    // ───────────── сеть ─────────────

    private fun fetchApi(): Fetch {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
            when (val code = conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    if (tag.isEmpty()) return Fetch.Failed("empty GitHub response")
                    var apk: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apk = a.optString("browser_download_url").ifEmpty { null }
                                break
                            }
                        }
                    }
                    Fetch.Found(
                        ReleaseInfo(
                            version = tag.trim().removePrefix("v").removePrefix("V"),
                            url = json.optString("html_url").ifEmpty { LATEST_PAGE },
                            notes = json.optString("body").trim(),
                            apkUrl = apk,
                        ),
                    )
                }
                404 -> Fetch.NotFound
                else -> Fetch.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Fetch.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** Запасной путь: страница /releases/latest перенаправляет на /releases/tag/<версия>. */
    private fun fetchRedirect(): Fetch {
        val conn = URL(LATEST_PAGE).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
            val code = conn.responseCode
            if (code in 300..399) {
                var loc = conn.getHeaderField("Location") ?: return Fetch.Failed("no redirect location")
                if (loc.startsWith("/")) loc = "https://github.com$loc"
                if ("/releases/tag/" in loc) {
                    val tag = loc.substringAfter("/releases/tag/").substringBefore('?').substringBefore('#')
                    Fetch.Found(ReleaseInfo(Uri.decode(tag).removePrefix("v").removePrefix("V"), loc, "", null))
                } else {
                    Fetch.NotFound
                }
            } else if (code == 200 || code == 404) {
                Fetch.NotFound
            } else {
                Fetch.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Fetch.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    // ───────────── сравнение версий и уведомление ─────────────

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun postNotification(context: Context, info: ReleaseInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, S.get(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 5, Intent(Intent.ACTION_VIEW, Uri.parse(info.url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle(S.get(R.string.notif_update_title))
                .setContentText(S.get(R.string.notif_update_text, info.version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
