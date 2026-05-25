/* embed_test.cpp — verify the embedding init path for the iOS app shell.
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * Sequence: set a stand-alone bootstrap script (makes praat_init pick batch mode
 * without exiting in a NO_GUI build), full praat_init + uvafon_init + praat_run
 * (completes initialisation and returns), then run further scripts on demand via
 * praat_executeScriptFromText — exactly what the app's C bridge will do.
 */
#include "praat.h"
#include "praat_script.h"
#include "praat_uvafon_init.h"
#include <cstdio>

int main () {
	static char arg0 [] = "Spraak";
	static char *argv [] = { arg0, nullptr };
	try {
		fprintf (stderr, "[1] set stand-alone bootstrap script\n"); fflush (stderr);
		praat_setStandAloneScriptText (U"# bootstrap\n");
		fprintf (stderr, "[2] praat_init\n"); fflush (stderr);
		praat_init (U"Spraak", U"6.4.67", 6467, 2026, 5, 21, U"x", U"y", 1, argv);
		fprintf (stderr, "[3] uvafon_init\n"); fflush (stderr);
		praat_uvafon_init ();
		fprintf (stderr, "[4] (skipping praat_run; init is complete)\n"); fflush (stderr);
	} catch (MelderError) {
		fprintf (stderr, "[INIT-ERROR] "); fflush (stderr);
		Melder_flushError ();
		return 2;
	}
	try {
		praat_executeScriptFromText (Melder_dup (U""
			"writeInfoLine: \"second script runs\"\n"
			"s = Create Sound from formula: \"x\", 1, 0, 0.2, 16000, \"sin (2*pi*200*x)\"\n"
			"selectObject: s\n"
			"pitch = To Pitch: 0.0, 75, 600\n"
			"selectObject: pitch\n"
			"f0 = Get mean: 0, 0, \"Hertz\"\n"
			"appendInfoLine: \"f0=\", fixed$ (f0, 1)").get());
	} catch (MelderError) {
		fprintf (stderr, "[E] MelderError\n"); Melder_flushError ();
	}
	fprintf (stderr, "[6] done\n"); fflush (stderr);
	printf ("=== CAPTURED Melder_getInfo() ===\n%s\n", Melder_peek32to8 (Melder_getInfo ()));
	return 0;
}
