/* PraatBridge.h — C interface between Swift and the Praat engine.
 * Part of the Spraak derivative. GPL-3.0-or-later. */
#ifndef PRAAT_BRIDGE_H
#define PRAAT_BRIDGE_H
#ifdef __cplusplus
extern "C" {
#endif

/* Initialise the Praat engine once (safe to call repeatedly). */
void praatios_init (void);

/* Run a Praat script (UTF-8). Returns the Info-window text, or the error
 * message if the script failed. The returned pointer is owned by the bridge
 * and stays valid until the next call to praatios_run. */
const char *praatios_run (const char *utf8script);

#ifdef __cplusplus
}
#endif
#endif
