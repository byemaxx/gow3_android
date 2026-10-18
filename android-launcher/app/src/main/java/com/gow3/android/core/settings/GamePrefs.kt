package com.gow3.android.core.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * `SharedPreferences("gow3")` 的唯一读写口（launcher 与 game 两侧共享的契约层）。
 *
 * 键名、默认值与读写全部集中在本类：其他代码不得再直接调用
 * `getSharedPreferences("gow3")`。历史上这些键散落在两个包 20+ 处裸访问，
 * 收敛后改键名/改默认值只需看这一处。
 *
 * 类本身是 public 而非 `internal`：`ro/cobrabm`（迁移后 `game/`）侧的 Java 代码
 * 也要调用，而 Kotlin `internal` 成员在字节码里会被改名，Java 无法引用。
 *
 * 键名与默认值是持久化契约：历史 APK 升级安装后必须读到原值，MUST NOT 改名或
 * 改默认值。新增键一律加在本类并在这里登记。
 */
class GamePrefs internal constructor(private val prefs: SharedPreferences) {

    /** 应用入口使用的构造：经 Gow3App 容器注入 Context。internal 主构造同时允许
     *  同模块宿主单测直接注入 SharedPreferences 替身（无 Robolectric 的替代方案）。 */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    )

    /**
     * 平台监听器的注册通道（`game_dir`/`update_dir` 变化驱动 UI 重扫）。
     * 仅供注册/注销监听使用，读写仍应走本类方法。
     */
    val raw: SharedPreferences get() = prefs

    // ---- 游戏与升级补丁目录 ----

    fun gameDir(): String? = prefs.getString(KEY_GAME_DIR, null)

    fun setGameDir(path: String) {
        prefs.edit().putString(KEY_GAME_DIR, path).apply()
    }

    fun updateDir(): String? = prefs.getString(KEY_UPDATE_DIR, null)

    fun setUpdateDir(path: String) {
        prefs.edit().putString(KEY_UPDATE_DIR, path).apply()
    }

    fun clearUpdateDir() {
        prefs.edit().remove(KEY_UPDATE_DIR).apply()
    }

    // ---- 触屏手柄与物理手柄 ----

    fun controllerOverlayEnabled(): Boolean = prefs.getBoolean(KEY_CONTROLLER_OVERLAY, false)

    fun setControllerOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTROLLER_OVERLAY, enabled).apply()
    }

    fun swapSquareTriangle(): Boolean = prefs.getBoolean(KEY_SWAP_SQUARE_TRIANGLE, false)

    fun setSwapSquareTriangle(on: Boolean) {
        prefs.edit().putBoolean(KEY_SWAP_SQUARE_TRIANGLE, on).apply()
    }

    /** 触屏按键布局的 X 坐标；未拖动过时返回布局默认值。 */
    fun overlayX(name: String, default: Float): Float =
        prefs.getFloat(OVERLAY_KEY_PREFIX + "x_" + name, default)

    /** 触屏按键布局的 Y 坐标；未拖动过时返回布局默认值。 */
    fun overlayY(name: String, default: Float): Float =
        prefs.getFloat(OVERLAY_KEY_PREFIX + "y_" + name, default)

    /** 拖动结束时持久化一个按键的位置（x/y 成对写入）。 */
    fun setOverlayPosition(name: String, x: Float, y: Float) {
        prefs.edit()
            .putFloat(OVERLAY_KEY_PREFIX + "x_" + name, x)
            .putFloat(OVERLAY_KEY_PREFIX + "y_" + name, y)
            .apply()
    }

    /** 重置触屏布局：删除全部 `overlay_*` 键，按键回到代码内默认位置。 */
    fun clearOverlayLayout() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(OVERLAY_KEY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ---- 性能 HUD（android-perf-hud-overlay） ----

    /** 性能 HUD 开关；默认关闭，与 spec「默认关闭」一致。 */
    fun perfHudEnabled(): Boolean = prefs.getBoolean(KEY_PERF_HUD_ENABLED, false)

    fun setPerfHudEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERF_HUD_ENABLED, enabled).apply()
    }

    /**
     * HUD 左上角 X 坐标；从未拖动过时返回 null（由 PerfHudOverlay 按默认右上角回退，
     * NaN/越界的兜底校验在 PerfHudMetrics.resolvePosition，两处共同构成 spec 的
     * 「异常位置数据不致丢失 HUD」契约）。
     */
    fun perfHudX(): Float? =
        if (prefs.contains(KEY_PERF_HUD_X)) prefs.getFloat(KEY_PERF_HUD_X, 0f) else null

    /** HUD 左上角 Y 坐标；语义同 [perfHudX]。 */
    fun perfHudY(): Float? =
        if (prefs.contains(KEY_PERF_HUD_Y)) prefs.getFloat(KEY_PERF_HUD_Y, 0f) else null

    /** 拖动松手时持久化 HUD 位置（x/y 成对写入）。 */
    fun setPerfHudPosition(x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_PERF_HUD_X, x)
            .putFloat(KEY_PERF_HUD_Y, y)
            .apply()
    }

    // ---- 界面语言 ----

    /** 语言覆盖值；未设置时为 [LANGUAGE_FOLLOW_SYSTEM]。 */
    fun language(): String = prefs.getString(KEY_LANGUAGE, LANGUAGE_FOLLOW_SYSTEM) ?: LANGUAGE_FOLLOW_SYSTEM

    fun setLanguage(value: String) {
        prefs.edit().putString(KEY_LANGUAGE, value).apply()
    }

    // ---- 驱动下载镜像代理 ----

    /** 玩家存储的原始镜像前缀选择；null/空白 = 未选择（跟随语言默认）。 */
    fun driverProxyRaw(): String? = prefs.getString(KEY_DRIVER_PROXY_PREFIX, null)

    fun setDriverProxyRaw(value: String) {
        prefs.edit().putString(KEY_DRIVER_PROXY_PREFIX, value).apply()
    }

    fun clearDriverProxy() {
        prefs.edit().remove(KEY_DRIVER_PROXY_PREFIX).apply()
    }

    // ---- 驱动包「已下载」标记 ----

    fun downloadedDriverUrls(): Set<String> =
        prefs.getStringSet(KEY_DRIVER_DOWNLOADED_URLS, emptySet()).orEmpty()

    fun markDriverUrlDownloaded(url: String) {
        prefs.edit().putStringSet(KEY_DRIVER_DOWNLOADED_URLS, downloadedDriverUrls() + url).apply()
    }

    companion object {
        /** SharedPreferences 文件名，与历史版本一致；改名会让玩家丢掉全部设置。 */
        const val NAME = "gow3"

        const val KEY_GAME_DIR = "game_dir"
        const val KEY_UPDATE_DIR = "update_dir"
        const val KEY_CONTROLLER_OVERLAY = "controller_overlay"
        const val KEY_SWAP_SQUARE_TRIANGLE = "swap_square_triangle"
        const val KEY_PERF_HUD_ENABLED = "perf_hud_enabled"
        const val KEY_PERF_HUD_X = "perf_hud_x"
        const val KEY_PERF_HUD_Y = "perf_hud_y"
        const val KEY_LANGUAGE = "language"
        const val KEY_DRIVER_PROXY_PREFIX = "driver_proxy_prefix"
        const val KEY_DRIVER_DOWNLOADED_URLS = "driver_downloaded_urls"

        /** 触屏布局键前缀：`overlay_x_<按键>` / `overlay_y_<按键>`。 */
        const val OVERLAY_KEY_PREFIX = "overlay_"

        /** 「跟随系统」的语言覆盖值（与 LanguageManager.FOLLOW_SYSTEM 同值）。 */
        const val LANGUAGE_FOLLOW_SYSTEM = "system"
    }
}
