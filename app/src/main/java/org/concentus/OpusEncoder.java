package org.concentus;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Opus encoder that wraps Android's native MediaCodec Opus encoder (API 21+).
 * Falls back to a simple pass-through on older devices (won't produce valid Opus).
 */
public class OpusEncoder {
    private static final String TAG = "OpusEncoder";
    private static final String MIME_TYPE = "audio/opus";

    private final int sampleRate;
    private final int channels;
    private final OpusApplication application;
    private MediaCodec codec;
    private boolean isConfigured = false;
    private boolean codecStarted = false;
    private int bitrate = 24000; // default
    private int complexity = 10; // default

    public OpusEncoder(int sampleRate, int channels, OpusApplication application) throws OpusException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.application = application;

        if (Build.VERSION.SDK_INT < 21) {
            Log.w(TAG, "OpusEncoder: API < 21, encoding will not work properly");
            return;
        }

        try {
            codec = MediaCodec.createEncoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            // Opus frame size: 20ms = sampleRate * 20 / 1000 samples
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            isConfigured = true;
            Log.d(TAG, "OpusEncoder created: " + sampleRate + "Hz, " + channels + "ch");
        } catch (IOException e) {
            throw new OpusException("Failed to create Opus encoder: " + e.getMessage(), e);
        }
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
        // Note: setParameters requires API 19+. Bitrate will be applied on next encoder creation.
    }

    public void setComplexity(int complexity) {
        this.complexity = complexity;
    }

    /**
     * Encode PCM samples to Opus.
     * @param input PCM input (short samples)
     * @param inputOffset offset in input array
     * @param inputSamples number of samples to encode
     * @param output output buffer for encoded Opus data
     * @param outputOffset offset in output buffer
     * @param maxOutputBytes maximum bytes to write to output
     * @return number of encoded bytes, or -1 on error
     * @throws OpusException if encoding fails
     */
    public int encode(short[] input, int inputOffset, int inputSamples,
                      byte[] output, int outputOffset, int maxOutputBytes) throws OpusException {
        if (Build.VERSION.SDK_INT < 21 || codec == null || !isConfigured) {
            throw new OpusException("Opus encoder not available (requires API 21+)");
        }

        // Start codec if not running
        try {
            if (!codecStarted) {
                codec.start();
                codecStarted = true;
            }
        } catch (IllegalStateException e) {
            try {
                codec.start();
                codecStarted = true;
            } catch (IllegalStateException e2) {
                throw new OpusException("Failed to start encoder: " + e2.getMessage(), e2);
            }
        }

        try {
            // Convert short[] to byte[] (little-endian PCM)
            int pcmBytes = inputSamples * 2;
            byte[] pcmData = new byte[pcmBytes];
            ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .put(input, inputOffset, inputSamples);

            // Queue input buffer
            int inputIndex = codec.dequeueInputBuffer(10000); // 10ms timeout
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                inputBuffer.clear();
                inputBuffer.put(pcmData);
                codec.queueInputBuffer(inputIndex, 0, pcmBytes, 0, 0);
            } else {
                throw new OpusException("Encoder input buffer timeout");
            }

            // Dequeue output buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                int size = Math.min(bufferInfo.size, maxOutputBytes);
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.get(output, outputOffset, size);
                codec.releaseOutputBuffer(outputIndex, false);
                return size;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed, try again
                return encode(input, inputOffset, inputSamples, output, outputOffset, maxOutputBytes);
            } else {
                throw new OpusException("Encoder output buffer timeout");
            }
        } catch (IllegalStateException e) {
            throw new OpusException("Encoding failed: " + e.getMessage(), e);
        }
    }

    public void reset() throws OpusException {
        if (codec != null) {
            try {
                codec.flush();
            } catch (IllegalStateException e) {
                // Ignore
            }
        }
    }

    public void destroy() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException e) {
                // Ignore
            }
            try {
                codec.release();
            } catch (IllegalStateException e) {
                // Ignore
            }
            codec = null;
        }
        isConfigured = false;
    }
}
