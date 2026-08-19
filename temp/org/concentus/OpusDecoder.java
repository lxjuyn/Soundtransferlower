package org.concentus;

/**
 * Opus decoder - STUB for compilation.
 * This is a stub class. Replace with actual Concentus OpusDecoder for runtime use.
 *
 * Real source: https://github.com/concentus/Concentus
 */
public class OpusDecoder {

    private int sampleRate;
    private int channels;

    public OpusDecoder(int sampleRate, int channels) throws OpusException {
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * Decodes Opus data to PCM samples.
     *
     * @param opusData    input Opus encoded data
     * @param opusOffset  offset in opusData array
     * @param opusLength  length of Opus data to decode
     * @param pcmOutput   output buffer for PCM samples (interleaved if stereo)
     * @param pcmOffset   offset in pcmOutput array
     * @param pcmSamples  max samples to decode per channel
     * @param decodeFec   whether to decode FEC (forward error correction)
     * @return number of samples decoded per channel
     * @throws OpusException if decoding fails
     */
    public int decode(byte[] opusData, int opusOffset, int opusLength,
                      short[] pcmOutput, int pcmOffset, int pcmSamples,
                      boolean decodeFec) throws OpusException {
        throw new OpusException("STUB: Replace with real Concentus OpusDecoder JAR");
    }

    public void resetState() {
        // stub
    }

    public void destroy() {
        // stub
    }
}
