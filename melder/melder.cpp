/* melder.cpp
 *
 * Copyright (C) 1992-2018,2020,2021,2024 Paul Boersma
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this work. If not, see <http://www.gnu.org/licenses/>.
 */

#include "melder.h"

#include "enums_getText.h"
#include "melder_enums.h"
#include "enums_getValue.h"
#include "melder_enums.h"

#include "../dwsys/NUMmachar.h"
#include "../external/gsl/gsl_errno.h"
#if defined (macintosh) && ! defined (PRAAT_IOS)
	#include <Carbon/Carbon.h>   // Gestalt; [iOS port] Carbon is macOS-desktop-only (absent on iOS)
#endif

void Melder_init () {
	NUMmachar ();
	gsl_set_error_handler_off ();
	NUMrandom_initializeSafelyAndUnpredictably ();
	Melder_alloc_init ();
	Melder_audiofiles_init ();
	#if defined (macintosh) && ! defined (PRAAT_IOS)
		NSOperatingSystemVersion version = [[NSProcessInfo processInfo] operatingSystemVersion];
		Melder_systemVersion = version. majorVersion * 10000 + version. minorVersion * 100 + version. patchVersion;
	#endif
	// [iOS port] On iOS, Melder_systemVersion stays 0 (avoids requiring Foundation/Obj-C in the core).
	#ifdef linux
		const char *gdkBackend = getenv ("GDK_BACKEND");   // this setting rules all the others; GDK typically supports wayland, x11, and broadway
		if (gdkBackend) {
			if (strequ (gdkBackend, "wayland"))
				Melder_systemVersion = 'w';
			else
				Melder_systemVersion = 0;
		} else {
			const char *waylandDisplay = getenv ("WAYLAND_DISPLAY");
			const char *gdkSessionType = getenv ("XDG_SESSION_TYPE");   // not set on Chrome (2021-11-12); typical values are wayland, x11 and tty
			if (waylandDisplay || gdkSessionType && strstr (gdkSessionType, "wayland"))
				Melder_systemVersion = 'w';
			else
				Melder_systemVersion = 0;
		}
	#endif
}

/* End of file melder.cpp */
