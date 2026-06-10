/* PraatBridgeAndroid.h — Android-only extensions to the shared Praat C bridge.
 * Part of the Spraak derivative. GPL-3.0-or-later. */
#ifndef PRAAT_BRIDGE_ANDROID_H
#define PRAAT_BRIDGE_ANDROID_H
#ifdef __cplusplus
extern "C" {
#endif

/* --- Draw the first selected object as a Praat Graphics *recording* ---
 * Android has no Quartz/Cairo/GDI, so instead of rendering to a PNG in C++
 * (the iOS path), the object is drawn into a backend-less Graphics with
 * recording enabled, and the raw opcode stream (sys/Graphics_record.cpp
 * format: [opcode, numberOfArguments, args...]*) is handed to Kotlin, which
 * replays it onto an android.graphics.Canvas.
 *
 * Returns the number of doubles in the record (0 on failure or nothing
 * selected; check praatandroid_drawError() then). The buffer is owned by the
 * bridge and valid until the next praatandroid_drawSelectedRecord call. */
int praatandroid_drawSelectedRecord (double widthInches, double heightInches,
        const double **outRecord);
const char *praatandroid_drawError (void);   /* "" if the last draw succeeded */

#ifdef __cplusplus
}
#endif
#endif
