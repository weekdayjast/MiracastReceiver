# Experimental Google Cast media receiver

The foreground receiver service now also advertises `_googlecast._tcp.local`
and accepts Cast V2 over TLS on port 8009. It shares the device name shown on
the main screen. No root is required for this protocol.

## Supported scope

- Default Media Receiver application (`CC1AD845`). Other applications return
  `APP_UNAVAILABLE` / `LAUNCH_ERROR` rather than claiming compatibility.
- HTTP/HTTPS media URLs playable by the existing Media3 ExoPlayer backend,
  including progressive audio/video and HLS. Initial position and `autoplay`
  are preserved.
- `GET_STATUS`, `LAUNCH`, receiver/media `STOP`, `PLAY`, `PAUSE`, `SEEK`, volume,
  and mute. Status updates include buffering, position, duration, pause,
  completion (`FINISHED`), local cancellation and playback errors (`ERROR`).
- Multiple control connections can bind to one session. Closing its last
  transport stops playback. Old media IDs and old player callbacks cannot
  control or overwrite a subsequent load.
- Exiting the player locally ends the Cast application and notifies all bound
  senders with a final media status followed by an empty `RECEIVER_STATUS`.
  Natural completion and sender media `STOP` keep the application available
  for another item. A stale Activity exit cannot close a newer load.
- Service shutdown closes all sockets and unregisters mDNS. A five-second
  network check rebinds and re-advertises after the local IPv4 address changes;
  short-lived TLS identities rotate daily. Port conflicts are logged and
  retried without stopping AirPlay, DLNA or Miracast.

This is media URL casting, not Chrome screen/tab mirroring. Cast Streaming,
YouTube MDX, application-specific receivers, queues, subtitles, DASH and DRM
configuration are not implemented. Codec support depends on ExoPlayer and the
device. LAN access permission and the existing background-player/overlay
permission requirements still apply.

The receiver generates a **self-signed device identity**. It cannot satisfy
Google's device-certificate trust chain. Original YouTube / Google Home and
other senders requiring that chain are not supported. This feature does not
extract or embed another device's credentials. Discoverability alone does not
mean a sender will accept or play media on this receiver.

## Implementation

`cast/CastWire.kt` implements bounded length-prefixed protobuf frames;
`CastIdentity.kt` creates an RSA TLS identity and signs the device-auth challenge
over `sender_nonce || TLS_certificate`. `CastSession.kt` owns the protocol state.
`GoogleCastReceiver.kt` handles sockets, heartbeat and network recovery.
`CastMdnsAdvertiser.kt` advertises IPv4 records on the listener's LAN interface,
using `CastMdnsPacket.kt` to emit `PTR, TXT, SRV, A`. libmicrodns reverses the
wire records, so VLC then sees `A, SRV, TXT, PTR` and reads both the reachable
IPv4 address and the name/video capabilities.
`CastPlayerBridge.kt` queues main-thread player commands until the Activity is
ready. Player intents, commands and status broadcasts carry a unique token for
each media load. Cast status is separate from the DLNA renderer's state.

