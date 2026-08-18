package com.example.soundtransferlower;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceRecorder {
    private static final String TAG = "VoiceRecorder";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SAMPLES = 320; // 40ms
    private static final int PCM_BYTES_PER_FRAME = FRAME_SAMPLES * 2;
    private static final int MAX_OPUS_BYTES = 1024;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private OpusEncoder encoder;
    private OpusDecoder decoder;
    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;
    private File outputFile;
    private FileOutputStream fos;
    private Handler handler;
    private OnVoiceRecordListener listener;
    private ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    // ★★★ 播放监听器 ★★★
    public interface OnPlayListener {
        void onPlayStart();
        void onPlayFinish();
    }

    public interface OnVoiceRecordListener {
        void onRecordStart();
        void onRecordProgress(int durationSeconds);
        void onRecordFinish(File voiceFile, int durationSeconds);
        void onRecordError(String error);
    }

    public VoiceRecorder(OnVoiceRecordListener listener) {
        this.listener = listener != null ? listener : new OnVoiceRecordListener() {
            @Override public void onRecordStart() {}
            @Override public void onRecordProgress(int durationSeconds) {}
            @Override public void onRecordFinish(File voiceFile, int durationSeconds) {}
            @Override public void onRecordError(String error) {}
        };
        this.handler = new Handler(Looper.getMainLooper());
        initCodecs();
        initAudioTrack();
    }

    private void initCodecs() {
        try {
            encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(16000);
            encoder.setComplexity(5);
            decoder = new OpusDecoder(SAMPLE_RATE, 1);
            Log.d(TAG, "Opus 编解码器初始化成功");
        } catch (OpusException e) {
            Log.e(TAG, "Opus初始化失败", e);
        }
    }

    private void initAudioTrack() {
        int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
        if (bufferSize < PCM_BYTES_PER_FRAME * 2) {
            bufferSize = PCM_BYTES_PER_FRAME * 2;
        }
        audioTrack = new AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 初始化失败");
            audioTrack = null;
        } else {
            Log.d(TAG, "AudioTrack 初始化成功");
        }
    }

    // ==================== 录音部分 ====================
    public void startRecording(File file) {
        if (isRecording || encoder == null) {
            Log.e(TAG, "录音已在进行或编码器未初始化");
            listener.onRecordError("编码器未就绪");
            return;
        }
        this.outputFile = file;
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "无法创建录音目录");
                listener.onRecordError("无法创建录音目录");
                return;
            }
        }
        try {
            fos = new FileOutputStream(file);
        } catch (IOException e) {
            Log.e(TAG, "打开文件输出流失败", e);
            listener.onRecordError("文件创建失败: " + e.getMessage());
            return;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize < PCM_BYTES_PER_FRAME * 2) {
                bufferSize = PCM_BYTES_PER_FRAME * 2;
            }
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            audioRecord.startRecording();
            isRecording = true;
            handler.post(() -> listener.onRecordStart());
            new Thread(new RecordRunnable()).start();
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
                fos = null;
            }
            listener.onRecordError("录音启动失败: " + e.getMessage());
        }
    }

    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private class RecordRunnable implements Runnable {
        private int totalSamples = 0;
        private final short[] pcmShorts = new short[FRAME_SAMPLES];
        private final byte[] pcmBytes = new byte[PCM_BYTES_PER_FRAME];
        private final byte[] opusBuffer = new byte[MAX_OPUS_BYTES];

        @Override
        public void run() {
            while (isRecording) {
                // ★★★ 获取局部引用并检查 ★★★
                FileOutputStream localFos;
                synchronized (VoiceRecorder.this) {
                    localFos = fos;
                    if (localFos == null) {
                        Log.w(TAG, "fos 为空，等待重新打开...");
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }
                }

                if (audioRecord == null) {
                    Log.e(TAG, "AudioRecord 为空，停止录音");
                    isRecording = false;
                    handler.post(() -> listener.onRecordError("录音设备异常"));
                    break;
                }

                int totalRead = 0;
                while (totalRead < PCM_BYTES_PER_FRAME) {
                    int bytesRead = audioRecord.read(pcmBytes, totalRead, PCM_BYTES_PER_FRAME - totalRead);
                    if (bytesRead <= 0) break;
                    totalRead += bytesRead;
                }

                if (totalRead == PCM_BYTES_PER_FRAME) {
                    ByteBuffer.wrap(pcmBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(pcmShorts);

                    try {
                        int encodedBytes = encoder.encode(
                                pcmShorts, 0, FRAME_SAMPLES,
                                opusBuffer, 0, opusBuffer.length
                        );

                        if (encodedBytes > 0) {
                            localFos.write((encodedBytes >> 8) & 0xFF);
                            localFos.write(encodedBytes & 0xFF);
                            localFos.write(opusBuffer, 0, encodedBytes);
                            totalSamples += FRAME_SAMPLES;
                            int duration = totalSamples / SAMPLE_RATE;
                            if (duration > 0 && duration % 1 == 0) {
                                handler.post(() -> listener.onRecordProgress(duration));
                            }
                        }
                    } catch (IOException | OpusException e) {
                        Log.e(TAG, "编码失败", e);
                        isRecording = false;
                        handler.post(() -> listener.onRecordError("编码失败: " + e.getMessage()));
                        break;
                    }
                }
            }
            // 录音结束，关闭文件流
            synchronized (VoiceRecorder.this) {
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignored) {}
                    fos = null;
                }
            }
            int finalDuration = totalSamples / SAMPLE_RATE;
            handler.post(() -> listener.onRecordFinish(outputFile, finalDuration));
        }
    }

    // ==================== 播放部分（增加控制和回调） ====================
    public void playVoice(byte[] opusData, int length, int durationSeconds, OnPlayListener playListener) {
        if (decoder == null || audioTrack == null) {
            Log.e(TAG, "解码器或 AudioTrack 未初始化");
            if (playListener != null) playListener.onPlayFinish();
            return;
        }

        // 如果已在播放，先停止
        stopPlayback();

        isPlaying = true;
        if (playListener != null) {
            handler.post(playListener::onPlayStart);
        }

        playbackExecutor.execute(() -> {
            try {
                int offset = 0;
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                    audioTrack.flush();
                }
                while (isPlaying && offset + 2 <= length) {
                    int frameLen = ((opusData[offset] & 0xFF) << 8) | (opusData[offset + 1] & 0xFF);
                    offset += 2;
                    if (offset + frameLen > length) {
                        Log.w(TAG, "帧长度超出数据范围");
                        break;
                    }
                    short[] pcmShorts = new short[FRAME_SAMPLES];
                    int decodedSamples = decoder.decode(
                            opusData, offset, frameLen,
                            pcmShorts, 0, FRAME_SAMPLES,
                            false
                    );
                    if (decodedSamples != FRAME_SAMPLES) {
                        Log.w(TAG, "解码样本数异常: " + decodedSamples + "，期望: " + FRAME_SAMPLES);
                        break;
                    }
                    byte[] pcmBytes = new byte[PCM_BYTES_PER_FRAME];
                    ByteBuffer.wrap(pcmBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .put(pcmShorts);
                    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                    audioTrack.write(pcmBytes, 0, PCM_BYTES_PER_FRAME);
                    offset += frameLen;
                }
                Log.d(TAG, isPlaying ? "播放完成" : "播放被中断");
            } catch (OpusException e) {
                Log.e(TAG, "播放失败", e);
                handler.post(() -> {
                    if (playListener != null) playListener.onPlayFinish();
                    listener.onRecordError("播放失败: " + e.getMessage());
                });
                return;
            } catch (IllegalStateException e) {
                Log.e(TAG, "播放状态错误", e);
                handler.post(() -> {
                    if (playListener != null) playListener.onPlayFinish();
                    listener.onRecordError("播放状态错误: " + e.getMessage());
                });
                return;
            }
            // 播放结束（正常或中断）
            isPlaying = false;
            handler.post(() -> {
                if (playListener != null) playListener.onPlayFinish();
            });
        });
    }

    public void stopPlayback() {
        isPlaying = false;
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void release() {
        stopRecording();
        stopPlayback();
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
        playbackExecutor.shutdownNow();
        encoder = null;
        decoder = null;
        if (fos != null) {
            try { fos.close(); } catch (IOException ignored) {}
            fos = null;
        }
    }
}