/* praat_jni.cpp — JNI layer between Kotlin (com.thermetery.spraak.PraatEngine)
 * and the Praat C bridge. Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * [Android port] Thin wrappers over ios/app/PraatBridge.h — the bridge body is
 * plain C++ and compiles for Android unchanged — plus the Android-only draw-
 * recording extension in PraatBridgeAndroid.cpp. Strings cross this boundary as
 * real UTF-8 on the C side and UTF-16 on the Java side: JNI's NewStringUTF/
 * GetStringUTFChars use *modified* UTF-8 (CESU-8), which mangles or aborts on
 * supplementary-plane characters, so both directions are converted manually.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string>
#include <vector>
#include "../../ios/app/PraatBridge.h"
#include "PraatBridgeAndroid.h"

/* --- UTF conversions (real UTF-8, not JNI's modified UTF-8) --- */

static std::u16string utf8to16 (const char *s) {
	std::u16string out;
	if (! s) return out;
	const unsigned char *p = (const unsigned char *) s;
	while (*p) {
		uint32_t cp = 0; int extra = 0;
		if (*p < 0x80) { cp = *p; }
		else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F; extra = 1; }
		else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F; extra = 2; }
		else if ((*p & 0xF8) == 0xF0) { cp = *p & 0x07; extra = 3; }
		else { p ++; continue; }   // invalid lead byte: skip
		p ++;
		for (int i = 0; i < extra; i ++) {
			if ((*p & 0xC0) != 0x80) { cp = 0xFFFD; break; }
			cp = (cp << 6) | (*p & 0x3F); p ++;
		}
		if (cp >= 0x10000 && cp <= 0x10FFFF) {
			cp -= 0x10000;
			out += (char16_t) (0xD800 + (cp >> 10));
			out += (char16_t) (0xDC00 + (cp & 0x3FF));
		} else
			out += (char16_t) (cp <= 0x10FFFF ? cp : 0xFFFD);
	}
	return out;
}

static std::string jstringToUtf8 (JNIEnv *env, jstring js) {
	std::string out;
	if (! js) return out;
	const jchar *u16 = env -> GetStringChars (js, nullptr);
	const jsize n = env -> GetStringLength (js);
	for (jsize i = 0; i < n; i ++) {
		uint32_t cp = u16 [i];
		if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n
				&& u16 [i + 1] >= 0xDC00 && u16 [i + 1] <= 0xDFFF) {
			cp = 0x10000 + ((cp - 0xD800) << 10) + (u16 [i + 1] - 0xDC00);
			i ++;
		}
		if (cp < 0x80) out += (char) cp;
		else if (cp < 0x800) {
			out += (char) (0xC0 | (cp >> 6));
			out += (char) (0x80 | (cp & 0x3F));
		} else if (cp < 0x10000) {
			out += (char) (0xE0 | (cp >> 12));
			out += (char) (0x80 | ((cp >> 6) & 0x3F));
			out += (char) (0x80 | (cp & 0x3F));
		} else {
			out += (char) (0xF0 | (cp >> 18));
			out += (char) (0x80 | ((cp >> 12) & 0x3F));
			out += (char) (0x80 | ((cp >> 6) & 0x3F));
			out += (char) (0x80 | (cp & 0x3F));
		}
	}
	env -> ReleaseStringChars (js, u16);
	return out;
}

static jstring utf8ToJString (JNIEnv *env, const char *utf8) {
	const std::u16string u16 = utf8to16 (utf8);
	return env -> NewString ((const jchar *) u16.data (), (jsize) u16.size ());
}

