package com.tianmeng.security.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "tianmeng_security"
    private const val KEY_SHAKE_TRIGGER = "shake_trigger"
    private const val KEY_VOLUME_TRIGGER = "volume_trigger"
    private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
    private const val KEY_FROZEN_APPS = "frozen_apps"
    private const val KEY_FREEZE_MODES = "freeze_modes"
    private const val KEY_SHIELD_ENABLED = "shield_enabled"
    private const val KEY_BLOCK_MODE = "block_mode"
    private const val KEY_VOLUME_KEY = "volume_key"
    private const val KEY_VOLUME_COUNT = "volume_press_count"
    private const val KEY_SHAKE_LEVEL = "shake_level"
    private const val KEY_ROOT_GUARD = "root_guard"
    private const val KEY_FEEDBACK_ACCOUNT = "feedback_account"
    private const val KEY_WHITELIST = "virus_whitelist"
    private const val KEY_MUTE_GUARD = "mute_guard"
    private const val KEY_MUTE_VOLUMES = "mute_saved_volumes"
    private const val KEY_MUTE_RINGER = "mute_saved_ringer"
    private const val KEY_MUTE_DND = "mute_saved_dnd"
    private const val KEY_CLICKER_POINTS = "clicker_points"
    private const val KEY_CLICKER_INTERVAL = "clicker_interval"
    private const val KEY_CLICKER_PRESS = "clicker_press"
    private const val KEY_CLICKER_LIMIT = "clicker_limit"
    private const val KEY_CLICKER_RANDOM = "clicker_random"
    private const val KEY_SMART_BLOCK = "smart_block"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_ANTI_FLASH = "anti_flash"


    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var shakeTrigger: Boolean
        get() = sp.getBoolean(KEY_SHAKE_TRIGGER, true)
        set(value) = sp.edit().putBoolean(KEY_SHAKE_TRIGGER, value).apply()

    var volumeTrigger: Boolean
        get() = sp.getBoolean(KEY_VOLUME_TRIGGER, true)
        set(value) = sp.edit().putBoolean(KEY_VOLUME_TRIGGER, value).apply()

    var shakeSensitivity: Int
        get() = sp.getInt(KEY_SHAKE_SENSITIVITY, 5)
        set(value) = sp.edit().putInt(KEY_SHAKE_SENSITIVITY, value).apply()

    /** 触发按键：0 = 音量 -，1 = 音量 + */
    var volumeKey: Int
        get() = sp.getInt(KEY_VOLUME_KEY, 0)
        set(value) = sp.edit().putInt(KEY_VOLUME_KEY, value).apply()

    /** 音量键连按次数（1~3），默认 3 次 */
    var volumePressCount: Int
        get() = sp.getInt(KEY_VOLUME_COUNT, 3).coerceIn(1, 3)
        set(value) = sp.edit().putInt(KEY_VOLUME_COUNT, value.coerceIn(1, 3)).apply()

    /** 摇一摇力度：0 轻摇 / 1 标准 / 2 用力 / 3 猛摇（默认猛摇，几乎不误触） */
    var shakeLevel: Int
        get() = sp.getInt(KEY_SHAKE_LEVEL, 3).coerceIn(0, 3)
        set(value) = sp.edit().putInt(KEY_SHAKE_LEVEL, value.coerceIn(0, 3)).apply()

    /** 护盾是否应保持开启（用于打开应用/开机时自动恢复） */
    var shieldEnabled: Boolean
        get() = sp.getBoolean(KEY_SHIELD_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SHIELD_ENABLED, value).apply()

    /** 格机防护（仅 Root 可用） */
    var rootGuard: Boolean
        get() = sp.getBoolean(KEY_ROOT_GUARD, false)
        set(value) = sp.edit().putBoolean(KEY_ROOT_GUARD, value).apply()

    /** 反馈时使用的用户账号（留空则使用设备标识） */
    var feedbackAccount: String
        get() = sp.getString(KEY_FEEDBACK_ACCOUNT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_FEEDBACK_ACCOUNT, value.trim()).apply()

    /**
     * 拦截模式：
     * 0 = 基础，1 = 强力，2 = Super Shield（默认），3 = 终结
     */
    var blockMode: Int
        get() = sp.getInt(KEY_BLOCK_MODE, 1) // 0 基础 / 1 强力 / 3 终结
        set(value) = sp.edit().putInt(KEY_BLOCK_MODE, value).apply()

    /** 用户白名单：不参与病毒判定，也不会被自动冻结 / 卸载 */
    fun getWhitelist(): Set<String> {
        return sp.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    fun setWhitelist(packages: Set<String>) {
        sp.edit().putStringSet(KEY_WHITELIST, packages).apply()
    }

    fun addToWhitelist(packageName: String) {
        setWhitelist(getWhitelist() + packageName)
    }

    fun removeFromWhitelist(packageName: String) {
        setWhitelist(getWhitelist() - packageName)
    }

    /** 一键静音守卫是否开启 */
    var muteGuard: Boolean
        get() = sp.getBoolean(KEY_MUTE_GUARD, false)
        set(value) = sp.edit().putBoolean(KEY_MUTE_GUARD, value).apply()

    /** 静音前各音频流的音量，格式 "music,ring,notification,alarm,system" */
    var muteSavedVolumes: String?
        get() = sp.getString(KEY_MUTE_VOLUMES, null)
        set(value) = sp.edit().putString(KEY_MUTE_VOLUMES, value).apply()

    /** 静音前的响铃模式，-1 表示未记录 */
    var muteSavedRinger: Int
        get() = sp.getInt(KEY_MUTE_RINGER, -1)
        set(value) = sp.edit().putInt(KEY_MUTE_RINGER, value).apply()

    /** 静音前的勿扰过滤模式，-1 表示未记录 */
    var muteSavedDnd: Int
        get() = sp.getInt(KEY_MUTE_DND, -1)
        set(value) = sp.edit().putInt(KEY_MUTE_DND, value).apply()

    /** 自动点击点位列表，格式 "x,y|x,y" */
    var clickerPoints: String?
        get() = sp.getString(KEY_CLICKER_POINTS, null)
        set(value) = sp.edit().putString(KEY_CLICKER_POINTS, value).apply()

    /** 自动点击间隔（毫秒） */
    var clickerInterval: Int
        get() = sp.getInt(KEY_CLICKER_INTERVAL, 200)
        set(value) = sp.edit().putInt(KEY_CLICKER_INTERVAL, value).apply()

    /** 按下时长（毫秒） */
    var clickerPress: Int
        get() = sp.getInt(KEY_CLICKER_PRESS, 60)
        set(value) = sp.edit().putInt(KEY_CLICKER_PRESS, value).apply()

    /** 点击次数上限，0 = 无限 */
    var clickerLimit: Int
        get() = sp.getInt(KEY_CLICKER_LIMIT, 0)
        set(value) = sp.edit().putInt(KEY_CLICKER_LIMIT, value).apply()

    /** 随机偏移与间隔抖动 */
    var clickerRandom: Boolean
        get() = sp.getBoolean(KEY_CLICKER_RANDOM, true)
        set(value) = sp.edit().putBoolean(KEY_CLICKER_RANDOM, value).apply()

    /** 智能拦截：识别到真实病毒后自动卸载 */
    var smartBlock: Boolean
        get() = sp.getBoolean(KEY_SMART_BLOCK, false)
        set(value) = sp.edit().putBoolean(KEY_SMART_BLOCK, value).apply()

    /** 防冻结：后台加固，划掉后自动重启 */
    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, false)
        set(value) = sp.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /** 防闪保护：拦截锁机病毒恶意打开手电筒闪烁 */
    var antiFlash: Boolean
        get() = sp.getBoolean(KEY_ANTI_FLASH, false)
        set(value) = sp.edit().putBoolean(KEY_ANTI_FLASH, value).apply()

    fun getFrozenApps(): Set<String> {
        return sp.getStringSet(KEY_FROZEN_APPS, emptySet()) ?: emptySet()
    }

    fun setFrozenApps(apps: Set<String>) {
        sp.edit().putStringSet(KEY_FROZEN_APPS, apps).apply()
    }

    fun addFrozenApp(pkg: String) {
        val set = getFrozenApps().toMutableSet()
        set.add(pkg)
        setFrozenApps(set)
    }

    fun removeFrozenApp(pkg: String) {
        val set = getFrozenApps().toMutableSet()
        set.remove(pkg)
        setFrozenApps(set)
        removeFreezeMode(pkg)
    }

    fun getFreezeMode(pkg: String): FreezeMode {
        val mode = sp.getString("${KEY_FREEZE_MODES}_$pkg", FreezeMode.NONE.name)
        return try {
            FreezeMode.valueOf(mode ?: FreezeMode.NONE.name)
        } catch (e: Exception) {
            FreezeMode.NONE
        }
    }

    fun setFreezeMode(pkg: String, mode: FreezeMode) {
        sp.edit().putString("${KEY_FREEZE_MODES}_$pkg", mode.name).apply()
    }

    fun removeFreezeMode(pkg: String) {
        sp.edit().remove("${KEY_FREEZE_MODES}_$pkg").apply()
    }
}
