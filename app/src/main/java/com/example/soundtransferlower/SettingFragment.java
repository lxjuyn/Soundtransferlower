package com.example.soundtransferlower;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

public class SettingFragment extends Fragment {

    private CheckBox checkBoxLog;
    private CheckBox checkBoxAutoScan;
    private CheckBox checkBoxDiscoverable; // ★ 新增
    private EditText editScanInterval;
    private Button btnApplyScan;

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scrollView = new ScrollView(getActivity());
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);

        SharedPreferences prefs = getActivity().getSharedPreferences("settings", Context.MODE_PRIVATE);

        // ---- 日志开关 ----
        checkBoxLog = new CheckBox(getActivity());
        checkBoxLog.setText("开启日志");
        checkBoxLog.setChecked(prefs.getBoolean("enable_log", false));
        checkBoxLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("enable_log", isChecked).apply();
                LogUtil.setEnableLog(isChecked);
                Toast.makeText(getActivity(), isChecked ? "日志已开启" : "日志已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(checkBoxLog);

        // ---- 分割线 ----
        View divider = new View(getActivity());
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
        divider.setBackgroundColor(0xFFCCCCCC);
        layout.addView(divider);

        // ---- 自动探测开关 ----
        checkBoxAutoScan = new CheckBox(getActivity());
        checkBoxAutoScan.setText("开启自动探测（可能降低文件传输速度）");
        checkBoxAutoScan.setChecked(prefs.getBoolean("auto_scan_enabled", false));
        layout.addView(checkBoxAutoScan);

        // ---- 扫描间隔设置 ----
        LinearLayout intervalLayout = new LinearLayout(getActivity());
        intervalLayout.setOrientation(LinearLayout.HORIZONTAL);
        intervalLayout.setPadding(0, 20, 0, 20);

        android.widget.EditText label = new android.widget.EditText(getActivity());
        label.setText("间隔(秒):");
        label.setEnabled(false);
        label.setInputType(0);
        intervalLayout.addView(label);

        editScanInterval = new EditText(getActivity());
        int currentInterval = prefs.getInt("auto_scan_interval", 60);
        editScanInterval.setText(String.valueOf(currentInterval));
        editScanInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editScanInterval.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        intervalLayout.addView(editScanInterval);

        btnApplyScan = new Button(getActivity());
        btnApplyScan.setText("确认");
        btnApplyScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int interval = Integer.parseInt(editScanInterval.getText().toString());
                    if (interval < 1 || interval > 300) {
                        Toast.makeText(getActivity(), "请输入1-300秒", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putInt("auto_scan_interval", interval).apply();
                    if (getActivity() instanceof MainActivityNew) {
                        ((MainActivityNew) getActivity()).updateAutoScannerSettings();
                    }
                    Toast.makeText(getActivity(), "探测间隔已更新为 " + interval + " 秒", Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(getActivity(), "请输入有效数字", Toast.LENGTH_SHORT).show();
                }
            }
        });
        intervalLayout.addView(btnApplyScan);

        layout.addView(intervalLayout);

        // 自动探测开关监听
        checkBoxAutoScan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("auto_scan_enabled", isChecked).apply();
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).updateAutoScannerSettings();
                }
                Toast.makeText(getActivity(), isChecked ? "自动探测已开启" : "自动探测已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        // ---- ★★★ 新增：始终请求可被发现 ★★★ ----
        CheckBox checkBoxDiscoverable = new CheckBox(getActivity());
        checkBoxDiscoverable.setText("始终请求可被发现（建议开启）");
        checkBoxDiscoverable.setChecked(prefs.getBoolean("request_discoverable", false));
        checkBoxDiscoverable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("request_discoverable", isChecked).apply();
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).updateDiscoverableSettings();
                }
                Toast.makeText(getActivity(), isChecked ? "已开启，应用将定期请求可被发现" : "已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(checkBoxDiscoverable);

        // ---- 所有组件添加完毕 ----
        scrollView.addView(layout);
        return scrollView; // 这里没有任何提前 return
    }
}