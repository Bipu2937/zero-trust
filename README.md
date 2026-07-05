# Zero-Trust Android Media Vault

A hyper-secure media vault (images + videos) for Android, built with
React Native (TypeScript) and custom Kotlin native modules. Designed for a
hostile environment: assume the JS supply chain is compromised, assume
spyware is watching the screen, assume a keylogger is in the IME — and be
safe anyway.

## Security model at a glance

| Threat | Defense |
|---|---|
| Screenshots / screen recording / MediaProjection spyware | `FLAG_SECURE` on the whole window **plus** media rendered on a DRM-style secure `SurfaceView` (`setSecure(true)`) — captures come back black |
| System-keyboard keyloggers | Fully custom in-app PIN pad drawn on a native canvas; the Android IME is never invoked; digit layout re-shuffles every attempt |
| Malicious npm packages exfiltrating data | **No `INTERNET` permission** in the release manifest — network I/O is physically impossible |
| Compromised JS bundle reading secrets | Bridge isolation: keys, PINs and media bytes exist only in Kotlin; JS sends commands ("show item X") and receives metadata/events |
| Key theft from a rooted / dumped device | AES-256-GCM keys wrapped by a non-exportable Android Keystore key (StrongBox SE when available, TEE otherwise), bound to biometric/device-credential auth |
| App switcher snooping | `android:excludeFromRecents="true"` — the app never appears in Recents |
| "I left it open" | Ruthless instant lock: `onPause`/focus-loss zeroes key material with **no grace period** |
| Stored-file tampering | Chunked AES-GCM with per-chunk AAD (file magic + chunk index): reordering, truncation or bit-flips abort decryption |
| Offline PIN brute force | PIN digest is an HMAC under a Keystore key — the stored digest is useless off-device; on-device guessing gets exponential lockout |
| Backup/exfil via ADB or cloud | `allowBackup=false` + full `data_extraction_rules` exclusion |

## Architecture

```
┌─────────────────────────── JavaScript (React Native) ───────────────────────────┐
│  App.tsx ── LockScreen ── GalleryScreen ── ViewerScreen                          │
│     commands + metadata only: "unlock", "import", "show item 3", "lock"          │
└───────────────┬──────────────────────────────────────────────────────────────────┘
                │  RN bridge (NO keys, NO PIN digits, NO media bytes — ever)
┌───────────────▼──────────────────────────── Kotlin ──────────────────────────────┐
│  VaultModule        command API: import/export (SAF), list, delete, lock, wipe   │
│  PinPadView         native-rendered keypad → PIN never enters JS                 │
│  SecureMediaView    secure SurfaceView; decrypt + render entirely native         │
│  SessionManager     holds the DEK in RAM while unlocked; zeroes it on lock       │
│  VaultCipher        chunked AES-256-GCM container (ZTV1), O(1) seek              │
│  EncryptedMediaDataSource   on-the-fly decrypting source for MediaPlayer         │
│  KeystoreManager    KEK + PIN-HMAC keys in StrongBox/TEE, auth-bound             │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Key hierarchy

```
Android Keystore (StrongBox / TEE, non-exportable, user-auth-bound)
 └─ KEK  (AES-256-GCM)          — unwraps the DEK once per unlock session
     └─ DEK (random 32 B)       — on disk only KEK-wrapped (dek.blob);
                                   in RAM only while unlocked; zeroed on lock
         ├─ media/<uuid>.ztv    — encrypted media containers
         └─ index.ztv           — encrypted metadata index (names/sizes are
                                   sensitive too)
 └─ PIN-HMAC key (HMAC-SHA256)  — keyed hash of the PIN (pin.dat = salt‖mac)
