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
import java.util.concurrent.TimeUnit;

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
    private volatile boolean isReleased = false; // ★★★ 释放标志 ★★★
    private File outputFile;
    private FileOutputStream fos;
    private Handler handler;
    private OnVoiceRecordListener listener;
    private ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    public interface OnVoiceRecordListener {
        void onRecordStart();
        void onRecordProgress(int durationSeconds);
        void onRecordFinish(File voiceFile, int durationSeconds);
        void onRecordError(String error);
    }

    public VoiceRecorder(OnVoiceRecordListener listener) {
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
        this.isReleased = false;
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
                android.media.AudioManager.STREAM_VOICE_CALL,
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

    public void startRecording(File file) {
        if (isRecording || encoder == null || isReleased) {
            Log.e(TAG, "录音已在进行或编码器未初始化或已释放");
            if (listener != null) listener.onRecordError("编码器未就绪或已释放");
            return;
        }
        this.outputFile = file;
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "无法创建录音目录");
                if (listener != null) listener.onRecordError("无法创建录音目录");
                return;
            }
        }
        try {
            fos = new FileOutputStream(file);
        } catch (IOException e) {
            Log.e(TAG, "打开文件输出流失败", e);
            if (listener != null) listener.onRecordError("文件创建失败: " + e.getMessage());
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
            handler.post(() -> {
                if (listener != null) listener.onRecordStart();
            });
            new Thread(new RecordRunnable()).start();
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
                fos = null;
            }
            if (listener != null) listener.onRecordError("录音启动失败: " + e.getMessage());
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
            while (isRecording && !isReleased) {
                FileOutputStream localFos = fos;
                if (localFos == null) {
                    Log.e(TAG, "文件流为空，停止录音");
                    isRecording = false;
                    handler.post(() -> listener.onRecordError("文件流无效"));
                    break;
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
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭文件流失败", e);
                }
                fos = null;
            }
            int finalDuration = totalSamples / SAMPLE_RATE;
            handler.post(() -> listener.onRecordFinish(outputFile, finalDuration));
        }
    }

    // ★★★ 播放语音（增加释放检测）★★★
    public void playVoice(byte[] opusData, int length, int durationSeconds) {
        if (isReleased || decoder == null || audioTrack == null) {
            Log.e(TAG, "解码器或 AudioTrack 未初始化或已释放");
            return;
        }

        playbackExecutor.execute(() -> {
            if (isReleased) {
                Log.w(TAG, "播放线程已释放，跳过");
                return;
            }
            try {
                int offset = 0;
                while (offset + 2 <= length && !isReleased) {
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

                    // ★★★ 检查 AudioTrack 状态 ★★★
                    if (isReleased || audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        Log.w(TAG, "AudioTrack 不可用，停止播放");
                        break;
                    }
                    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                    audioTrack.write(pcmBytes, 0, PCM_BYTES_PER_FRAME);
                    offset += frameLen;
                }
                Log.d(TAG, "播放完成，长度: " + durationSeconds + "秒");
            } catch (OpusException e) {
                Log.e(TAG, "播放失败", e);
                handler.post(() -> {
                    if (listener != null) listener.onRecordError("播放失败: " + e.getMessage());
                });
            }
        });
    }

    public void release() {
        if (isReleased) return;
        isReleased = true;
        stopRecording();

        // 停止并释放 AudioTrack
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 AudioTrack 失败", e);
            }
            audioTrack = null;
        }

        // 关闭播放线程池
        if (playbackExecutor != null) {
            playbackExecutor.shutdownNow();
            try {
                if (!playbackExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "播放线程池未完全终止");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "等待播放线程池终止被中断");
            }
        }

        encoder = null;
        decoder = null;

        if (fos != null) {
            try { fos.close(); } catch (IOException ignored) {}
            fos = null;
        }

        Log.d(TAG, "VoiceRecorder 已释放");
    }
}