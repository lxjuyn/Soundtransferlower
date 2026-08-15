package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioRecorderPlayer {
    private static final String TAG = "AudioRecorderPlayer";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;

    // 使用单线程执行器确保顺序播放
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    public interface AudioDataSender {
        void sendAudioData(byte[] data);
    }

    private AudioDataSender audioDataSender;

    public AudioRecorderPlayer(Context context) {
        initAudio();
    }

    public void setAudioDataSender(AudioDataSender sender) {
        this.audioDataSender = sender;
    }

    @SuppressLint("MissingPermission")
    private void initAudio() {
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE * 2);

            audioTrack = new AudioTrack(
                    android.media.AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    BUFFER_SIZE * 2,
                    AudioTrack.MODE_STREAM);

            Log.d(TAG, "音频录制和播放初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "音频初始化失败: " + e.getMessage());
        }
    }

    public void startRecording() {
        if (audioRecord == null || isRecording) return;

        Log.d(TAG, "开始录音");
        new Thread(() -> {
            try {
                audioRecord.startRecording();
                isRecording = true;
                Log.d(TAG, "录音已启动");
                // 使用固定大小的缓冲区
                int fixedBufferSize = 640; // 固定为640字节
                byte[] pcmBuffer = new byte[fixedBufferSize];
                byte[] encodedBuffer = new byte[fixedBufferSize / 2]; // μ-law编码后数据减半

                while (isRecording) {
                    int totalRead = 0;
                    // 循环读取直到填满缓冲区
                    while (totalRead < fixedBufferSize) {
                        int bytesRead = audioRecord.read(pcmBuffer, totalRead, fixedBufferSize - totalRead);
                        if (bytesRead <= 0) break;
                        totalRead += bytesRead;
                    }

                    if (totalRead > 0 && audioDataSender != null) {
                        // 只发送完整的数据包
                        if (totalRead == fixedBufferSize) {
                            // 将PCM数据编码为μ-law
                            int encodedSize = encodeMuLaw(pcmBuffer, encodedBuffer, totalRead);
                            Log.d(TAG, "录制到 " + totalRead + " 字节PCM数据，编码为 " + encodedSize + " 字节μ-law数据");
                            audioDataSender.sendAudioData(encodedBuffer);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "录制错误: " + e.getMessage());
            } finally {
                stopRecordingInternal();
                Log.d(TAG, "录音线程结束");
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
            Log.d(TAG, "停止录音");
            audioRecord.stop();
            Log.d(TAG, "录音已停止");
        }
    }

    public void playAudio(byte[] encodedData, int length) {
        if (audioTrack == null || length <= 0) return;

        playbackExecutor.execute(() -> {
            try {
                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack未初始化");
                    return;
                }

                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.play();
                    isPlaying = true;
                    Log.d(TAG, "音频播放已启动");
                }

                // 解码μ-law数据为PCM
                byte[] pcmData = new byte[length * 2]; // μ-law解码后数据翻倍
                int decodedSize = decodeMuLaw(encodedData, pcmData, length);

                Log.d(TAG, "接收到 " + length + " 字节μ-law数据，解码为 " + decodedSize + " 字节PCM数据");

                // 优化缓冲区大小计算
                int bufferSize = calculateOptimalBufferSize();
                Log.d(TAG, "优化后的缓冲区大小: " + bufferSize);

                int written = 0;
                while (written < decodedSize) {
                    int bytesToWrite = Math.min(bufferSize, decodedSize - written);
                    int result = audioTrack.write(pcmData, written, bytesToWrite);

                    if (result > 0) {
                        written += result;
                    } else {
                        Log.e(TAG, "AudioTrack写入错误: " + result);
                        break;
                    }
                }

                Log.d(TAG, "成功播放音频数据，长度: " + decodedSize);
            } catch (Exception e) {
                Log.e(TAG, "播放错误: " + e.getMessage());
            }
        });
    }

    /**
     * 计算优化的缓冲区大小
     */
    private int calculateOptimalBufferSize() {
        int bufferSize;

        // 优先使用AudioTrack的推荐缓冲区大小
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0+ 使用 getBufferSizeInFrames()
            bufferSize = audioTrack.getBufferSizeInFrames() * 2; // 每帧2字节（16位）
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Android 5.0+ 使用 getBufferSizeInBytes()
            try {
                bufferSize = (Integer) AudioTrack.class.getMethod("getBufferSizeInBytes").invoke(audioTrack);
            } catch (Exception e) {
                // 回退到固定大小
                bufferSize = BUFFER_SIZE * 2;
            }
        } else {
            // Android 4.4 及以下版本使用固定缓冲区大小
            // 使用更小的缓冲区以减少延迟
            bufferSize = Math.max(1024, BUFFER_SIZE / 2);
        }

        // 确保缓冲区大小合理
        return Math.max(512, Math.min(bufferSize, 8192));
    }

    public void release() {
        stopRecording();
        isPlaying = false;

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

        Log.d(TAG, "音频资源已释放");
    }

    /**
     * μ-law编码表
     */
    private static final short[] MU_LAW_TABLE = {
            -32124, -31100, -30076, -29052, -28028, -27004, -25980, -24956,
            -23932, -22908, -21884, -20860, -19836, -18812, -17788, -16764,
            -15996, -15484, -14972, -14460, -13948, -13436, -12924, -12412,
            -11900, -11388, -10876, -10364, -9852, -9340, -8828, -8316,
            -7932, -7676, -7420, -7164, -6908, -6652, -6396, -6140,
            -5884, -5628, -5372, -5116, -4860, -4604, -4348, -4092,
            -3900, -3772, -3644, -3516, -3388, -3260, -3132, -3004,
            -2876, -2748, -2620, -2492, -2364, -2236, -2108, -1980,
            -1884, -1820, -1756, -1692, -1628, -1564, -1500, -1436,
            -1372, -1308, -1244, -1180, -1116, -1052, -988, -924,
            -876, -844, -812, -780, -748, -716, -684, -652,
            -620, -588, -556, -524, -492, -460, -428, -396,
            -372, -356, -340, -324, -308, -292, -276, -260,
            -244, -228, -212, -196, -180, -164, -148, -132,
            -120, -112, -104, -96, -88, -80, -72, -64,
            -56, -48, -40, -32, -24, -16, -8, 0,
            32124, 31100, 30076, 29052, 28028, 27004, 25980, 24956,
            23932, 22908, 21884, 20860, 19836, 18812, 17788, 16764,
            15996, 15484, 14972, 14460, 13948, 13436, 12924, 12412,
            11900, 11388, 10876, 10364, 9852, 9340, 8828, 8316,
            7932, 7676, 7420, 7164, 6908, 6652, 6396, 6140,
            5884, 5628, 5372, 5116, 4860, 4604, 4348, 4092,
            3900, 3772, 3644, 3516, 3388, 3260, 3132, 3004,
            2876, 2748, 2620, 2492, 2364, 2236, 2108, 1980,
            1884, 1820, 1756, 1692, 1628, 1564, 1500, 1436,
            1372, 1308, 1244, 1180, 1116, 1052, 988, 924,
            876, 844, 812, 780, 748, 716, 684, 652,
            620, 588, 556, 524, 492, 460, 428, 396,
            372, 356, 340, 324, 308, 292, 276, 260,
            244, 228, 212, 196, 180, 164, 148, 132,
            120, 112, 104, 96, 88, 80, 72, 64,
            56, 48, 40, 32, 24, 16, 8, 0
    };

    /**
     * 将PCM数据编码为μ-law格式
     * @param pcmData PCM输入数据
     * @param encodedData 编码后的输出数据
     * @param pcmLength PCM数据长度
     * @return 编码后的数据长度
     */
    private int encodeMuLaw(byte[] pcmData, byte[] encodedData, int pcmLength) {
        // 确保输入数据是偶数长度（16位样本）
        int sampleCount = pcmLength / 2;

        for (int i = 0; i < sampleCount; i++) {
            // 将两个字节组合成16位有符号整数（小端序）
            short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));

            // 查找最接近的μ-law值
            int sign = (sample & 0x8000) >> 8;
            if (sign != 0) {
                sample = (short) -sample;
            }

            if (sample > 32767) sample = 32767;

            int exponent = 7;
            int mask = 0x4000;

            // 找到最高有效位的位置
            while ((sample & mask) == 0 && exponent > 0) {
                exponent--;
                mask >>= 1;
            }

            int mantissa = (sample >> (exponent + 3)) & 0x0F;
            byte encoded = (byte) (sign | (exponent << 4) | mantissa);

            // 取反并存储
            encodedData[i] = (byte) ~encoded;
        }

        return sampleCount;
    }

    /**
     * 将μ-law数据解码为PCM格式
     * @param encodedData μ-law输入数据
     * @param pcmData PCM输出数据
     * @param encodedLength 编码数据长度
     * @return 解码后的PCM数据长度
     */
    private int decodeMuLaw(byte[] encodedData, byte[] pcmData, int encodedLength) {
        for (int i = 0; i < encodedLength; i++) {
            // 取反获取原始编码值
            byte ulaw = (byte) ~encodedData[i];

            // 提取符号、指数和尾数
            int sign = (ulaw & 0x80) >> 7;
            int exponent = (ulaw & 0x70) >> 4;
            int mantissa = ulaw & 0x0F;

            // 重建线性样本
            int sample = (mantissa << 3) + 0x84;
            sample <<= exponent;
            sample -= 0x84;

            if (sign == 0) {
                sample = -sample;
            }

            // 限制到16位范围
            if (sample > 32767) sample = 32767;
            if (sample < -32768) sample = -32768;

            // 拆分为两个字节（小端序）
            pcmData[i * 2] = (byte) (sample & 0xFF);
            pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return encodedLength * 2;
    }
}