```

### ZTV1 container format

```
[4B "ZTV1"][4B IV prefix][8B plaintext size][chunk 0][chunk 1]…
chunk  = GCM(64 KiB plaintext) ‖ 16B tag
IV     = prefix(4B) ‖ chunkIndex(8B)          (unique, never reused)
AAD    = "ZTV1" ‖ chunkIndex                  (anti-reorder/splice)
```

Fixed chunks give O(1) random access — that's what makes **seekable,
authenticated video playback with zero plaintext on disk** possible:
`MediaPlayer` pulls byte ranges from `EncryptedMediaDataSource`, which
decrypts only the covering chunks, straight onto the secure surface.

### Unlock flow

1. User enters PIN on the **native** pad (shuffled layout, no IME).
2. Kotlin verifies `HMAC(KeystoreKey, salt‖pin)` in constant time;
   failures hit exponential lockout (5 free, then 30 s → 30 min).
3. If the KEK is auth-bound (device has a lock screen), Keystore demands
   fresh system auth → `BiometricPrompt` (Class-3 biometric or device
   credential).
4. Keystore unwraps the DEK; it lives only in `SessionManager` memory.
5. Any focus loss → DEK zeroed → back to step 1. No grace period.

### Import / export (Secure Folder parity)

* **Import**: SAF `ACTION_OPEN_DOCUMENT` (multi-select, permission-less) →
  streamed chunk-by-chunk through AES-GCM into
  `/data/data/com.zerotrustvault/files/vault/` → optional best-effort
  secure-delete of originals (`DocumentsContract.deleteDocument` +
  overwrite where the provider allows it).
* **Export**: SAF `ACTION_CREATE_DOCUMENT` → user picks the destination →
  streamed decrypt into it. Requires an unlocked vault.
* Opening the system picker backgrounds the app, which **intentionally
  triggers the instant lock**; queued work resumes automatically after
  re-unlock.

## Building

Prereqs: Node ≥ 18, JDK 17+, Android SDK (API 34).

```bash
npm install

# dev (Metro needs INTERNET, which only the debug manifest has)
npm run android

# release (air-gapped: no INTERNET permission at all)
cd android && ./gradlew assembleRelease
```

> `android/app/debug.keystore` is a throwaway debug key. Configure a real
> release signing config before shipping; never ship debug-signed builds.

## Verifying the claims on a device

```bash
# 1. Air-gap: release APK must list NO permissions
aapt dump permissions android/app/build/outputs/apk/release/app-release.apk

# 2. Un-screenshotable: open a photo, press Power+VolDown → OS refuses,
#    or the saved capture shows black where the media is. Recents preview
#    doesn't exist (excludeFromRecents).

# 3. Instant lock: open the vault, swipe to home for <1s, return →
#    PIN pad. Turn the screen off → PIN pad.

# 4. No plaintext at rest (rooted test device):
adb shell ls /data/data/com.zerotrustvault/files/vault/media   # only *.ztv
adb shell head -c 4 /data/data/com.zerotrustvault/files/vault/media/<id>.ztv
# → "ZTV1", followed by ciphertext
```

## Honest limitations (read before trusting your life to it)

* **Memory scrubbing is best-effort.** Every buffer we control is zeroed
  immediately after use, but ART's GC can move arrays and JCA providers
  keep internal key copies. This shrinks the exposure window to
  milliseconds; it cannot make it zero on a fully compromised kernel.
* **A compromised OS/kernel sees everything eventually.** `FLAG_SECURE`
  and secure surfaces defeat user-space capture (including accessibility
  and MediaProjection malware), not a malicious display driver.
* **Secure deletion on flash is probabilistic.** Wear-leveling may retain
  old blocks. Mitigation: files only ever existed as ciphertext under a
  hardware key, and panic-wipe destroys the Keystore keys — recovered
  blocks stay ciphertext forever.
* **Devices without a lock screen** can't have auth-bound keys; the app
  still encrypts (gated by the PIN + Keystore HMAC) and warns the user.
* **Deleting originals** after import depends on the source document
  provider; some gallery providers require manual deletion.

## Project layout

```
src/
  App.tsx                     root state machine (lock → gallery → viewer)
  theme.ts                    hand-rolled tokens; zero UI libraries
  native/VaultModule.ts       typed command bridge
  native/SecurePinPad.tsx     shim over native ZTVPinPad
  native/SecureMediaView.tsx  shim over native ZTVSecureMediaView
  security/useInstantLock.ts  JS mirror of the native instant lock
  screens/{Lock,Gallery,Viewer}Screen.tsx
android/app/src/main/java/com/zerotrustvault/
  MainActivity.kt             FLAG_SECURE + instant lock hooks
  MainApplication.kt
  vault/VaultModule.kt        SAF import/export, list, lock, wipe
  vault/VaultPackage.kt
  vault/SessionManager.kt     DEK custody + BiometricPrompt + zeroing
  vault/VaultStore.kt         encrypted index + shredding delete
  vault/crypto/KeystoreManager.kt   StrongBox/TEE keys
  vault/crypto/VaultCipher.kt       ZTV1 chunked AES-256-GCM
  vault/crypto/PinManager.kt        Keystore-HMAC PIN + lockout
  vault/crypto/MemoryUtil.kt        buffer scrubbing
  vault/media/EncryptedMediaDataSource.kt
  vault/ui/PinPadView(.Manager).kt
  vault/ui/SecureMediaView(.Manager).kt
```