The Kotlin implementation references the protocol definitions and flows in
[openchromecast](https://github.com/yukarikaname/openchromecast), revision
`0cc510b2e0fd8c484029ccf8ab08f527d6ad752c`. Its Rust executable, desktop tray,
mpv and VLC backends are not bundled. The upstream MIT copyright and license
are retained at `app/src/main/assets/licenses/openchromecast.txt` and included
in APK assets.

## VLC troubleshooting

Android NSD on the test device emitted `PTR, TXT, A, AAAA, SRV`. VLC's
[microdns parser](https://github.com/videolan/vlc/blob/master/modules/services_discovery/microdns.c)
only parses Cast TXT fields after encountering an SRV record. Its
[libmicrodns decoder](https://github.com/videolabs/libmicrodns/blob/master/src/mdns.c)
prepends entries to a linked list, reversing their wire order. With Android's
record order, VLC also selects the link-local AAAA address before the IPv4 A
record, without an interface scope. The receiver now sends `PTR, TXT, SRV, A`
together, with `ca=5` (video and audio), and advertises only the LAN IPv4 it
listens on. Discovery tests reproduce the reverse traversal and capability
parsing, and a LAN packet capture verifies the order
from the real device. Restart VLC after updating: its renderer list may retain
the previously discovered audio-only capabilities.

SMB credentials belong to the VLC sender. In the normal Cast flow VLC reads the
SMB source and serves an HTTP media URL to the receiver; this app does not log
in to SMB. A VLC error naming `smb://...` does not by itself establish whether
SMB login, sender transcoding, Cast connection or receiver decoding failed.
Check for `Cast client connected`, `Cast TLS established`, and `Cast request`
in receiver logcat. Diagnostics record message types and MIME types without
logging sender credentials or request payloads. No incoming TCP connection
means playback has not reached this receiver. An original `smb://` LOAD URL is
unsupported, and RMVB transcoding still depends on the sender's build.

The complete reverse traversal matters: an earlier SRV-before-TXT wire order
allowed IPv4 connections but made VLC omit video. With the final order above,
the real VLC 3.7.2 sender advertises the correct renderer name and delivers both
video and audio. Verified on the connected Huawei LRA-AL00 and Xiaomi MIX 2S:
H.264/AAC MP4 playback, plus the original SMB RMVB after VLC converts RV40/Cook
to H.264/MP3. The receiver does not directly decode RMVB or access SMB credentials.
MIME `audio/x-matroska` alone is not evidence of missing video; actual track
inventory is. `onTracksChanged` diagnostics record track types, codecs, selection
and device support, without media data.

This VLC build sends `"autoplay":"false"` as a string, rather than a JSON
boolean. Only boolean values are interpreted as explicit autoplay settings;
missing or incorrectly typed values default to automatic playback, consistent
with the reference receiver. Standard `"autoplay": false` remains paused.

For local exit, sending media `IDLE` alone is insufficient: VLC can keep the
receiver application selected and display an active cast. The player now marks
an explicit session-ended event, and the session queues the final media status
before announcing `applications: []` on the receiver namespace. The transport
delivers these messages even after clearing active media, in order before any
subsequent request/reply. Real-device logs confirm VLC changes from Playing to
Ready to Stopped and exits its playback page after Back on the MIX 2S.

## Verification

From `MiracastReceiver/`:

```shell
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Protocol tests cover DNS-SD record ordering, malformed/compressed queries,
unicast responses, goodbye TTLs, protobuf framing and malformed input, nonce/certificate
binding, the media command flow, unsupported applications/URLs, stale sessions,
multi-connection teardown and mute/volume behavior.
They also cover local exit notification ordering and delivery to every bound
client, duplicate/stale player exits, relaunch after exit, and preserving the
application after normal completion or sender media stop.

For a real device, install the Debug APK, start the app and use the following
from the repository root. The PC and device must share a LAN. `adb reverse`
provides temporary local test media over USB; the Cast connection still uses
the LAN IP. The scripts stop the test session when they finish.

```shell
adb -s SERIAL reverse tcp:18089 tcp:18089
python tools/cast_smoke_test.py DEVICE_LAN_IP

# Also press Back on the receiver, assert shutdown notification, and recast:
python tools/cast_smoke_test.py DEVICE_LAN_IP --receiver-serial SERIAL --adb PATH_TO_ADB

# Optional third-party sender and mDNS discovery test:
pip install pychromecast
python tools/cast_pychromecast_test.py "DEVICE_NAME" --video-file sample.mp4

adb -s SERIAL reverse --remove tcp:18089
```

The standard-library smoke client generates quiet WAV audio, then asserts TLS,
device-auth response, autoplay=false, initial time, progress, pause, seek,
volume/mute, actual completion, actual HTTP playback error and stop. The
pychromecast test independently discovers the device by mDNS and verifies
media playback and remote controls. These tests do not establish compatibility
with Google's official sender applications.

Verified on 2026-11-03 using the USB-connected Xiaomi MIX 2S: the independent
client passed the audio/control/completion/error flow, and pychromecast 14.1.1
discovered the device over mDNS and played the repository's demo MP4 with
pause/seek/play/volume/stop controls. Initial discovery may take several seconds;
pychromecast also attempts an optional setup-info query on an unknown model
before falling back to its Chromecast control path. A setup HTTP server is not
implemented. HLS and other sender apps still require compatibility testing.

The Debug APK builds and unit tests pass. Full Android Lint currently reports
existing player/layout issues, including `MissingSuperCall`, Media3 opt-in
annotations and `UseAppTint`.
