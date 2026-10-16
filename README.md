# Shield Media

Home Assistant custom integration that exposes the media playback of an Android TV device (tested target: NVIDIA Shield) as a real `media_player` entity, with **local push** state and playback control.

It talks to the companion Android app, a fork of [MediaSession2MQTT](https://github.com/cbeyls/MediaSession2MQTT) which serves a local WebSocket API. No MQTT broker and no Home Assistant token on the TV are needed.

## Features

- States `playing`, `paused`, `idle`; title, duration, position (with progress bar extrapolation) and application name.
- Controls: play, pause, stop, seek, next, previous. Features are enabled dynamically from what the running application declares.
- Entity becomes `unavailable` when the Shield is asleep or unreachable and recovers by itself (reconnection with backoff).
- Authenticated by a pairing token specific to the app. Optional mDNS discovery (`_shield-media._tcp`).

## Repository layout

- `custom_components/shield_media`: the Home Assistant integration (this is what HACS installs).
- `android/`: the companion Android app (fork of MediaSession2MQTT with the local API). Build with `./gradlew assembleDebug` using JDK 21+.

## Installation

### HACS (custom repository)

1. HACS → ⋮ → *Custom repositories* → add this repository, category *Integration*.
2. Install **Shield Media** and restart Home Assistant.

### Manual

Copy `custom_components/shield_media` to your `config/custom_components/` and restart Home Assistant.

## Setup

1. On the Shield, install the companion app and grant it notification access (see the app README).
2. In the app settings, enable **Enable local API** and note the **pairing token**.
3. In Home Assistant: *Settings → Devices & services → Add integration → Shield Media*, then enter the Shield IP address, the port (default `8765`) and the token.

The traffic is unencrypted WebSocket on the local network: do not expose the port outside a trusted network.

## Protocol

WebSocket `ws://<host>:<port>/ws` with header `Authorization: Bearer <token>`; JSON text frames.

- App → HA (on connection, then on each change):
  `{"type":"state","state":"playing|paused|idle","app_id":"...","title":"..."|null,"duration_ms":123|null,"position_ms":123|null,"position_updated_at":"2026-10-16T13:00:00.000Z","supported_actions":["play","pause","stop","seek","next","previous"]}`
- HA → app: `{"id":1,"type":"command","command":"play|pause|play_pause|stop|next|previous|seek","position_ms":120000}`
- App → HA: `{"id":1,"type":"result","ok":true}` or `{"id":1,"type":"result","ok":false,"error":"no_session|unsupported|..."}`

## Known limitations

- Some applications report no title (e.g. Netflix) or an unreliable position (e.g. Prime Video); the integration shows what the application declares.
- A command is only available if the application declares the matching action in its playback state.
