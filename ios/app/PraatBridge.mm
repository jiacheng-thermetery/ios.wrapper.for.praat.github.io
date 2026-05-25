/* PraatBridge.mm — bridges Swift to the embedded Praat engine + DSP.
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * Embedding recipe (verified on the iOS simulator): praat_setStandAloneScriptText
 * makes praat_init choose batch mode without exiting in a NO_GRAPHICS build; then
 * praat_init + praat_uvafon_init fully initialise the engine. We skip praat_run and
 * call the analysis functions (Sound_to_Spectrogram_e, Sound_to_Pitch, …) directly.
 */
#include "PraatBridge.h"
#include "praat.h"
#include "praat_script.h"
#include "praat_uvafon_init.h"

#include "Sound.h"
#include "Sound_and_Spectrogram.h"
#include "Sound_to_Pitch.h"
#include "Pitch.h"
#include "Sound_to_Formant.h"
#include "Formant.h"
#include "Sound_to_Intensity.h"
#include "Intensity.h"
#include "Vector.h"
#include "Sound_and_Spectrum.h"
#include "Spectrum.h"

#include <string>
#include <vector>
#include <cmath>

static autoSound       theSound;
static autoSpectrogram theSpectrogram;
static autoPitch       thePitch;
static autoFormant     theFormant;
static autoIntensity   theIntensity;

static std::string g_result;
static std::vector<float> g_spectro;
static std::vector<float> g_slice;

/* analysis parameters (Praat-like defaults) */
static const double kPitchFloor = 75.0, kPitchCeiling = 600.0;
static const double kMaxFormantFreq = 5500.0;
static const int    kNumFormants = 5;

void praatios_init (void) {
	static bool inited = false;
	if (inited) return;
	static char arg0 [] = "Spraak";
	static char *argv [] = { arg0, nullptr };
	try {
		praat_setStandAloneScriptText (U"# Spraak bootstrap\n");
		praat_init (U"Spraak", U"6.4.67", 6467, 2026, 5, 21, U"x", U"y", 1, argv);
		praat_uvafon_init ();
		inited = true;
	} catch (MelderError) {
		Melder_clearError ();
	}
}

const char *praatios_run (const char *utf8script) {
	praatios_init ();
	g_result.clear ();
	try {
		autostring32 script = Melder_8to32_e (utf8script);
		praat_executeScriptFromText (script.get());
	} catch (MelderError) { }
	conststring32 info = Melder_getInfo ();
	if (info && info [0]) g_result = (const char *) Melder_peek32to8 (info);
	if (Melder_hasError ()) {
		if (! g_result.empty ()) g_result += "\n";
		g_result += (const char *) Melder_peek32to8 (Melder_getError ());
		Melder_clearError ();
	}
	if (g_result.empty ()) g_result = "(no output)";
	return g_result.c_str ();
}

int praatios_setSound (const float *samples, int count, double sampleRate) {
	praatios_init ();
	if (count <= 0 || sampleRate <= 0.0) return 0;
	try {
		const double dx = 1.0 / sampleRate;
		autoSound s = Sound_create (1, 0.0, count * dx, count, dx, 0.5 * dx);
		for (int i = 0; i < count; i ++)
			s -> z [1] [i + 1] = samples [i];
		theSound = s.move();
		theSpectrogram = autoSpectrogram();   // invalidate caches
		thePitch = autoPitch();
		theFormant = autoFormant();
		theIntensity = autoIntensity();
		return 1;
	} catch (MelderError) {
		Melder_clearError ();
		return 0;
	}
}

double praatios_soundDuration (void) {
	return theSound. get() ? theSound -> xmax - theSound -> xmin : 0.0;
}
double praatios_soundSampleRate (void) {
	return theSound. get() ? 1.0 / theSound -> dx : 0.0;
}

int praatios_waveform (double t0, double t1, int n, float *outMin, float *outMax) {
	if (! theSound. get() || n <= 0) return 0;
	const integer nx = theSound -> nx;
	const double dx = theSound -> dx, xmin = theSound -> xmin;
	constVEC z = theSound -> z [1];
	auto clampIdx = [&] (double t) -> integer {
		integer i = (integer) llround ((t - xmin) / dx);
		return i < 0 ? 0 : (i > nx ? nx : i);
	};
	const integer s0 = clampIdx (t0);
	integer s1 = clampIdx (t1);
	if (s1 <= s0) s1 = (s0 < nx ? s0 + 1 : nx);
	const integer span = s1 - s0;
	for (int b = 0; b < n; b ++) {
		const integer a  = s0 + (integer) ((int64_t) b * span / n);
		const integer bb = s0 + (integer) ((int64_t) (b + 1) * span / n);
		double lo = 1e30, hi = -1e30;
		for (integer i = a; i < bb && i < nx; i ++) { const double v = z [i + 1]; if (v < lo) lo = v; if (v > hi) hi = v; }
		if (hi < lo) { lo = hi = 0.0; }
		outMin [b] = (float) lo; outMax [b] = (float) hi;
	}
	return 1;
}

