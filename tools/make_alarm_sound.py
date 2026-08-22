#!/usr/bin/env python3
"""Generates the built-in siren shipped as the default alarm sound.

Synthesised rather than sourced so there is no licence to honour and no attribution to
carry, and so the loop can be made exactly seamless.

The character is a classic two-tone intruder siren: a harsh warble alternating between two
dissonant tones several times a second, with odd harmonics to give it the piercing quality
of a piezo sounder rather than the politeness of a sine wave.

Two details make the loop seam inaudible without resorting to a cross-fade:

* Frequency is applied through a running phase accumulator, so switching tones bends the
  waveform instead of jumping it. A jump would click.
* The tones are whole numbers of hertz and each is sounded for exactly one second, so the
  accumulated phase over the two-second loop is an exact whole number of cycles and the
  waveform lands back precisely where it started.
"""
import math
import struct
import wave

SAMPLE_RATE = 44_100
TONE_A_HZ = 2730          # near the ear's most sensitive band
TONE_B_HZ = 2170
WARBLE_SECONDS = 0.125    # time on each tone -> a 4 Hz warble
TOTAL_SECONDS = 2.0       # one second on each tone in total, so the phase closes
PEAK = 0.86               # leaves headroom for the harmonic sum

# Odd harmonics only, square-ish. Amplitudes fall off so it stays harsh without turning to mush.
HARMONICS = ((1, 1.0), (3, 0.34), (5, 0.16))

OUT = "app/src/main/res/raw/alarm_siren.wav"


def render():
    frames = int(SAMPLE_RATE * TOTAL_SECONDS)
    samples_per_tone = int(SAMPLE_RATE * WARBLE_SECONDS)

    phase = 0.0
    raw = []
    for n in range(frames):
        on_tone_a = (n // samples_per_tone) % 2 == 0
        freq = TONE_A_HZ if on_tone_a else TONE_B_HZ

        # Accumulate phase rather than evaluating sin(2*pi*f*t): this is what keeps the
        # waveform continuous across a tone change.
        phase += 2.0 * math.pi * freq / SAMPLE_RATE

        value = 0.0
        for multiple, amplitude in HARMONICS:
            if freq * multiple < SAMPLE_RATE / 2:   # never synthesise above Nyquist
                value += amplitude * math.sin(phase * multiple)
        raw.append(value)

    loudest = max(abs(v) for v in raw)
    scale = PEAK / loudest
    return [int(max(-32768, min(32767, round(v * scale * 32767)))) for v in raw]


def write_wav(samples, path):
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(b"".join(struct.pack("<h", s) for s in samples))


def goertzel(samples, target_hz):
    """Energy at one frequency, for checking the tones came out where intended."""
    n = len(samples)
    k = int(0.5 + (n * target_hz) / SAMPLE_RATE)
    omega = (2.0 * math.pi * k) / n
    coeff = 2.0 * math.cos(omega)
    s1 = s2 = 0.0
    for sample in samples:
        s0 = sample / 32768.0 + coeff * s1 - s2
        s2, s1 = s1, s0
    return math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / n


def main():
    samples = render()
    write_wav(samples, OUT)

    seconds = len(samples) / SAMPLE_RATE
    dc = sum(samples) / len(samples)
    peak = max(abs(s) for s in samples)
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))

    # The loop seam. Comparing the first and last sample values would be the wrong test: in any
    # continuous waveform adjacent samples differ, so a raw difference proves nothing. What
    # matters is whether the step *across* the seam looks like an ordinary step within the
    # waveform. If it does, the repeat is indistinguishable from the tone carrying on.
    steps = [abs(samples[n + 1] - samples[n]) for n in range(len(samples) - 1)]
    seam_step = abs(samples[0] - samples[-1])
    worst_step = max(steps)
    typical_step = sorted(steps)[len(steps) // 2]

    window = int(SAMPLE_RATE * WARBLE_SECONDS)
    first_tone = samples[:window]
    second_tone = samples[window:2 * window]

    print(f"wrote {OUT}")
    print(f"  {seconds:.3f}s, {len(samples)} frames, {SAMPLE_RATE} Hz mono 16-bit")
    print(f"  peak {peak} ({peak / 32767:.0%} of full scale), rms {rms:.0f}, dc offset {dc:+.1f}")
    print(f"  seam step {seam_step}, typical step {typical_step}, largest step {worst_step}"
          f" -> {'seamless' if seam_step <= worst_step else 'CLICKS'}")
    print(f"  first window  {TONE_A_HZ}Hz={goertzel(first_tone, TONE_A_HZ):.4f}"
          f"  {TONE_B_HZ}Hz={goertzel(first_tone, TONE_B_HZ):.4f}")
    print(f"  second window {TONE_A_HZ}Hz={goertzel(second_tone, TONE_A_HZ):.4f}"
          f"  {TONE_B_HZ}Hz={goertzel(second_tone, TONE_B_HZ):.4f}")


if __name__ == "__main__":
    main()
