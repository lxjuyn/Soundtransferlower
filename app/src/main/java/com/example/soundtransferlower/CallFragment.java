package com.example.soundtransferlower;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

public class CallFragment extends Fragment {
    private TextView tvCallerName, tvDuration, tvCallerAvatar;
    private Button btnHangUp, btnSpeaker;
    private MainActivityNew mainActivity;
    private boolean isSpeakerOn = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_call, container, false);
        tvCallerName = view.findViewById(R.id.tvCallerName);
        tvDuration = view.findViewById(R.id.tvDuration);
        tvCallerAvatar = view.findViewById(R.id.tvCallerAvatar);
        btnHangUp = view.findViewById(R.id.btnHangUp);
        btnSpeaker = view.findViewById(R.id.btnSpeaker);

        mainActivity = (MainActivityNew) getActivity();
        if (getArguments() != null) {
            String name = getArguments().getString("TARGET_NAME");
            if (name != null) {
                tvCallerName.setText("正在与 " + name + " 通话");
                if (tvCallerAvatar != null && !name.isEmpty()) {
                    tvCallerAvatar.setText(String.valueOf(name.charAt(0)).toUpperCase(Locale.getDefault()));
                }
                // 呼吸环：等待回调时头像轻微脉动
                if (tvCallerAvatar != null) {
                    android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofFloat(1f, 1.06f);
                    pulse.setDuration(800);
                    pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    pulse.addUpdateListener(a -> {
                        float v = (Float) a.getAnimatedValue();
                        tvCallerAvatar.setScaleX(v);
                        tvCallerAvatar.setScaleY(v);
                    });
                    pulse.start();
                    tvCallerAvatar.setTag(pulse);
                }
            }
        }

        btnHangUp.setOnClickListener(v -> {
            if (mainActivity != null) {
                mainActivity.endCall();
            }
        });

        btnSpeaker.setOnClickListener(v -> toggleSpeaker());

        updateSpeakerButton();
        Md3Ui.applyTree(view); // 处理 md3-btn-danger 等 tag
        return view;
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(isSpeakerOn);
        }
        updateSpeakerButton();
    }

    private void updateSpeakerButton() {
        if (btnSpeaker != null) {
            btnSpeaker.setText(isSpeakerOn ? "听筒" : "免提");
        }
    }

    public void updateDuration(long elapsedMillis) {
        long seconds = elapsedMillis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        if (tvDuration != null) {
            tvDuration.setText(time);
        }
    }
}