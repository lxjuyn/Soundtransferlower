package org.concentus;

/**
 * Opus encoder - STUB for compilation.
 * This is a stub class. Replace with actual Concentus OpusEncoder for runtime use.
 *
 * Real source: https://github.com/concentus/Concentus
 */
public class OpusEncoder {

    private int sampleRate;
    private int channels;
    private OpusApplication application;
    private int bitrate = 64000;
    private int complexity = 10;

    public OpusEncoder(int sampleRate, int channels, OpusApplication application) throws OpusException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.application = application;
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    public int getBitrate() {
        return bitrate;
    }

    public void setComplexity(int complexity) {
        this.complexity = complexity;
    }

    public int getComplexity() {
        return complexity;
    }

    /**
     * Encodes PCM samples to Opus.
     *
     * @param pcm         PCM input samples (interleaved if stereo)
     * @param pcmOffset   offset in pcm array
     * @param pcmSamples  number of samples to encode per channel
     * @param opusOutput  output buffer for encoded Opus data
     * @param opusOffset  offset in opusOutput array
     * @param maxOpusBytes maximum bytes to write to opusOutput
     * @return number of bytes written to opusOutput
     * @throws OpusException if encoding fails
     */
    public int encode(short[] pcm, int pcmOffset, int pcmSamples,
                      byte[] opusOutput, int opusOffset, int maxOpusBytes) throws OpusException {
        throw new OpusException("STUB: Replace with real Concentus OpusEncoder JAR");
    }

    public void resetState() {
        // stub
    }

    public void destroy() {
        // stub
    }
}
