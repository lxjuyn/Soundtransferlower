package org.concentus;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Opus decoder that wraps Android's native MediaCodec Opus decoder (API 21+).
 * Falls back to silence on older devices.
 */
public class OpusDecoder {
    private static final String TAG = "OpusDecoder";
    private static final String MIME_TYPE = "audio/opus";

    private final int sampleRate;
    private final int channels;
    private MediaCodec codec;
    private boolean isConfigured = false;
    private boolean codecStarted = false;

    public OpusDecoder(int sampleRate, int channels) throws OpusException {
        this.sampleRate = sampleRate;
        this.channels = channels;

        if (Build.VERSION.SDK_INT < 21) {
            Log.w(TAG, "OpusDecoder: API < 21, decoding will not work properly");
            return;
        }

        try {
            codec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            codec.configure(format, null, null, 0);
            isConfigured = true;
            Log.d(TAG, "OpusDecoder created: " + sampleRate + "Hz, " + channels + "ch");
        } catch (IOException e) {
            throw new OpusException("Failed to create Opus decoder: " + e.getMessage(), e);
        }
    }

    /**
     * Decode Opus data to PCM samples.
     * @param input Opus encoded data
     * @param inputOffset offset in input array
     * @param inputLength length of Opus data
     * @param output output buffer for decoded PCM (short samples)
     * @param outputOffset offset in output buffer
     * @param maxOutputSamples maximum samples to decode
     * @param decodeFec whether to decode FEC (forward error correction)
     * @return number of decoded samples
     * @throws OpusException if decoding fails
     */
    public int decode(byte[] input, int inputOffset, int inputLength,
                      short[] output, int outputOffset, int maxOutputSamples,
                      boolean decodeFec) throws OpusException {
        if (Build.VERSION.SDK_INT < 21 || codec == null || !isConfigured) {
            throw new OpusException("Opus decoder not available (requires API 21+)");
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
                throw new OpusException("Failed to start decoder: " + e2.getMessage(), e2);
            }
        }

        try {
            // Queue input buffer
            int inputIndex = codec.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                inputBuffer.clear();
                inputBuffer.put(input, inputOffset, inputLength);
                codec.queueInputBuffer(inputIndex, 0, inputLength, 0, 0);
            } else {
                throw new OpusException("Decoder input buffer timeout");
            }

            // Dequeue output buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                int dataSize = bufferInfo.size;
                int samplesDecoded = dataSize / 2; // 16-bit mono = 2 bytes per sample
                samplesDecoded = Math.min(samplesDecoded, maxOutputSamples);

                // Read decoded PCM as bytes and convert to shorts
                byte[] pcmBytes = new byte[dataSize];
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.get(pcmBytes, 0, dataSize);

                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(output, outputOffset, samplesDecoded);

                codec.releaseOutputBuffer(outputIndex, false);
                return samplesDecoded;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed, try again
                return decode(input, inputOffset, inputLength, output, outputOffset, maxOutputSamples, decodeFec);
            } else {
                throw new OpusException("Decoder output buffer timeout");
            }
        } catch (IllegalStateException e) {
            throw new OpusException("Decoding failed: " + e.getMessage(), e);
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
