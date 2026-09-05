package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioRecorderPlayer {
    private static final String TAG = "AudioRecorderPlayer";
    // 优化：提升采样率到16kHz，支持宽带语音（HD Voice质量）
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // 每帧采样数（20ms @ 16kHz），对应 PCM 字节数 640
    private static final int FRAME_SAMPLES = 320;
    private static final int PCM_BYTES_PER_FRAME = FRAME_SAMPLES * 2; // 640
    private static final int MAX_OPUS_BYTES = 1024;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;

    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    // Opus 编解码器
    private OpusEncoder opusEncoder;
    private OpusDecoder opusDecoder;

    // 播放队列（容量 15 帧，约 300ms 缓冲 @ 16kHz）
    private final BlockingQueue<byte[]> pcmQueue = new LinkedBlockingQueue<>(15);
    private volatile boolean playThreadRunning = false;
    private Thread playThread;

    public interface AudioDataSender {
        void sendAudioData(byte[] data);
    }

    private AudioDataSender audioDataSender;

    public AudioRecorderPlayer(Context context) {
        initAudio();
        initOpusCodecs();
        startPlaybackThread();
    }

    public void setAudioDataSender(AudioDataSender sender) {
        this.audioDataSender = sender;
    }

    @SuppressLint("MissingPermission")
    private void initAudio() {
        try {
            int minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            int minPlayBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
            // 播放缓冲区设为最小值的 2 倍（防止 underrun），但不超过 4 帧大小
            int playBufferSize = Math.max(minPlayBuf, PCM_BYTES_PER_FRAME * 2);
            playBufferSize = Math.min(playBufferSize, PCM_BYTES_PER_FRAME * 4);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minRecordBuf * 2);

            audioTrack = new AudioTrack(
                    android.media.AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    playBufferSize,
                    AudioTrack.MODE_STREAM);

            LogUtil.d(TAG, "音频初始化成功，播放缓冲区 = " + playBufferSize + " 字节");
        } catch (Exception e) {
            LogUtil.e(TAG, "音频初始化失败: " + e.getMessage());
        }
    }

    private void initOpusCodecs() {
        try {
            opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            // 优化：提升比特率到24kbps，支持宽带语音
            opusEncoder.setBitrate(24000); // 24 kbps for wideband speech
            // 优化：启用FEC（前向纠错），提升抗丢包能力
            opusEncoder.setUseInbandFEC(true);
            // 优化：设置复杂度为5，平衡CPU使用和编码质量
            opusEncoder.setComplexity(5);
            opusDecoder = new OpusDecoder(SAMPLE_RATE, 1);
            LogUtil.d(TAG, "Opus 编解码器初始化成功 (16kHz, 24kbps, FEC enabled)");
        } catch (OpusException e) {
            LogUtil.e(TAG, "Opus 初始化失败: " + e.getMessage());
        }
    }

    // 启动独立播放线程
    private void startPlaybackThread() {
        playThreadRunning = true;
        playThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            int consecutiveErrors = 0;
            while (playThreadRunning) {
                try {
                    byte[] pcmData = pcmQueue.take(); // 阻塞取数据
                    writePcmToAudioTrack(pcmData);
                    consecutiveErrors = 0; // 成功则重置错误计数
                } catch (InterruptedException e) {
                    // 线程被中断，退出循环
                    break;
                } catch (Exception e) {
                    LogUtil.e(TAG, "播放线程异常: " + e.getMessage());
                    consecutiveErrors++;
                    // 优化：连续错误时添加短暂延迟，避免CPU空转
                    if (consecutiveErrors >= 5) {
                        LogUtil.w(TAG, "连续播放错误 " + consecutiveErrors + " 次，暂停500ms");
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        consecutiveErrors = 0;
                    }
                }
            }
            LogUtil.d(TAG, "播放线程退出");
        });
        playThread.start();
    }

    // 向 AudioTrack 写入一帧 PCM 数据
    private void writePcmToAudioTrack(byte[] pcmData) {
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            return;
        }
        // 确保 AudioTrack 处于播放状态
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.play();
                isPlaying = true;
                LogUtil.d(TAG, "音频播放已启动");
            } catch (IllegalStateException e) {
                LogUtil.e(TAG, "启动播放失败: " + e.getMessage());
                return;
            }
        }

        // 直接写入整帧（640 字节很小，不会阻塞太久）
        int written = audioTrack.write(pcmData, 0, pcmData.length);
        if (written != pcmData.length) {
            LogUtil.w(TAG, "写入不完全，期望 " + pcmData.length + " 实际 " + written);
        }
    }

    public void startRecording() {
        if (audioRecord == null || isRecording || opusEncoder == null) return;

        LogUtil.d(TAG, "开始录音");
        new Thread(() -> {
            try {
                audioRecord.startRecording();
                isRecording = true;
                LogUtil.d(TAG, "录音已启动");

                byte[] pcmBuffer = new byte[PCM_BYTES_PER_FRAME];
                short[] pcmShorts = new short[FRAME_SAMPLES];
                byte[] encodedBuffer = new byte[MAX_OPUS_BYTES];

                byte[] pendingFrame = null;
                while (isRecording) {
                    int totalRead = 0;
                    while (totalRead < PCM_BYTES_PER_FRAME) {
                        int bytesRead = audioRecord.read(pcmBuffer, totalRead, PCM_BYTES_PER_FRAME - totalRead);
                        if (bytesRead <= 0) break;
                        totalRead += bytesRead;
                    }

                    if (totalRead == PCM_BYTES_PER_FRAME && audioDataSender != null) {
                        ByteBuffer.wrap(pcmBuffer)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(pcmShorts);

                        int encodedBytes = opusEncoder.encode(
                                pcmShorts, 0, FRAME_SAMPLES,
                                encodedBuffer, 0, encodedBuffer.length
                        );

                        if (encodedBytes > 0) {
                            // —— 2 帧合批发送：write 系统调用减半（每帧 20ms → 每 40ms 一次），
                            //    对延迟影响 +20ms，对讲场景可接受；同时消灭每帧日志格式化开销 ——
                            if (pendingFrame == null) {
                                pendingFrame = new byte[encodedBytes];
                                System.arraycopy(encodedBuffer, 0, pendingFrame, 0, encodedBytes);
                            } else {
                                byte[] batch = new byte[pendingFrame.length + encodedBytes];
                                System.arraycopy(pendingFrame, 0, batch, 0, pendingFrame.length);
                                System.arraycopy(encodedBuffer, 0, batch, pendingFrame.length, encodedBytes);
                                pendingFrame = null;
                                audioDataSender.sendAudioData(batch);
                            }
                        }
                    }
                }
                if (pendingFrame != null && audioDataSender != null) {
                    audioDataSender.sendAudioData(pendingFrame);
                    pendingFrame = null;
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "录制错误: " + e.getMessage());
            } finally {
                stopRecordingInternal();
                LogUtil.d(TAG, "录音线程结束");
            }
        }).start();
    }

    public void stopRecording() {
        if (isRecording) {
            isRecording = false;
        }
    }

    private void stopRecordingInternal() {
        if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            LogUtil.d(TAG, "停止录音");
            audioRecord.stop();
            LogUtil.d(TAG, "录音已停止");
        }
    }

    // 播放接口：解码后将 PCM 入队，由独立线程写入 AudioTrack
    public void playAudio(byte[] encodedData, int length) {
        if (audioTrack == null || length <= 0 || opusDecoder == null) return;

        if (playbackExecutor.isShutdown()) return; // release 后仍有在途回调时防 RejectedExecutionException
        try {
            playbackExecutor.execute(() -> {
            try {
                // 解码输出 short[]
                short[] pcmShorts = new short[FRAME_SAMPLES];
                int decodedSamples = opusDecoder.decode(
                        encodedData, 0, length,
                        pcmShorts, 0, FRAME_SAMPLES, false
                );

                if (decodedSamples != FRAME_SAMPLES) {
                    LogUtil.w(TAG, "解码样本数异常: " + decodedSamples);
                    return;
                }

                // 将 short[] 转为 byte[]（小端序）
                byte[] pcmBytes = new byte[PCM_BYTES_PER_FRAME];
                ByteBuffer.wrap(pcmBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(pcmShorts);

                LogUtil.d(TAG, String.format("解码: Opus=%d 字节 -> PCM=%d 字节", length, PCM_BYTES_PER_FRAME));

                // 入队，若队列满则丢弃最旧帧（防止内存溢出和延迟累积）
                if (!pcmQueue.offer(pcmBytes)) {
                    byte[] dropped = pcmQueue.poll();          // 丢弃队首
                    if (dropped != null) {
                        LogUtil.w(TAG, "播放队列满，丢弃一帧");
                    }
                    pcmQueue.offer(pcmBytes); // 再入队
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "解码错误: " + e.getMessage());
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // release 与在途回调竞争：忽略
        }
    }

    public void release() {
        stopRecording();
        isPlaying = false;

        // 停止播放线程
        playThreadRunning = false;
        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(100);
            } catch (InterruptedException ignored) {
            }
            playThread = null;
        }
        pcmQueue.clear();

        // 关闭解码线程池
        playbackExecutor.shutdownNow();

        if (audioRecord != null) {
            stopRecordingInternal();
            audioRecord.release();
            audioRecord = null;
        }

        if (audioTrack != null) {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.stop();
            }
            audioTrack.release();
            audioTrack = null;
        }

        opusEncoder = null;
        opusDecoder = null;

        LogUtil.d(TAG, "音频资源已释放");
    }
}