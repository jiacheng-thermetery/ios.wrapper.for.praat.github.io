/* test_bridge.mm — standalone harness that exercises the Analyze↔Objects bridge functions
 * on the real embedded Praat engine (run via `simctl spawn booted`). Not part of the app.
 * Part of the Spraak derivative. GPL-3.0-or-later. */
#include "PraatBridge.h"
#include <cstdio>
#include <cmath>
#include <vector>

static int failures = 0;
static void check (bool ok, const char *what) {
	printf ("%s  %s\n", ok ? "PASS" : "FAIL", what);
	if (! ok) failures ++;
}

int main () {
	praatios_init ();
	printf ("== init ok ==\n");

	// 1) addSoundObject creates a selected Sound object in the engine list
	const int n = 1000; const double sr = 16000.0;
	std::vector<float> s (n);
	for (int i = 0; i < n; i ++) s [i] = 0.3f * sinf (2.0f * (float) M_PI * 440.0f * i / (float) sr);

	int id = praatios_addSoundObject (s.data (), n, sr, "testtone");
	printf ("addSoundObject -> id=%d, count=%d, info(1)=%s\n", id, praatios_objectCount (), praatios_objectInfo (1));
	check (id > 0, "addSoundObject returns a positive id");
	check (praatios_objectCount () == 1, "object count is 1");
	{
		std::string info = praatios_objectInfo (1);
		check (info.find ("Sound") != std::string::npos, "object 1 is a Sound");
		check (info.find ("testtone") != std::string::npos, "object 1 is named testtone");
		check (info.size () >= 2 && info.substr (info.size () - 2) == "|1", "object 1 is selected");
	}

	// 2) selectedSoundPCM round-trips the samples and sample rate
	std::vector<float> out (n + 16); double rrate = 0;
	int ns = praatios_selectedSoundPCM (out.data (), n + 16, & rrate);
	double maxerr = 0; for (int i = 0; i < n; i ++) maxerr = fmax (maxerr, fabs (out [i] - s [i]));
	printf ("selectedSoundPCM -> ns=%d rate=%.1f maxerr=%.6g\n", ns, rrate, maxerr);
	check (ns == n, "selectedSoundPCM returns the right sample count");
	check (fabs (rrate - sr) < 1e-6, "selectedSoundPCM returns the right rate");
	check (maxerr < 1e-5, "selectedSoundPCM round-trips the samples");

	// 3) adding a second sound selects the newest (mirrors the GUI) and reads back its PCM
	std::vector<float> s2 (500, 0.123f);
	int id2 = praatios_addSoundObject (s2.data (), 500, 8000.0, "second");
	printf ("addSoundObject2 -> id=%d count=%d info(2)=%s\n", id2, praatios_objectCount (), praatios_objectInfo (2));
	check (praatios_objectCount () == 2, "object count is 2");
	{
		std::string a = praatios_objectInfo (1), b = praatios_objectInfo (2);
		check (a.size () >= 2 && a.substr (a.size () - 2) == "|0", "first object is deselected");
		check (b.size () >= 2 && b.substr (b.size () - 2) == "|1", "second object is selected");
	}
	double r2 = 0; int ns2 = praatios_selectedSoundPCM (out.data (), n + 16, & r2);
	printf ("selectedSoundPCM(now) -> ns=%d rate=%.1f\n", ns2, r2);
	check (ns2 == 500 && fabs (r2 - 8000.0) < 1e-6, "selection followed the newest object");

	printf ("== %s (%d failures) ==\n", failures == 0 ? "ALL PASS" : "FAILURES", failures);
	return failures;
}
