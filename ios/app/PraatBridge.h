/* PraatBridge.h — C interface between Swift and the Praat engine.
 * Part of the Spraak derivative. GPL-3.0-or-later. */
#ifndef PRAAT_BRIDGE_H
#define PRAAT_BRIDGE_H
#ifdef __cplusplus
extern "C" {
#endif

/* --- engine lifecycle / scripting console --- */
void praatios_init (void);
const char *praatios_run (const char *utf8script);   /* runs a script, returns Info/error text */

/* --- analysis: set the current Sound from mono float PCM --- */
int    praatios_setSound (const float *samples, int count, double sampleRate);
double praatios_soundDuration (void);          /* seconds, 0 if no sound */
double praatios_soundSampleRate (void);

/* Copy the waveform (downsampled to `n` points, peak-preserving) into out[0..n-1]. */
int    praatios_waveform (int n, float *outMin, float *outMax);

/* --- spectrogram (dB power matrix) ---
 * Returns a pointer to nx*ny floats in dB (row-major: index = iy*nx + ix; iy=0 is the
 * lowest frequency). The buffer is owned by the bridge and valid until the next call.
 * NULL if no sound. */
const float *praatios_spectrogram (double maxFreq, double windowLength,
        int *outNx, int *outNy,
        double *outTmin, double *outTmax, double *outFmax,
        double *outDbMin, double *outDbMax);

/* --- analysis curves sampled over [tmin,tmax] into out[0..n-1] (NaN where undefined) ---
 * kind: 0 = pitch (Hz), 1 = intensity (dB), 2..6 = formant 1..5 (Hz). */
int praatios_curve (int kind, double tmin, double tmax, int n, float *out);

/* min/max used to scale a curve in the UI (e.g. pitch floor/ceiling, intensity range). */
void praatios_curveRange (int kind, double *outMin, double *outMax);

/* --- spectral slice at time t (Cmd+L) ---
 * Returns a pointer to `*outN` floats: power density in dB vs frequency (0..outFmax).
 * Buffer owned by the bridge, valid until the next call. NULL if no sound. */
const float *praatios_spectrumSlice (double t, double windowDur,
        int *outN, double *outFmax, double *outDbMin, double *outDbMax);

#ifdef __cplusplus
}
#endif
#endif
