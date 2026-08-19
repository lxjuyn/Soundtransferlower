package org.concentus;

/**
 * Concentus Opus - Pure Java Opus encoder/decoder.
 * This is a lightweight shim that wraps Android's native MediaCodec Opus support.
 * Requires Android API 21+ for actual encoding/decoding functionality.
 *
 * Original: https://github.com/concentus/Concentus
 */
public enum OpusApplication {
    OPUS_APPLICATION_VOIP(2048),
    OPUS_APPLICATION_AUDIO(2049),
    OPUS_APPLICATION_RESTRICTED_LOWDELAY(2051);

    private final int value;

    OpusApplication(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
