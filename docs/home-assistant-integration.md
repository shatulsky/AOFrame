# Home Assistant integration

The app's on-device [`LocalControlServer`](local-control-server-api.md)
(and, if you're running it, the [reference server](../server/)) are
plain JSON-over-HTTP — nothing HA-specific needed on the AOFrame side.
This doc covers wiring them into Home Assistant as sensors (read-only
stats) and actions (things HA can trigger).

Point HA at either the device directly (`http://<frame-ip>:8099`) or the
reference server's proxy (`http://<server-host>:8080`) — same JSON shapes
either way, see [`local-control-server-api.md`](local-control-server-api.md).

## Why YAML, not the UI config flow

HA's UI-based integration flow doesn't cover arbitrary custom REST
endpoints — use the YAML-based `rest:`/`rest_command:` integrations in
`configuration.yaml` instead, applied via a Home Assistant restart
(`docker compose restart homeassistant` if you're running HA in Docker).

**Only ever have one top-level `rest:` key in the whole file.** A second
`rest:` block added lower down doesn't merge with the first — it's a
duplicate YAML mapping key, and HA's loader (like plain PyYAML) silently
keeps only the *last* one, dropping every sensor defined under the first
block with no error. If you're adding a new resource, edit the existing
`rest:` list and add a new `resource:` entry as a sibling of the ones
already there, rather than appending a whole new `rest:` key.

## Sensors: frame status

```yaml
rest:
  - resource: http://<frame-ip>:8099/status
    scan_interval: 300
    sensor:
      - name: "Frame Asset Count"
        unique_id: frame_asset_count
        value_template: "{{ value_json.assetCount }}"
        state_class: measurement
      - name: "Frame Photo Count"
        unique_id: frame_photo_count
        value_template: "{{ value_json.photoCount }}"
        state_class: measurement
      - name: "Frame Video Count"
        unique_id: frame_video_count
        value_template: "{{ value_json.videoCount }}"
        state_class: measurement
      - name: "Frame Cache Size"
        unique_id: frame_cache_size
        value_template: "{{ (value_json.cacheBytes / 1048576) | round(1) }}"
        unit_of_measurement: "MB"
        state_class: measurement
      - name: "Frame Faces Found"
        unique_id: frame_faces_found
        value_template: "{{ value_json.facesFound }}"
        state_class: measurement
```

A `scan_interval` in the low minutes is plenty — this data doesn't change
fast enough to justify polling more often, and unlike a live sysfs read,
each poll is a real network round-trip to the device.

## Actions: wrap each `rest_command` in a `script`

A bare `rest_command` is **not an entity** — it can't be exposed to
Assist/voice, targeted by name, or shown as a tile on an area's
dashboard. Wrap each one in a matching `script` to get a real, callable
entity:

```yaml
# configuration.yaml
rest_command:
  frame_refresh_cache:
    url: "http://<frame-ip>:8099/action/refresh-cache"
    method: POST
  frame_reshuffle:
    url: "http://<frame-ip>:8099/action/reshuffle"
    method: POST
  frame_refresh_webcam:
    url: "http://<frame-ip>:8099/action/refresh-webcam"
    method: POST
```

```yaml
# scripts.yaml
frame_refresh_cache:
  alias: "Frame: Refresh Photos"
  icon: mdi:refresh
  sequence:
    - action: rest_command.frame_refresh_cache

frame_reshuffle:
  alias: "Frame: Reshuffle Slideshow"
  icon: mdi:shuffle
  sequence:
    - action: rest_command.frame_reshuffle

frame_refresh_webcam:
  alias: "Frame: Refresh Webcam"
  icon: mdi:webcam
  sequence:
    - action: rest_command.frame_refresh_webcam
```

Each resulting `script.frame_*` entity is callable the normal HA ways —
a dashboard tile, an automation action, Assist (once exposed), or
directly via the REST API:

```
POST http://<ha-host>:8123/api/services/script/frame_reshuffle
Authorization: Bearer <long-lived access token>
```

No request body needed for any of the actions above.

## Night mode: sleep/wake now

`night-mode`'s own scheduled sleep/wake only fires on the configured
timer — for an immediate manual sleep/wake button, use the same
`KEYCODE_SLEEP`/`KEYCODE_WAKEUP` mechanism the app uses internally, via
root ADB from whatever host you run admin scripts on (this isn't exposed
as its own `LocalControlServer` endpoint — it operates at the Android
input-event level, below the app):

```
adb -s <frame-ip>:5555 shell su -c "input keyevent KEYCODE_SLEEP"
adb -s <frame-ip>:5555 shell su -c "input keyevent KEYCODE_WAKEUP"
```

**Use `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` specifically, not `KEYCODE_POWER`.**
The first two are idempotent — safe to send "sleep" while already asleep.
`KEYCODE_POWER` *toggles* power state, so sending it twice (or once when
you expected the opposite state) undoes itself. If you're wiring this
into HA, wrap the `adb` call in whatever local script-runner integration
you use to call host-side scripts from an automation — HA itself has no
built-in ADB client.

If you're calling this from a presence-based or other conditional
automation, check `/status`'s `screenAwake` field first (see
[`local-control-server-api.md`](local-control-server-api.md)) and skip
the keyevent when the device is already in the target state — harmless
either way since the keyevents are idempotent, but it saves a real ADB
round-trip your automation doesn't need.

## Grouping into an Area

Assign each new `sensor.frame_*`/`script.frame_*` entity to its own Area
(e.g. "Frame") so they show up together on one dashboard page — via the
UI (Settings → Areas → your area → Add entity) or the WebSocket API's
`config/entity_registry/update` for scripted setups. An entity with no
Area assigned won't appear on that Area's own dashboard page even though
it works fine everywhere else — easy to miss on a first pass.
