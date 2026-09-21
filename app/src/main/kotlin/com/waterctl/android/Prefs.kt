package com.waterctl.android

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局设置与「上次连接设备」的持久化存储（不区分设备，一套设置对所有水控器生效）。
 *
 * Activity 与 Service 共用同一个 SharedPreferences 文件，并且每次读取时都不做缓存，
 * 因此修改开关后会立即生效，不需要重启正在进行的会话。
 */
object Prefs {

    /** 沿用历史版本的文件名，保证升级后设置与「上次设备」不丢失。 */
    const val FILE = "waterctl"

    private const val KEY_LAST_ADDRESS = "last_address"
    private const val KEY_LAST_NAME = "last_name"
    private const val KEY_LOG_PACKETS = "log_packets"

    private fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 上次成功连接的设备（供「一键连接」使用） ----

    fun lastAddress(context: Context): String =
        get(context).getString(KEY_LAST_ADDRESS, "").orEmpty()

    fun lastName(context: Context): String =
        get(context).getString(KEY_LAST_NAME, "").orEmpty()

    fun saveLastDevice(context: Context, address: String, name: String) {
        get(context).edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_NAME, name)
            .apply()
    }

    // ---- 设置项 ----

    /** 是否在日志中显示本机发送的数据包（默认开启）。 */
    fun logPackets(context: Context): Boolean =
        get(context).getBoolean(KEY_LOG_PACKETS, true)

    fun setLogPackets(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_LOG_PACKETS, enabled).apply()
    }
}