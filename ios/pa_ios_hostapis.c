/* pa_ios_hostapis.c — empty PortAudio host-API table for iOS.
 *
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * iOS has no PortAudio host backend (the macOS build uses pa_mac_hostapis.c +
 * CoreAudio, which is not available on iOS). Live audio in the iOS app is provided
 * by the app shell via AVAudioEngine, not by PortAudio. This empty, NULL-terminated
 * table lets PortAudio link and lets Pa_Initialize() succeed reporting zero devices,
 * so Praat's audio calls fail gracefully instead of crashing.
 */
#include "pa_hostapi.h"

PaUtilHostApiInitializer *paHostApiInitializers [] = { 0 };   /* NULL-terminated */
int paDefaultHostApiIndex = 0;
