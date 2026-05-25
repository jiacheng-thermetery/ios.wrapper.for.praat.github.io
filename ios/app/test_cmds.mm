/* test_cmds.mm — verify the filter/convert/combine command strings the Objects UI generates
 * actually run on the embedded engine (run via `simctl spawn booted`). Not part of the app.
 * Part of the Spraak derivative. GPL-3.0-or-later. */
#include "PraatBridge.h"
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0;

/* Run a script and assert it didn't error and the object count became `expectCount`. */
static void runStep (const char *script, int expectCount, const char *what) {
	const char *out = praatios_run (script);
	int count = praatios_objectCount ();
	bool err = strstr (out, "rror") != nullptr;   // "Error"/"error"
	bool ok = ! err && count == expectCount;
	printf ("%s  %-28s -> count=%d %s\n", ok ? "PASS" : "FAIL", what, count, err ? "[ERROR in output]" : "");
	if (! ok) { printf ("      output: %.180s\n", out); failures ++; }
}

int main () {
	praatios_init ();
	printf ("== filter / convert / combine ==\n");

	// base sound (object 1)
	runStep ("Create Sound as pure tone: \"a\", 1, 0, 0.4, 44100, 440, 0.4, 0.01, 0.01", 1, "Create pure tone");

	runStep ("selectObject: 1\nFilter (pass Hann band): 0, 2000, 100", 2, "Filter (pass Hann band)");
	runStep ("selectObject: 1\nFilter (stop Hann band): 0, 500, 100", 3, "Filter (stop Hann band)");
	runStep ("selectObject: 1\nResample: 22050, 50", 4, "Resample");
	runStep ("selectObject: 1\nConvert to mono", 5, "Convert to mono");
	runStep ("selectObject: 1\nConvert to stereo", 6, "Convert to stereo");
	runStep ("selectObject: 1\nScale peak: 0.99", 6, "Scale peak (in place)");

	// combine needs two mono sounds: object 1 and a new one (object 7)
	runStep ("Create Sound as pure tone: \"b\", 1, 0, 0.4, 44100, 660, 0.4, 0.01, 0.01", 7, "Create 2nd tone");
	runStep ("selectObject: 1, 7\nCombine to stereo", 8, "Combine to stereo");
	runStep ("selectObject: 1, 7\nConcatenate", 9, "Concatenate");

	printf ("== %s (%d failures) ==\n", failures == 0 ? "ALL PASS" : "FAILURES", failures);
	return failures;
}
