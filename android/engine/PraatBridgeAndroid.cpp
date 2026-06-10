/* PraatBridgeAndroid.cpp — Android-only extensions to the shared Praat C bridge.
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * [Android port] The iOS bridge renders the Objects-window "Draw" to a PNG via
 * Praat's Quartz Graphics backend. Android has no Quartz/Cairo/GDI, so this file
 * draws into a backend-less GraphicsScreen with *recording* switched on and
 * exports the raw opcode stream; PraatPicture.kt replays it on an Android Canvas
 * (Praat's own Graphics_play in sys/Graphics_record.cpp documents the format).
 */
#include "PraatBridgeAndroid.h"
#include "../../ios/app/PraatBridge.h"
#include "praat.h"
#include "GraphicsP.h"

#include "Sound.h"
#include "Spectrogram.h"
#include "Pitch.h"
#include "Formant.h"
#include "Intensity.h"
#include "Spectrum.h"

#include <string>

static std::string g_drawError;
static autoGraphics g_recGraphics;   // owns the record buffer between calls

const char *praatandroid_drawError (void) {
	return g_drawError.c_str ();
}

int praatandroid_drawSelectedRecord (double wInches, double hInches, const double **outRecord) {
	praatios_init ();
	g_drawError.clear ();
	*outRecord = nullptr;
	praat_Object found = nullptr;
	for (integer i = 1; i <= theCurrentPraatObjects -> n; i ++)
		if (theCurrentPraatObjects -> list [i]. isSelected) { found = & theCurrentPraatObjects -> list [i]; break; }
	if (! found) { g_drawError = "Select an object first."; return 0; }
	try {
		/* A pngfile GraphicsScreen has correct device extents for the requested size
		   even with no drawing backend compiled in; only the recording is used. */
		structMelderFile dummyFile { };
		Melder_pathToFile (U"/dev/null", & dummyFile);
		g_recGraphics = Graphics_create_pngfile (& dummyFile, 100, 0.0, wInches, 0.0, hInches);
		Graphics me = g_recGraphics.get();
		Graphics_startRecording (me);
		Graphics_setFontSize (me, 10.0);
		Graphics_setViewport (me, 0.8, wInches - 0.3, 0.4, hInches - 0.4);
		Graphics_setWindow (me, 0.0, 1.0, 0.0, 1.0);
		conststring32 cls = found -> klas -> className;
		Daata obj = found -> object;
		if (str32equ (cls, U"Sound"))
			Sound_draw ((Sound) obj, me, 0, 0, 0, 0, true, U"Curve");
		else if (str32equ (cls, U"Spectrogram"))
			Spectrogram_paint ((Spectrogram) obj, me, 0, 0, 0, 0, 100.0, true, 50.0, 6.0, 0.0, true);
		else if (str32equ (cls, U"Pitch"))
			Pitch_draw ((Pitch) obj, me, 0, 0, 0, 500.0, true, false, kPitch_unit::HERTZ);
		else if (str32equ (cls, U"Formant"))
			Formant_drawSpeckles ((Formant) obj, me, 0, 0, 5500.0, 30.0, true);
		else if (str32equ (cls, U"Intensity"))
			Intensity_draw ((Intensity) obj, me, 0, 0, 0, 0, true);
		else if (str32equ (cls, U"Spectrum"))
			Spectrum_draw ((Spectrum) obj, me, 0, 0, 0, 0, true);
		else {
			g_drawError = "Drawing is not yet supported for ";
			g_drawError += (const char *) Melder_peek32to8 (cls);
			g_recGraphics.reset ();
			return 0;
		}
		Graphics_stopRecording (me);
		*outRecord = me -> record;
		return (int) me -> irecord;
	} catch (MelderError) {
		Melder_clearError ();
		g_drawError = "Draw failed.";
		g_recGraphics.reset ();
		return 0;
	}
}
