/* PraatBridge.mm — bridges Swift to the embedded Praat engine.
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * Embedding recipe (verified on the iOS 18.1 simulator):
 *   praat_setStandAloneScriptText() makes praat_init choose batch mode without
 *   exiting in a NO_GRAPHICS build; praat_init + praat_uvafon_init fully initialise
 *   the engine; we deliberately skip praat_run (which would run once and exit) and
 *   instead drive the interpreter on demand with praat_executeScriptFromText.
 */
#include "PraatBridge.h"
#include "praat.h"
#include "praat_script.h"
#include "praat_uvafon_init.h"
#include <string>

static std::string g_result;

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
	} catch (MelderError) {
		/* error text remains in Melder_getError() until cleared, below */
	}
	conststring32 info = Melder_getInfo ();
	if (info && info [0])
		g_result = (const char *) Melder_peek32to8 (info);
	if (Melder_hasError ()) {
		if (! g_result.empty ()) g_result += "\n";
		g_result += (const char *) Melder_peek32to8 (Melder_getError ());
		Melder_clearError ();
	}
	if (g_result.empty ())
		g_result = "(no output)";
	return g_result.c_str ();
}
