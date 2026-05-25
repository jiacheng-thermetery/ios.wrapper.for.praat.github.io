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

/* --- Objects window: enumerate the live object list ---
 * Objects persist across praatios_run() calls (the engine's object table is global), so the
 * Objects UI runs commands by generating `selectObject: <ids>` + the command through praatios_run. */
int         praatios_objectCount (void);
const char *praatios_objectInfo (int index1based);   /* "id|className|name|selected"; valid until next call */

/* Draw the first selected object (Sound/Spectrogram/Pitch/Formant/Intensity/Spectrum) to a PNG
 * file using Praat's own Quartz Graphics. Returns "ok" or an error message. */
const char *praatios_drawSelectedToPNG (const char *path, double widthInches, double heightInches, int resolution);

/* --- Manipulation (PSOLA): edit the pitch tier of the current Sound and resynthesize --- */
int praatios_manipulationStart (int maxN, double *times, double *values);   /* returns #pitch points */
int praatios_manipulationResynth (const double *times, const double *values, int n,
        float *out, int maxSamples, double *outRate);   /* returns #samples */

/* --- ExperimentMFC perception experiment (multiple forced choice) ---
 * mfcCreateDemo builds a built-in tone-height identification experiment (no files needed);
 * mfcUseSelected runs an ExperimentMFC the user opened from a file. Both return #trials (0 = none).
 * The UI plays each trial's stimulus (rendered to PCM) and records the tapped response. */
int         praatios_mfcCreateDemo (void);
int         praatios_mfcUseSelected (void);
int         praatios_mfcNumberOfTrials (void);
const char *praatios_mfcText (int which);            /* 0 start, 1 run, 2 pause, 3 end */
int         praatios_mfcResponseCount (void);
const char *praatios_mfcResponseInfo (int i1based);  /* "label|left|right|bottom|top" (0..1 coords) */
int         praatios_mfcStimulusForTrial (int trial1based);   /* stimulus index, 0 if none */
const char *praatios_mfcStimulusText (int trial1based);       /* visibleText for the trial */
int         praatios_mfcStimulusSound (int trial1based, float *out, int maxSamples, double *outRate);
void        praatios_mfcRecordResponse (int trial1based, int iresp, double goodness, double reactionTime);
const char *praatios_mfcResultsCSV (void);

/* --- analysis: set the current Sound from mono float PCM --- */
int    praatios_setSound (const float *samples, int count, double sampleRate);
double praatios_soundDuration (void);          /* seconds, 0 if no sound */
double praatios_soundSampleRate (void);

/* [iOS port] Bridge the Analyze tab and the Objects window (one shared engine):
 * addSoundObject puts a recorded/opened/spoken sound into the engine object list as a Sound
 * and selects it (returns the new object id, 0 on failure); selectedSoundPCM copies the first
 * selected Sound object's samples (mixed to mono) out so it can be sent to the Analyze tab. */
int praatios_addSoundObject (const float *samples, int count, double sampleRate, const char *name);
int praatios_selectedSoundPCM (float *out, int maxSamples, double *outRate);

/* Waveform of [t0,t1], downsampled to `n` peak-preserving (min,max) pairs. */
int    praatios_waveform (double t0, double t1, int n, float *outMin, float *outMax);

/* --- spectrogram (dB power matrix) over the visible window [t0,t1] ---
 * Re-analyses the part of the sound in [t0,t1] (like Praat's editor: zooming in shows
 * finer detail). Returns a pointer to nx*ny floats in dB (row-major: index = iy*nx + ix;
 * iy=0 is the lowest frequency). The buffer is owned by the bridge and valid until the
 * next call. NULL if no sound or the window is too small. */
const float *praatios_spectrogram (double t0, double t1, double maxFreq, double windowLength,
        double dynamicRange, int *outNx, int *outNy,
        double *outTmin, double *outTmax, double *outFmax,
        double *outDbMin, double *outDbMax);

/* --- analysis parameters (re-run analyses when changed) --- */
void praatios_setPitchRange (double floor, double ceiling);
void praatios_setFormantParams (double maxFreq, int numFormants, double windowLength);

/* --- analysis curves sampled over [tmin,tmax] into out[0..n-1] (NaN where undefined) ---
 * kind: 0 = pitch (Hz), 1 = intensity (dB), 2..6 = formant 1..5 (Hz). */
int praatios_curve (int kind, double tmin, double tmax, int n, float *out);

/* min/max used to scale a curve in the UI (e.g. pitch floor/ceiling, intensity range). */
void praatios_curveRange (int kind, double *outMin, double *outMax);

/* Single analysis value at time t (NaN if undefined/unvoiced).
 * kind: 0 = pitch (Hz), 1 = intensity (dB), 2..6 = formant 1..5 (Hz). */
double praatios_valueAt (int kind, double t);

/* --- spectral slice at time t (Cmd+L) ---
 * Returns a pointer to `*outN` floats: power density in dB vs frequency (0..outFmax).
 * Buffer owned by the bridge, valid until the next call. NULL if no sound. */
const float *praatios_spectrumSlice (double t, double windowDur,
        int *outN, double *outFmax, double *outDbMin, double *outDbMax);

#ifdef __cplusplus
}
#endif
#endif
