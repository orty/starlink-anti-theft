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
TONE_A_HZ = 800           # low enough not to be shrill, high enough to carry
TONE_B_HZ = 570           # roughly a tritone below: dissonant, so it reads as an alarm
WARBLE_SECONDS = 0.1      # time on each tone -> a 5 Hz warble
TOTAL_SECONDS = 2.0       # one second on each tone in total, so the phase closes
PEAK = 0.86               # leaves headroom for the harmonic sum

# Odd harmonics only, square-ish. These carry the cut-through: with a fundamental this low the
# upper harmonics are what makes it audible across a room, without the shrillness of putting
# the fundamental itself up at 2-3 kHz.
HARMONICS = ((1, 1.0), (3, 0.38), (5, 0.2), (7, 0.1))

OUT = "app/src/main/res/raw/alarm_siren.wav"


def render():
    frames = int(SAMPLE_RATE * TOTAL_SECONDS)
    samples_per_tone = int(SAMPLE_RATE * WARBLE_SECONDS)

    # The seamless loop rests on three things being exactly true rather than nearly true, so
    # they are checked instead of assumed. An earlier version quietly failed the first of
    # these - 44100 * 0.125 is 5512.5 - which left the two tones with unequal airtime and the
    # phase not quite closing.
    assert SAMPLE_RATE * WARBLE_SECONDS == samples_per_tone, (
        f"a warble of {WARBLE_SECONDS}s is {SAMPLE_RATE * WARBLE_SECONDS} samples, not a whole number"
    )
    assert frames % (2 * samples_per_tone) == 0, (
        "the loop must hold a whole number of two-tone cycles so each tone sounds for equally long"
    )
    assert float(TONE_A_HZ + TONE_B_HZ).is_integer(), (
        "whole-hertz tones are what make the accumulated phase land on a whole number of cycles"
    )

    phase = 0.0
    raw = []
    # One extra sample past the end: it is what would come next if the tone simply carried on,
    # so comparing it with the first sample is a direct test of whether the loop closes.
    for n in range(frames + 1):
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

    loudest = max(abs(v) for v in raw[:frames])
    scale = PEAK / loudest
    quantised = [int(max(-32768, min(32767, round(v * scale * 32767)))) for v in raw]

    # The definitive seam test. If the sample after the last one is the first one, then looping
    # is indistinguishable from the waveform continuing, whatever the slope happens to be there.
    wrap_residual = abs(quantised[frames] - quantised[0])
    assert wrap_residual == 0, (
        f"the loop does not close: continuing past the end gives {quantised[frames]}, "
        f"but it restarts at {quantised[0]}"
    )
    return quantised[:frames], wrap_residual


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
    samples, wrap_residual = render()
    write_wav(samples, OUT)

    # With equal airtime per tone, the accumulated phase over the loop is (fA + fB) cycles.
    # Anything other than a whole number here means the waveform does not close.
    cycles = (TONE_A_HZ + TONE_B_HZ) * (TOTAL_SECONDS / 2)
    assert float(cycles).is_integer(), f"phase closes on {cycles} cycles, which is not whole"

    seconds = len(samples) / SAMPLE_RATE
    dc = sum(samples) / len(samples)
    peak = max(abs(s) for s in samples)
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))

    window = int(SAMPLE_RATE * WARBLE_SECONDS)
    first_tone = samples[:window]
    second_tone = samples[window:2 * window]

    print(f"wrote {OUT}")
    print(f"  {seconds:.3f}s, {len(samples)} frames, {SAMPLE_RATE} Hz mono 16-bit")
    print(f"  tones {TONE_A_HZ}/{TONE_B_HZ} Hz, {1 / (2 * WARBLE_SECONDS):.0f} Hz warble,"
          f" phase closes on {int(cycles)} whole cycles")
    print(f"  peak {peak} ({peak / 32767:.0%} of full scale), rms {rms:.0f}, dc offset {dc:+.1f}")
    print(f"  loop closes exactly: continuing past the end lands {wrap_residual} away from the start")
    print(f"  first window  {TONE_A_HZ}Hz={goertzel(first_tone, TONE_A_HZ):.4f}"
          f"  {TONE_B_HZ}Hz={goertzel(first_tone, TONE_B_HZ):.4f}")
    print(f"  second window {TONE_A_HZ}Hz={goertzel(second_tone, TONE_A_HZ):.4f}"
          f"  {TONE_B_HZ}Hz={goertzel(second_tone, TONE_B_HZ):.4f}")


if __name__ == "__main__":
    main()
