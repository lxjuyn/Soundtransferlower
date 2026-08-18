package com.example.soundtransferlower;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.support.v7.widget.SwitchCompat;
import android.app.AlertDialog;

/**
 * 设置页面 Fragment
 */
public class SettingsFragment extends Fragment {

    private static final String PREF_NAME = "app_settings";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_CALL_REMINDER = "call_reminder";
    private static final String KEY_VIBRATION = "vibration";

    private TextView tvDeviceName;
    private TextView tvVersion;
    private TextView tvAuthor;
    private TextView tvOpenSourceUrl;
    private SwitchCompat switchAutoReconnect;
    private SwitchCompat switchCallReminder;
    private SwitchCompat switchVibration;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        tvDeviceName = view.findViewById(R.id.tvDeviceName);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvAuthor = view.findViewById(R.id.tvAuthor);
        tvOpenSourceUrl = view.findViewById(R.id.tvOpenSourceUrl);
        switchAutoReconnect = view.findViewById(R.id.switchAutoReconnect);
        switchCallReminder = view.findViewById(R.id.switchCallReminder);
        switchVibration = view.findViewById(R.id.switchVibration);

        setupBackButton(view);
        setupDeviceName();
        setupVersionInfo();
        setupSwitches();

        return view;
    }

    private void setupBackButton(View view) {
        TextView btnBack = view.findViewById(R.id.btnSettingsBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            }
        });
    }

    private void setupDeviceName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            String name = adapter.getName();
            tvDeviceName.setText(name != null ? name : "未知");
        }
    }

    private void setupVersionInfo() {
        if (getActivity() == null) return;
        try {
            PackageInfo pInfo = getActivity().getPackageManager()
                    .getPackageInfo(getActivity().getPackageName(), 0);
            tvVersion.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            tvVersion.setText("未知版本");
        }
    }

    private void setupSwitches() {
        SharedPreferences prefs = getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        switchAutoReconnect.setChecked(prefs.getBoolean(KEY_AUTO_RECONNECT, true));
        switchCallReminder.setChecked(prefs.getBoolean(KEY_CALL_REMINDER, true));
        switchVibration.setChecked(prefs.getBoolean(KEY_VIBRATION, true));

        switchAutoReconnect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePreference(KEY_AUTO_RECONNECT, isChecked);
            }
        });

        switchCallReminder.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePreference(KEY_CALL_REMINDER, isChecked);
            }
        });

        switchVibration.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePreference(KEY_VIBRATION, isChecked);
            }
        });
    }

    private void savePreference(String key, boolean value) {
        if (getActivity() == null) return;
        SharedPreferences.Editor editor = getActivity()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(key, value);
        editor.apply();
    }
}