extern "C" {
#define FN(name) JNIEXPORT JNICALL Java_com_thermetery_spraak_PraatEngine_##name

/* --- lifecycle / scripting --- */

/* [Android port] iOS apps always have HOME pointing at the app sandbox; Android app
 * processes have no usable HOME/TMPDIR, and Praat's UNIX paths (preferences folder,
 * PID file, ~-expansion) build on them. MainActivity points them at filesDir/cacheDir
 * before the first engine call. */
void FN(setEnv) (JNIEnv *env, jobject, jstring name, jstring value) {
	const std::string n = jstringToUtf8 (env, name), v = jstringToUtf8 (env, value);
	setenv (n.c_str (), v.c_str (), 1);
}

void FN(init) (JNIEnv *, jobject) {
	praatios_init ();
}

jstring FN(runScript) (JNIEnv *env, jobject, jstring script) {
	const std::string utf8 = jstringToUtf8 (env, script);
	return utf8ToJString (env, praatios_run (utf8.c_str ()));
}

/* --- Objects window --- */

jint FN(objectCount) (JNIEnv *, jobject) {
	return praatios_objectCount ();
}

jstring FN(objectInfo) (JNIEnv *env, jobject, jint index1based) {
	return utf8ToJString (env, praatios_objectInfo (index1based));
}

/* --- current Sound / analysis --- */

jint FN(setSound) (JNIEnv *env, jobject, jfloatArray samples, jdouble sampleRate) {
	jfloat *p = env -> GetFloatArrayElements (samples, nullptr);
	const jint n = env -> GetArrayLength (samples);
	const int r = praatios_setSound (p, n, sampleRate);
	env -> ReleaseFloatArrayElements (samples, p, JNI_ABORT);
	return r;
}

jdouble FN(soundDuration) (JNIEnv *, jobject) { return praatios_soundDuration (); }
jdouble FN(soundSampleRate) (JNIEnv *, jobject) { return praatios_soundSampleRate (); }

jint FN(addSoundObject) (JNIEnv *env, jobject, jfloatArray samples, jdouble sampleRate, jstring name) {
	jfloat *p = env -> GetFloatArrayElements (samples, nullptr);
	const jint n = env -> GetArrayLength (samples);
	const std::string nm = jstringToUtf8 (env, name);
	const int r = praatios_addSoundObject (p, n, sampleRate, nm.c_str ());
	env -> ReleaseFloatArrayElements (samples, p, JNI_ABORT);
	return r;
}

jint FN(selectedSoundPCM) (JNIEnv *env, jobject, jfloatArray out, jdoubleArray outRate) {
	jfloat *p = env -> GetFloatArrayElements (out, nullptr);
	double rate = 0.0;
	const int n = praatios_selectedSoundPCM (p, env -> GetArrayLength (out), & rate);
	env -> ReleaseFloatArrayElements (out, p, 0);
	env -> SetDoubleArrayRegion (outRate, 0, 1, & rate);
	return n;
}

jint FN(waveform) (JNIEnv *env, jobject, jdouble t0, jdouble t1, jint n,
		jfloatArray outMin, jfloatArray outMax) {
	jfloat *pmin = env -> GetFloatArrayElements (outMin, nullptr);
	jfloat *pmax = env -> GetFloatArrayElements (outMax, nullptr);
	const int r = praatios_waveform (t0, t1, n, pmin, pmax);
	env -> ReleaseFloatArrayElements (outMin, pmin, 0);
	env -> ReleaseFloatArrayElements (outMax, pmax, 0);
	return r;
}

/* outDims = [nx, ny]; outMeta = [tmin, tmax, fmax, dbMin, dbMax] */
jfloatArray FN(spectrogram) (JNIEnv *env, jobject, jdouble t0, jdouble t1,
		jdouble maxFreq, jdouble windowLength, jdouble dynamicRange,
		jintArray outDims, jdoubleArray outMeta) {
	int nx = 0, ny = 0;
	double tmin = 0, tmax = 0, fmax = 0, dbMin = 0, dbMax = 0;
	const float *m = praatios_spectrogram (t0, t1, maxFreq, windowLength, dynamicRange,
			& nx, & ny, & tmin, & tmax, & fmax, & dbMin, & dbMax);
	if (! m) return nullptr;
	const jint dims [2] = { nx, ny };
	const double meta [5] = { tmin, tmax, fmax, dbMin, dbMax };
	env -> SetIntArrayRegion (outDims, 0, 2, dims);
	env -> SetDoubleArrayRegion (outMeta, 0, 5, meta);
	jfloatArray arr = env -> NewFloatArray ((jsize) nx * ny);
	env -> SetFloatArrayRegion (arr, 0, (jsize) nx * ny, m);
	return arr;
}

void FN(setPitchRange) (JNIEnv *, jobject, jdouble floor, jdouble ceiling) {
	praatios_setPitchRange (floor, ceiling);
}

void FN(setFormantParams) (JNIEnv *, jobject, jdouble maxFreq, jint numFormants, jdouble windowLength) {
	praatios_setFormantParams (maxFreq, numFormants, windowLength);
}

jfloatArray FN(curve) (JNIEnv *env, jobject, jint kind, jdouble tmin, jdouble tmax, jint n) {
	if (n <= 0) return nullptr;
	std::vector<float> buf ((size_t) n);
	if (! praatios_curve (kind, tmin, tmax, n, buf.data ())) return nullptr;
	jfloatArray arr = env -> NewFloatArray (n);
	env -> SetFloatArrayRegion (arr, 0, n, buf.data ());
	return arr;
}

void FN(curveRange) (JNIEnv *env, jobject, jint kind, jdoubleArray outMinMax) {
	double mn = 0, mx = 0;
	praatios_curveRange (kind, & mn, & mx);
	const double v [2] = { mn, mx };
	env -> SetDoubleArrayRegion (outMinMax, 0, 2, v);
}

jdouble FN(valueAt) (JNIEnv *, jobject, jint kind, jdouble t) {
	return praatios_valueAt (kind, t);
}

/* outMeta = [fmax, dbMin, dbMax] */
jfloatArray FN(spectrumSlice) (JNIEnv *env, jobject, jdouble t, jdouble windowDur, jdoubleArray outMeta) {
	int n = 0;
	double fmax = 0, dbMin = 0, dbMax = 0;
	const float *s = praatios_spectrumSlice (t, windowDur, & n, & fmax, & dbMin, & dbMax);
	if (! s) return nullptr;
	const double meta [3] = { fmax, dbMin, dbMax };
	env -> SetDoubleArrayRegion (outMeta, 0, 3, meta);
	jfloatArray arr = env -> NewFloatArray (n);
	env -> SetFloatArrayRegion (arr, 0, n, s);
	return arr;
}

/* --- Manipulation (PSOLA) --- */

jint FN(manipulationStart) (JNIEnv *env, jobject, jdoubleArray times, jdoubleArray values) {
	jdouble *pt = env -> GetDoubleArrayElements (times, nullptr);
	jdouble *pv = env -> GetDoubleArrayElements (values, nullptr);
	const int n = praatios_manipulationStart (env -> GetArrayLength (times), pt, pv);
	env -> ReleaseDoubleArrayElements (times, pt, 0);
	env -> ReleaseDoubleArrayElements (values, pv, 0);
	return n;
}

jint FN(manipulationResynth) (JNIEnv *env, jobject, jdoubleArray times, jdoubleArray values,
		jfloatArray out, jdoubleArray outRate) {
	jdouble *pt = env -> GetDoubleArrayElements (times, nullptr);
	jdouble *pv = env -> GetDoubleArrayElements (values, nullptr);
	jfloat *po = env -> GetFloatArrayElements (out, nullptr);
	double rate = 0.0;
	const int n = praatios_manipulationResynth (pt, pv, env -> GetArrayLength (times),
			po, env -> GetArrayLength (out), & rate);
	env -> ReleaseDoubleArrayElements (times, pt, JNI_ABORT);
	env -> ReleaseDoubleArrayElements (values, pv, JNI_ABORT);
	env -> ReleaseFloatArrayElements (out, po, 0);
	env -> SetDoubleArrayRegion (outRate, 0, 1, & rate);
	return n;
}

/* --- ExperimentMFC --- */

jint FN(mfcCreateDemo) (JNIEnv *, jobject) { return praatios_mfcCreateDemo (); }
jint FN(mfcUseSelected) (JNIEnv *, jobject) { return praatios_mfcUseSelected (); }
jint FN(mfcNumberOfTrials) (JNIEnv *, jobject) { return praatios_mfcNumberOfTrials (); }

jstring FN(mfcText) (JNIEnv *env, jobject, jint which) {
	return utf8ToJString (env, praatios_mfcText (which));
}

jint FN(mfcResponseCount) (JNIEnv *, jobject) { return praatios_mfcResponseCount (); }

jstring FN(mfcResponseInfo) (JNIEnv *env, jobject, jint i1based) {
	return utf8ToJString (env, praatios_mfcResponseInfo (i1based));
}

jint FN(mfcStimulusForTrial) (JNIEnv *, jobject, jint trial1based) {
	return praatios_mfcStimulusForTrial (trial1based);
}

jstring FN(mfcStimulusText) (JNIEnv *env, jobject, jint trial1based) {
	return utf8ToJString (env, praatios_mfcStimulusText (trial1based));
}

jint FN(mfcStimulusSound) (JNIEnv *env, jobject, jint trial1based, jfloatArray out, jdoubleArray outRate) {
	jfloat *p = env -> GetFloatArrayElements (out, nullptr);
	double rate = 0.0;
	const int n = praatios_mfcStimulusSound (trial1based, p, env -> GetArrayLength (out), & rate);
	env -> ReleaseFloatArrayElements (out, p, 0);
	env -> SetDoubleArrayRegion (outRate, 0, 1, & rate);
	return n;
}

void FN(mfcRecordResponse) (JNIEnv *, jobject, jint trial1based, jint iresp, jdouble goodness, jdouble reactionTime) {
	praatios_mfcRecordResponse (trial1based, iresp, goodness, reactionTime);
}

jstring FN(mfcResultsCSV) (JNIEnv *env, jobject) {
	return utf8ToJString (env, praatios_mfcResultsCSV ());
}

/* --- Draw (Graphics recording; see PraatBridgeAndroid.h) --- */

jdoubleArray FN(drawSelectedRecord) (JNIEnv *env, jobject, jdouble widthInches, jdouble heightInches) {
	const double *rec = nullptr;
	const int n = praatandroid_drawSelectedRecord (widthInches, heightInches, & rec);
	if (n <= 0 || ! rec) return nullptr;
	jdoubleArray arr = env -> NewDoubleArray (n);
	env -> SetDoubleArrayRegion (arr, 0, n, rec);
	return arr;
}

jstring FN(drawError) (JNIEnv *env, jobject) {
	return utf8ToJString (env, praatandroid_drawError ());
}

}   // extern "C"
