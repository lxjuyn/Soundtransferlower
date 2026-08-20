package com.example.soundtransferlower;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

public class SettingFragment extends Fragment {

    private CheckBox checkBoxLog;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 创建 ScrollView
        ScrollView scrollView = new ScrollView(getActivity());
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);

        checkBoxLog = new CheckBox(getActivity());
        checkBoxLog.setText("开启日志");
        // 从 SharedPreferences 读取初始状态
        SharedPreferences prefs = getActivity().getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean isChecked = prefs.getBoolean("enable_log", false);
        checkBoxLog.setChecked(isChecked);

        checkBoxLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // 保存状态
                SharedPreferences prefs = getActivity().getSharedPreferences("settings", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("enable_log", isChecked).apply();
                // 更新 LogUtil 开关
                LogUtil.setEnableLog(isChecked);
                Toast.makeText(getActivity(), isChecked ? "日志已开启" : "日志已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        layout.addView(checkBoxLog);
        // 未来可在此添加更多设置选项

        scrollView.addView(layout);
        return scrollView;
    }
}