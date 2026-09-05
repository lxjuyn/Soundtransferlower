package com.example.soundtransferlower;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

/**
 * 设置页面 Fragment（MD3 风格）
 */
public class SettingsFragment extends Fragment {

    private static final String PREF_NAME = "app_settings";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_CALL_REMINDER = "call_reminder";
    private static final String KEY_VIBRATION = "vibration";
    private static final String KEY_TALKBACK_TIMEOUT = "talkback_timeout";
    private static final int DEFAULT_TALKBACK_TIMEOUT_SECONDS = 50;
    private static final int[] TIMEOUT_CHOICES_SECONDS = {30, 50, 90};

    private TextView tvDeviceName;
    private TextView tvVersion;
    private TextView tvVersionPill;
    private TextView tvAuthor;
    private TextView tvOpenSourceUrl;
    private TextView tvSourceInfo;
    private TextView tvTimeout;
    private TextView tvConnectionStatus;
    private TextView tvThemeValue;
    private View dotStatus;
    private SwitchCompat switchAutoReconnect;
    private SwitchCompat switchCallReminder;
    private SwitchCompat switchVibration;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        tvDeviceName = view.findViewById(R.id.tvDeviceName);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvVersionPill = view.findViewById(R.id.tvVersionPill);
        tvAuthor = view.findViewById(R.id.tvAuthor);
        tvOpenSourceUrl = view.findViewById(R.id.tvOpenSourceUrl);
        tvSourceInfo = view.findViewById(R.id.tvSourceInfo);
        tvTimeout = view.findViewById(R.id.tvTimeout);
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        tvThemeValue = view.findViewById(R.id.tvThemeValue);
        dotStatus = view.findViewById(R.id.dotStatus);
        switchAutoReconnect = view.findViewById(R.id.switchAutoReconnect);
        switchCallReminder = view.findViewById(R.id.switchCallReminder);
        switchVibration = view.findViewById(R.id.switchVibration);

        setupBackButton(view);
        setupDeviceName(view);
        setupVersionInfo();
        setupAuthor();
        setupTimeoutRow(view);
        setupOpenSourceRow(view);
        setupThemeRow(view);
        setupSwitches();

