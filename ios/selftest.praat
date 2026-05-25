# selftest.praat — exercises the Praat compute + scripting engine headlessly.
# Used to prove the iOS core build actually runs (via `xcrun simctl spawn`).
# Part of the Spraak derivative. GPL-3.0-or-later.

writeInfoLine: "Praat iOS core self-test"

# 1. Synthesize a 0.5 s tone complex (220 Hz + harmonics) as a Sound.
sound = Create Sound from formula: "test", 1, 0.0, 0.5, 16000,
    ... "0.5*sin(2*pi*220*x) + 0.3*sin(2*pi*440*x) + 0.2*sin(2*pi*660*x)"
selectObject: sound
ns = Get number of samples
fs = Get sampling frequency
appendInfoLine: "Created Sound: ", ns, " samples at ", fs, " Hz"

# 2. Pitch analysis — the heart of Praat.
selectObject: sound
pitch = To Pitch: 0.0, 75, 600
selectObject: pitch
meanF0 = Get mean: 0.0, 0.0, "Hertz"
appendInfoLine: "Mean F0: ", fixed$ (meanF0, 2), " Hz   (expected ~220)"

# 3. Spectral analysis.
selectObject: sound
spectrum = To Spectrum: "yes"
selectObject: spectrum
cog = Get centre of gravity: 2.0
appendInfoLine: "Spectral centre of gravity: ", fixed$ (cog, 1), " Hz"

# 4. Intensity.
selectObject: sound
intensity = To Intensity: 100, 0.0, "yes"
selectObject: intensity
meanInt = Get mean: 0.0, 0.0, "energy"
appendInfoLine: "Mean intensity: ", fixed$ (meanInt, 2), " dB"

# 5. Numeric/scripting sanity (vector ops run on the CPU path).
v# = zero# (5)
for i to 5
    v# [i] = i * i
endfor
appendInfoLine: "Vector {1,4,9,16,25} sum = ", sum (v#), "   (expected 55)"

appendInfoLine: "OK -- Praat phonetics engine runs on iOS."
