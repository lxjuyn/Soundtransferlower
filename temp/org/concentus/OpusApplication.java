package org.concentus;

/**
 * Opus application type selector.
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
