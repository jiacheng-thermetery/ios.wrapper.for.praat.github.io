/* bridge_selftest.c — host-side (Linux/WSL) smoke test of the shared Praat bridge,
 * simulating Android app conditions (no HOME/TMPDIR until set explicitly).
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 *   bridge_selftest            # HOME unset, like a bare Android process
 *   bridge_selftest /tmp/home  # HOME set, like MainActivity's setEnv fix
 */
#include <stdio.h>
#include <stdlib.h>
#include "../../ios/app/PraatBridge.h"
#include "PraatBridgeAndroid.h"

int main (int argc, char **argv) {
	if (argc > 1) {
		setenv ("HOME", argv [1], 1);
		printf ("[selftest] HOME set to %s\n", argv [1]);
	} else {
		unsetenv ("HOME");
		printf ("[selftest] HOME unset (bare Android process conditions)\n");
	}
	unsetenv ("TMPDIR");

	printf ("[selftest] praatios_init...\n");
	fflush (stdout);
	praatios_init ();
	printf ("[selftest] init survived\n");

	printf ("[selftest] runScript: Create Sound + Info\n");
	fflush (stdout);
	const char *out = praatios_run (
		"Create Sound as pure tone: \"tone\", 1, 0, 0.3, 44100, 220, 0.4, 0.01, 0.01\n"
		"Info");
	printf ("[selftest] script output:\n%s\n", out);

	printf ("[selftest] objectCount = %d\n", praatios_objectCount ());
	printf ("[selftest] objectInfo(1) = %s\n", praatios_objectInfo (1));

	const double *rec = NULL;
	int n = praatandroid_drawSelectedRecord (6.0, 4.5, & rec);
	printf ("[selftest] drawSelectedRecord -> %d doubles (err: \"%s\")\n", n, praatandroid_drawError ());
	if (n > 4 && rec)
		printf ("[selftest] record head: %g %g %g %g\n", rec [0], rec [1], rec [2], rec [3]);

	printf ("[selftest] ALL OK\n");
	return 0;
}
