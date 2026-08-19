package com.example.soundtransferlower;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时权限辅助类
 * 兼容 Android 6.0 (API 23) 以下版本
 */
public class PermissionHelper {
    private static final String TAG = "PermissionHelper";

    /**
     * 检查单个权限是否已授予（兼容方法）
     * @param context 上下文
     * @param permission 权限名称
     * @return true 表示已授权或 Android 版本低于 6.0
     */
    public static boolean checkSelfPermission(Context context, String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0 以下，安装时自动授予所有权限
            return true;
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 请求多个权限
     * @param activity 当前 Activity
     * @param permissions 需要请求的权限数组
     * @param requestCode 请求码，用于 onActivityResult 回调
     */
    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0 以下，不需要运行时权限请求
            return;
        }
        ActivityCompat.requestPermissions(activity, permissions, requestCode);
    }

    /**
     * 判断是否已拥有某个权限
     * @param context 上下文
     * @param permission 权限名称
     * @return true 表示已授权
     */
    public static boolean hasPermission(Context context, String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 检查多个权限，返回未授权的权限列表
     * @param context 上下文
     * @param permissions 需要检查的权限数组
     * @return 未授权的权限列表，如果全部已授权则返回空列表
     */
    public static List<String> getDeniedPermissions(Context context, String[] permissions) {
        List<String> deniedPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return deniedPermissions;
        }
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                deniedPermissions.add(permission);
            }
        }
        return deniedPermissions;
    }

    /**
     * 检查并请求多个权限，返回是否所有权限都已授予
     * @param activity 当前 Activity
     * @param permissions 需要检查的权限数组
     * @param requestCode 请求码
     * @return true 表示所有权限都已授予，false 表示需要请求权限
     */
    public static boolean checkAndRequestPermissions(Activity activity, String[] permissions, int requestCode) {
        List<String> deniedPermissions = getDeniedPermissions(activity, permissions);
        if (deniedPermissions.isEmpty()) {
            return true;
        }
        requestPermissions(activity, deniedPermissions.toArray(new String[0]), requestCode);
        return false;
    }

    /**
     * 判断权限请求结果是否被永久拒绝
     * @param activity 当前 Activity
     * @param permission 权限名称
     * @return true 表示被永久拒绝（需要引导用户去设置页面）
     */
    public static boolean isPermissionPermanentlyDenied(Activity activity, String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }
}
