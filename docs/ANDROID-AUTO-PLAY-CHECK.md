# Android Auto's Play-only check for template apps

Status: researched against Android Auto 17.9.664044 (versionCode 179664044), decompiled
from `~/tmp/play-services/android-auto`. Nothing here changes Third Ear; it records what
the gate is and what it would take to get around it.

## What the gate is

Android Auto's projection validator (`jem` in `classes.dex`) classifies every car app by
its declared `<uses>` type. The "Unknown sources" developer switch only covers a fixed
subset of those types:

```java
// jem.<clinit>: the types Unknown sources unlocks
i = Set.of(MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE, SMS);
// TEMPLATE and PROJECTION are missing
```

and the last thing the validator does is:

```java
:gate
if (D() && !i.contains(type)) {        // D() reads carservice/allow_unknown_sources
    log("Package DENIED; failed all other checks [" + pkg + "]");
    return false;
}
return true;
```

So a `template` app (Third Ear, OsmAnd, Vela, Home Assistant) can never pass on a normal
head unit, whatever the installer is and whatever the Unknown sources switch says. Google
documents the same gap: Unknown sources "doesn't apply to apps built using the Android for
Cars App Library" (developer.android.com/training/cars/testing).

Before that gate the validator only checks that the app is enabled, is not on the block
list, and declares the matching `<uses>` type in its `automotive_app_desc.xml` ("Uses for
%s not defined"). Third Ear declares `template`, so it reaches the gate and dies there.

The installer allowlist one step earlier (`AppValidation__allowed_3p_installers`, decoded:
SKT/kt/olleh/LG U+ stores only) does not contain `com.android.vending`. Play installs pass
because the *initiating* package is `com.android.vending` itself, or because Play Store's
`PlayGearheadService.isPackageInstalledByPlay` answers. On GrapheneOS the initiating
package is AOSP's `com.android.packageinstaller`, and the code special-cases only Google's
`com.google.android.packageinstaller`:

```smali
# jem.v
if (initiatingPackage.equals("com.google.android.packageinstaller")) {
    installer = getInstallSourceInfo(pkg).getInstallerPackageName();
} else {
    installer = initiatingPackage;
}
```

## The patch

Make the gate unconditional. In `jem.v`:

```smali
    :cond_22
-   invoke-direct {v1}, Ljem;->D()Z
-   ...
-   if-nez v0, :cond_24
-   ...
-   invoke-virtual {v7}, Lqxy;->name()   # log argument only
+   goto :cond_24                       # cond_24 = const/16 v17, 1 ; return v17
```

Everything else (disabled package, block list, missing `<uses>`, first-party skip list,
allowlists, Play ownership query) still applies. A working build was made by decoding
`base.apk` with apktool, editing `smali/jem.smali`, assembling with smali 3.0.9, then
zipalign and apksigner over all four splits. `AppValidation__should_bypass_validation`
(default `false`, read in `actz.e()` and tested as `jem.o` one branch earlier) is the
intended knob; an APK patch is only the no-root way to flip it.

## Why the patch alone does not help on GrapheneOS

Patching means re-signing, and everything that matters identifies Android Auto by
certificate:

1. **GrapheneOS pins AA's signing certificate.** `PackageExtInit.getPackageId()` maps
   `com.google.android.projection.gearhead` through
   `validate(ANDROID_AUTO, 11_0_635014L, "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295")`.
   A self-signed build logs *"SigningDetails … don't contain any of known certificates"*
   and gets `PackageId.UNKNOWN`. `GmsCompat.isEnabledFor()` then turns the compatibility
   layer off, `GmsCompatPackageHooks.maybeGet()` stops returning `AndroidAutoHooks`, and
   the AA-only privileged grants disappear: `ADD_TRUSTED_DISPLAY`,
   `MANAGE_USB_ANDROID_AUTO`, `BLUETOOTH_PRIVILEGED_ANDROID_AUTO`, `MEDIA_CONTENT_CONTROL`,
   `WIFI_PRIVILEGED_ANDROID_AUTO`, `TOGGLE_AUTOMOTIVE_PROJECTION`. On the test phone those
   are granted `SYSTEM_FIXED` (checked with `dumpsys package`). Without them AA cannot
   create the projection display, and the entry also vanishes from Settings → Apps →
   Sandboxed Google Play → Android Auto, so the wired/wireless toggles cannot be re-granted.
2. **Play services checks the signer.** GMS binds zero-party binders through
   `ZeroPartyBinder`, which throws `SecurityException("Wrong signature")` unless the caller
   is signed with GMS's own certificate. Projection goes through that path.
3. **`com.google.android.finsky.permission.GEARHEAD_SERVICE` is `signature`** (dumped from
   the Play Store manifest), so a re-signed AA loses it and the
   `isPackageInstalledByPlay` query path with it.
4. **GrapheneOS Apps owns AA's updates** (`installerPackageName=app.grapheneos.apps`) and
   will keep offering the Google-signed build.

Not theory: the Vela author decompiled gearhead, patched exactly this validator, installed
the build, and hit all of it. On GrapheneOS the car then reports "no installed apps work
with this USB accessory" (github.com/PimpinPumpkin/Vela/issues/86, 19 Jul 2026).

## Routes that were checked and do not work

- **`adb install -i com.android.vending` / KingInstaller / InstallerX→Play / Termux+rish.**
  These set `installerPackageName` but not the initiating package. AA honours the installer
  field only when the initiator is Google's `com.google.android.packageinstaller`; on
  GrapheneOS the initiator is AOSP's `com.android.packageinstaller`, so the override is
  skipped. Tested on the phone: `adb install -r -i com.android.vending` still ends in
  `Package DENIED; failed all other checks`.
- **`pm set-installer app.thirdear com.android.vending`.** Refused with `SecurityException:
  Caller does not have same cert as new installer package com.android.vending` — only
  something signed like Play may do it. The same refusal under Shizuku, which runs as the
  shell UID, so KingInstaller's Shizuku mode fails here as well. Its classic mode has been
  stripped by the installer since Android 14 (bug 236687884).
- **Unknown sources.** Excludes `TEMPLATE` by construction, as above.
- **Declaring the app as `media` instead of `template`.** Media is on the Unknown sources
  list, but `ACCESS_SURFACE` (the drawing surface) is granted only to navigation/template
  apps, so a navigator loses its map.
- **Adding the app to `AppValidation__allowed_package_list`.** It is a compiled default
  keyed on package, version code and signature (`jeu.a`), not something a user or a
  sideloaded app can append to.
- **Desktop-head-unit spoof.** Real only if the *head unit* can be made to claim Google's
  DHU identity (OpenAuto, AAWireless developer mode). A factory head unit cannot.

Without root, the only route left is Google Play installing the app itself.

## Options, cheapest first

| Option | Works for | Cost |
| --- | --- | --- |
| Play internal testing track | everyone, grid included; passes because Play installs it | Play Console, ongoing shipping |
| Head unit that identifies as Google's Desktop Head Unit | OpenAuto/AAWireless-style units only, phone untouched | receiver-side work |
| Root + hook (LSPosed) the Google-signed AA | keeps the certificate, so all four problems above vanish | root on GrapheneOS (Magisk/avbroot), re-patch per AA update |
| Root + bind-mount the patched APK over `base.apk` | same, no Xposed needed; PM keeps the Google SigningDetails | root, re-patch per AA update |
| Re-signed patched APK, no root | installs and starts, but loses the AA grants and projection | not usable today |

`persist.gmscompat_test_pkgs` does not help either: it needs a dev build and it only grants
the generic GmsCompat treatment, not `AndroidAutoHooks`.

## The certificate question

GrapheneOS does not verify Google's certificate for the *installer* — the
`SecurityException: Caller does not have same cert as new installer package
com.android.vending` from `pm set-installer` is stock AOSP (`PackageInstallerService`, bug
236687884, Nov 2022); GrapheneOS only inherits it. GrapheneOS does verify Google's
certificate for *package identity* (point 1 above), and that is what makes a patched
Android Auto a dead end without root.