const float *praatios_spectrogram (double t0, double t1, double maxFreq, double windowLength,
		int *outNx, int *outNy, double *outTmin, double *outTmax, double *outFmax,
		double *outDbMin, double *outDbMax) {
	if (! theSound. get()) return nullptr;
	if (t1 - t0 < 3.0 * windowLength) return nullptr;   // window too small to analyse
	try {
		autoSound part = Sound_extractPart (theSound.get(), t0, t1,
				kSound_windowShape::RECTANGULAR, 1.0, true /* preserve times */);
		const double timeStep = (t1 - t0) / 800.0;      // up to ~800 columns across the window
		theSpectrogram = Sound_to_Spectrogram_e (part.get(), windowLength, maxFreq,
				timeStep, 20.0, kSound_to_Spectrogram_windowShape::GAUSSIAN, 8.0, 8.0);
	} catch (MelderError) { Melder_clearError (); return nullptr; }
	Spectrogram s = theSpectrogram.get();
	const integer nx = s -> nx, ny = s -> ny;
	g_spectro.assign ((size_t) nx * ny, 0.0f);
	double dbMax = -1e30;
	for (integer iy = 1; iy <= ny; iy ++)
		for (integer ix = 1; ix <= nx; ix ++) {
			const double power = s -> z [iy] [ix];
			const double db = 10.0 * log10 ((power <= 0.0 ? 1e-30 : power) / 4.0e-10);
			g_spectro [(size_t) (iy - 1) * nx + (ix - 1)] = (float) db;
			if (db > dbMax) dbMax = db;
		}
	const double dynamicRange = 70.0;   // Praat default
	*outNx = (int) nx; *outNy = (int) ny;
	*outTmin = s -> xmin; *outTmax = s -> xmax;
	*outFmax = s -> ymax;
	*outDbMax = dbMax; *outDbMin = dbMax - dynamicRange;
	return g_spectro.data();
}

static void ensurePitch ()     { if (! thePitch. get())     { try { thePitch = Sound_to_Pitch (theSound.get(), 0.0, kPitchFloor, kPitchCeiling); } catch (MelderError) { Melder_clearError (); } } }
static void ensureFormant ()   { if (! theFormant. get())   { try { theFormant = Sound_to_Formant_burg (theSound.get(), 0.0, kNumFormants, kMaxFormantFreq, 0.025, 50.0); } catch (MelderError) { Melder_clearError (); } } }
static void ensureIntensity () { if (! theIntensity. get()) { try { theIntensity = Sound_to_Intensity (theSound.get(), kPitchFloor, 0.0, true); } catch (MelderError) { Melder_clearError (); } } }

int praatios_curve (int kind, double tmin, double tmax, int n, float *out) {
	if (! theSound. get() || n <= 0) return 0;
	for (int i = 0; i < n; i ++) out [i] = NAN;
	const double dt = (tmax - tmin) / n;
	if (kind == 0) {
		ensurePitch (); if (! thePitch. get()) return 0;
		for (int i = 0; i < n; i ++) {
			const double v = Pitch_getValueAtTime (thePitch.get(), tmin + (i + 0.5) * dt, kPitch_unit::HERTZ, true);
			if (isdefined (v)) out [i] = (float) v;
		}
	} else if (kind == 1) {
		ensureIntensity (); if (! theIntensity. get()) return 0;
		for (int i = 0; i < n; i ++) {
			const double v = Vector_getValueAtX (theIntensity.get(), tmin + (i + 0.5) * dt, 1, kVector_valueInterpolation::LINEAR);
			if (isdefined (v)) out [i] = (float) v;
		}
	} else if (kind >= 2 && kind <= 6) {
		ensureFormant (); if (! theFormant. get()) return 0;
		const integer fn = kind - 1;
		for (int i = 0; i < n; i ++) {
			const double v = Formant_getValueAtTime (theFormant.get(), fn, tmin + (i + 0.5) * dt, kFormant_unit::HERTZ);
			if (isdefined (v)) out [i] = (float) v;
		}
	} else return 0;
	return 1;
}

void praatios_curveRange (int kind, double *outMin, double *outMax) {
	if (kind == 0)      { *outMin = kPitchFloor;  *outMax = kPitchCeiling; }
	else if (kind == 1) { *outMin = 50.0;         *outMax = 100.0; }
	else                { *outMin = 0.0;          *outMax = kMaxFormantFreq; }
}

const float *praatios_spectrumSlice (double t, double windowDur,
		int *outN, double *outFmax, double *outDbMin, double *outDbMax) {
	if (! theSound. get()) return nullptr;
	autoSpectrum sp;
	try {
		const double half = windowDur;   // half-window; extractPart applies the window shape
		autoSound part = Sound_extractPart (theSound.get(), t - half, t + half,
				kSound_windowShape::GAUSSIAN_2, 1.0, false);
		sp = Sound_to_Spectrum (part.get(), true);
	} catch (MelderError) { Melder_clearError (); return nullptr; }
	const integer nf = sp -> nx;
	g_slice.assign ((size_t) nf, 0.0f);
	double dbMax = -1e30, dbMin = 1e30;
	for (integer i = 1; i <= nf; i ++) {
		const double re = sp -> z [1] [i], im = sp -> z [2] [i];
		const double powerDensity = 2.0 * (re * re + im * im);   // Pa^2 / Hz^2
		const double db = 10.0 * log10 ((powerDensity <= 0.0 ? 1e-30 : powerDensity) / 4.0e-10);
		g_slice [(size_t) (i - 1)] = (float) db;
		if (db > dbMax) dbMax = db;
		if (db < dbMin && db > -1e10) dbMin = db;
	}
	*outN = (int) nf;
	*outFmax = sp -> xmax;
	*outDbMax = dbMax;
	*outDbMin = (dbMax - dbMin > 100.0) ? dbMax - 100.0 : dbMin;
	return g_slice.data();
}
