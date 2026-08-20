package com.example.soundtransferlower;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.widget.Toast;

public class BluetoothHelper {

    /**
     * 获取当前蓝牙名称
     */
    public static String getBluetoothName(BluetoothAdapter adapter) {
        if (adapter == null) return "";
        return adapter.getName();
    }

    /**
     * 设置蓝牙名称
     */
    public static void setBluetoothName(BluetoothAdapter adapter, String name, Context context) {
        if (adapter == null) return;
        if (name == null || name.isEmpty()) return;
        boolean success = adapter.setName(name);
        if (success) {
            Toast.makeText(context, "蓝牙名称已修改为: " + name, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "修改失败，请检查权限", Toast.LENGTH_SHORT).show();
        }
    }
}