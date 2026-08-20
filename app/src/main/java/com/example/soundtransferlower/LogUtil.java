package com.example.soundtransferlower;

import android.util.Log;

/**
 * 日志工具类，可根据开关控制是否输出日志
 */
public class LogUtil {
    private static boolean sEnableLog = false;

    public static void setEnableLog(boolean enable) {
        sEnableLog = enable;
    }

    public static boolean isLogEnabled() {
        return sEnableLog;
    }

    public static void d(String tag, String msg) {
        if (sEnableLog) {
            Log.d(tag, msg);  // ★ 使用 Log.d，而不是 LogUtil.d
        }
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (sEnableLog) {
            Log.d(tag, msg, tr);
        }
    }

    public static void i(String tag, String msg) {
        if (sEnableLog) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (sEnableLog) {
            Log.w(tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        if (sEnableLog) {
            Log.e(tag, msg);
        }
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (sEnableLog) {
            Log.e(tag, msg, tr);
        }
    }
}