        // MD3 程序化样式：卡片/芯片/Hero/开关/图标统一按 tag 应用（跟随配色主题）
        Md3Ui.applyTree(view);
        Md3Ui.tintIcon((ImageView) view.findViewById(R.id.btnSettingsBack), R.attr.md3OnSurface);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshConnectionStatus();
        refreshTimeoutText();
    }

    private void setupBackButton(View view) {
        View btnBack = view.findViewById(R.id.btnSettingsBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            }
        });
    }

    private void setupDeviceName(View view) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            String name = PermissionHelper.safeName(getContext(), adapter);
            tvDeviceName.setText(name != null ? name : getString(R.string.settings_unknown));
        }

        TextView btnModifyName = view.findViewById(R.id.btnModifyName);
        if (btnModifyName != null) {
            btnModifyName.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenameDialog();
                }
            });
        }
    }

    private void showRenameDialog() {
        if (getActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.settings_rename_dialog_title);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String currentName = adapter != null ? PermissionHelper.safeName(getActivity(), adapter) : "";
        final EditText input = new EditText(getActivity());
        input.setText(currentName);
        input.setHint(currentName);
        input.setSelectAllOnFocus(true);
        builder.setView(input);
        builder.setPositiveButton(R.string.settings_save, (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && adapter != null) {
                adapter.setName(newName);
                tvDeviceName.setText(newName);
                Toast.makeText(getActivity(), R.string.settings_rename_done, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.settings_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void setupVersionInfo() {
        String versionName = getString(R.string.settings_unknown);
        if (getActivity() != null) {
            try {
                PackageInfo pInfo = getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0);
                versionName = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                // 保持占位文本
            }
        }
        tvVersion.setText(versionName);
        tvVersionPill.setText(versionName);
    }

    private void setupAuthor() {
        tvAuthor.setText(getString(R.string.app_name) + " · 秋元");
    }

    private void setupTimeoutRow(View view) {
        View rowTimeout = view.findViewById(R.id.rowTimeout);
        rowTimeout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimeoutDialog();
            }
        });
        refreshTimeoutText();
    }

    private void refreshTimeoutText() {
        if (getActivity() == null) return;
        int seconds = getTalkbackTimeoutSeconds(getActivity());
        tvTimeout.setText(getString(R.string.settings_seconds_fmt, seconds));
    }

    private void showTimeoutDialog() {
        if (getActivity() == null) return;
        int current = getTalkbackTimeoutSeconds(getActivity());
        String[] labels = new String[TIMEOUT_CHOICES_SECONDS.length];
        int checked = 0;
        for (int i = 0; i < TIMEOUT_CHOICES_SECONDS.length; i++) {
            labels[i] = getString(R.string.settings_seconds_fmt, TIMEOUT_CHOICES_SECONDS[i]);
            if (TIMEOUT_CHOICES_SECONDS[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.settings_timeout_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putInt(KEY_TALKBACK_TIMEOUT, TIMEOUT_CHOICES_SECONDS[which])
                            .apply();
                    refreshTimeoutText();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /** TalkbackFragment 等业务方读取对讲无活动断开秒数 */
    public static int getTalkbackTimeoutSeconds(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TALKBACK_TIMEOUT, DEFAULT_TALKBACK_TIMEOUT_SECONDS);
    }

    // ==================== 主题配色 ====================

    private static final String[] PALETTE_IDS = {
            PaletteHelper.PALETTE_INDIGO, PaletteHelper.PALETTE_GREEN_WHITE,
            PaletteHelper.PALETTE_TEAL, PaletteHelper.PALETTE_ROSE,
            PaletteHelper.PALETTE_AMBER, PaletteHelper.PALETTE_MONO
    };
    private static final int[] PALETTE_LABEL_RES = {
            R.string.palette_indigo, R.string.palette_green_white,
            R.string.palette_teal, R.string.palette_rose,
            R.string.palette_amber, R.string.palette_mono
    };

    private void setupThemeRow(View view) {
        View row = view.findViewById(R.id.rowTheme);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });
        refreshThemeValue();
    }

    private void refreshThemeValue() {
        if (getActivity() == null) return;
        tvThemeValue.setText(paletteLabel(PaletteHelper.getPalette(getActivity())));
    }

    private String paletteLabel(String paletteId) {
        for (int i = 0; i < PALETTE_IDS.length; i++) {
            if (PALETTE_IDS[i].equals(paletteId)) return getString(PALETTE_LABEL_RES[i]);
        }
        return getString(PALETTE_LABEL_RES[0]);
    }

    private void showThemeDialog() {
        if (getActivity() == null) return;
        String[] labels = new String[PALETTE_IDS.length];
        int checked = 0;
        String current = PaletteHelper.getPalette(getActivity());
        for (int i = 0; i < PALETTE_IDS.length; i++) {
            labels[i] = getString(PALETTE_LABEL_RES[i]);
            if (PALETTE_IDS[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    PaletteHelper.savePalette(getActivity(), PALETTE_IDS[which]);
                    dialog.dismiss();
                    // 主题在 Activity 创建时应用，切换后重建整个界面
                    getActivity().recreate();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private void setupOpenSourceRow(View view) {
        View row = view.findViewById(R.id.rowOpenSource);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/lxjuyn/Soundtransferlower"));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(getActivity(), R.string.settings_unknown, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void setupSwitches() {
        SharedPreferences prefs = getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        switchAutoReconnect.setChecked(prefs.getBoolean(KEY_AUTO_RECONNECT, true));
        switchCallReminder.setChecked(prefs.getBoolean(KEY_CALL_REMINDER, true));
        switchVibration.setChecked(prefs.getBoolean(KEY_VIBRATION, true));

        switchAutoReconnect.setOnCheckedChangeListener(new CompoundButtonListener(KEY_AUTO_RECONNECT));
        switchCallReminder.setOnCheckedChangeListener(new CompoundButtonListener(KEY_CALL_REMINDER));
        switchVibration.setOnCheckedChangeListener(new CompoundButtonListener(KEY_VIBRATION));
    }

    private void refreshConnectionStatus() {
        if (getActivity() instanceof MainActivityNew) {
            MainActivityNew main = (MainActivityNew) getActivity();
            if (main.isBluetoothConnected()) {
                Md3Ui.applyDot(dotStatus, true);
                String name = main.getConnectedDeviceName();
                tvConnectionStatus.setText(name != null
                        ? getString(R.string.settings_status_connected_fmt, name)
                        : getString(R.string.settings_status_connected_fmt, ""));
            } else {
                Md3Ui.applyDot(dotStatus, false);
                tvConnectionStatus.setText(R.string.settings_status_disconnected);
            }
        }
    }

    /** 开关统一保存监听 */
    private class CompoundButtonListener implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final String key;

        CompoundButtonListener(String key) {
            this.key = key;
        }

        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            savePreference(key, isChecked);
        }
    }

    private void savePreference(String key, boolean value) {
        if (getActivity() == null) return;
        SharedPreferences.Editor editor = getActivity()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(key, value);
        editor.apply();
    }
}
