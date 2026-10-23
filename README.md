# GPS Relay

GPS Relay shares one Android device's GPS location and navigation requests with other Android devices on its Wi-Fi hotspot.

A typical setup is a device with GPS and a hotspot (the **server**) plus a device without a data connection (the **client**) that does the navigating. When you tap an address or a "Navigate" link on the server, the destination opens in the client's navigation app, and the client navigates using the server's GPS. The server stays free for other things.

The same app does both jobs. You pick the mode on each device.

## How it works

```
 Server (GPS + hotspot)                         Client (any Android device with Wi-Fi)
 ┌───────────────────────────┐   Wi-Fi/TCP    ┌──────────────────────────────────┐
 │ GPS ───────────────────────┼──── fix ─────▶ │ mock location provider ──▶ apps  │
 │ geo:/Maps/Waze link ──▶    │                │                                  │
 │   "Send to client"  ───────┼──── nav ─────▶ │ opens Google Maps / Waze / …     │
 │                     ◀──────┼──── ack ────── │                                  │
 └───────────────────────────┘    port 47474   └──────────────────────────────────┘
```

- **Server mode**
  - Reads the device's GPS and serves it on TCP port 47474.
  - Registers as a maps app, so `geo:`, `google.navigation:`, `waze:`, Google Maps and Waze links can be sent to the client. "Share → GPS Relay" works too.
  - Expands short links such as `maps.app.goo.gl` and looks up coordinates for addresses before sending them.
  - Holds a destination until a client connects and acknowledges it.
- **Client mode**
  - Connects to the hotspot gateway by default, which is the server. You can also set a manual `IP:port`.
  - Provides the relayed location to every app as the system's mock location.
  - If no location arrives for 10 seconds, for example because the server is out of range, other apps see GPS as off rather than a frozen position. It comes back with the next location.
  - Opens each received destination in the navigation app you chose.
- **Both modes**
  - A live map shows the current position, accuracy, heading, a trail of recent positions and the latest destination. It uses OpenStreetMap, so it needs no Google services.
  - A log lists every link sent or received, with its delivery status.

The client still gets internet through the hotspot, so online navigation apps such as Waze work normally.

## Requirements

- **Both devices:** Android 5.0 (API 21) or later.
- **Server:** GPS and the ability to run a Wi-Fi hotspot. The client and server can also share any other Wi-Fi network if you set the client's server address by hand.
- **Client:** Wi-Fi, plus a navigation app (Google Maps, Waze, OsmAnd, Organic Maps, or anything that handles `geo:` links).

## Install

Download the APK from the repository's **Releases** page, or build it yourself (see [Building](#building)). Copy it to both devices and open it. Or install it over USB:

```sh
adb install app/build/outputs/apk/release/app-release.apk
```

## Setup

### Server

1. Open GPS Relay, choose **Server**, and tap **Start server**.
   - Read the location notice and allow location and notifications.
2. Turn on the device's Wi-Fi hotspot.
3. Teach the device to send destinations to GPS Relay:
   - **`geo:` / navigation links:** the first time an app opens one, choose **Send to client (GPS Relay)** and tap **Always**.
   - **From Google Maps or any other app:** use **Share → GPS Relay**.
   - **Google Maps web links:** Google Maps owns `maps.google.com` links by default. To route them to GPS Relay, turn off **Open supported links** for Google Maps. The Status tab has a button that opens that setting.
4. Optional: check **Also open destinations on this device** in Settings if you also want them to open on the server.

To test without the client, type an address or `lat, lon` into **Send a destination** on the Status tab.

### Client

1. Connect the device to the server's hotspot.
2. Make GPS Relay the mock location app:
   1. Turn on Developer options: tap **Build number** 7 times under Settings → About.
   2. Go to **Settings → System → Developer options → Select mock location app** and choose **GPS Relay**.
3. Open GPS Relay, choose **Client**, and tap **Start client**. Allow location and notifications.
4. In **Settings → Navigation app**, pick the app that should open destinations.
5. Work through the **Setup checklist** on the Status tab:
   - **Display over other apps** (Android 10+): lets GPS Relay open the navigation app by itself. Without it, a notification appears that you tap.
   - **Full-screen navigation prompts** (Android 14+): makes that notification pop up full-screen.
   - **Not battery-optimized:** keeps the connection alive with the screen off.

The client should show **Connected to <gateway>:47474**, and other apps on the client should now see the server's location.

### Optional settings

| Setting | Where | Notes |
|---|---|---|
| Port | Both | Default `47474`. Must match on both devices. |
| Pairing PIN | Both | If set on the server, clients must use the same PIN. |
| Server address | Client | Leave blank to use the Wi-Fi gateway, which is the server when connected to its hotspot. Otherwise use `host` or `host:port`. |
| Start when the device boots | Both | Android 10 and earlier start automatically. Android 11+ shows a one-tap "Start" notification, because Android blocks location access for services started at boot. |

Port and PIN changes apply the next time the relay starts.

## NMEA output

The server can also stream standard NMEA 0183 sentences, so a computer (or any program that reads NMEA) can use the server as a GPS receiver. It's off by default. Turn it on in **Settings → NMEA output** on the server; changes take effect right away, even while the server is running.

| Output | Setting | What you get |
|---|---|---|
| TCP | **TCP server**, port (default `10110`) | A plain text stream to every client that connects. No handshake and no PIN. |
| UDP | **UDP datagrams**, `host[:port]` list | The same text as datagrams, sent to each listed address. The port defaults to `10110`. |
| pty | `tools/nmea-pty.sh` on the computer | A serial-style device (pseudo-terminal) fed from the TCP or UDP output by `socat`. |

The sentences are the GPS receiver's own on Android 7.0+. On older Android, or if the receiver stops providing them, the server builds `GGA`, `RMC` and `VTG` from each fix instead. Those built sentences estimate HDOP as the reported accuracy ÷ 5 m, give altitude above the WGS84 ellipsoid, and leave the geoid separation empty.

There is no PIN on NMEA output. Anything that can reach the port, including any device on the server's hotspot, can read the server's location, so leave it off when you don't need it.

### Over USB

1. **USB tethering:** turn on USB tethering on the server (Settings → Network → Hotspot & tethering). The computer gets a network link to the phone. The phone's address on that link varies by device, often `192.168.42.129`. Find it with `ip route` or `ipconfig`.
   - TCP: connect to the phone's address, for example `nc 192.168.42.129 10110`.
   - UDP: set **Send to** on the server to the computer's address on that link, for example `192.168.42.100:10110`, then listen with `nc -u -l 10110`.
2. **USB debugging, without tethering (TCP only):** run `adb forward tcp:10110 tcp:10110`, then connect to `localhost:10110`. `adb` can't forward UDP.

### As a pty with socat

Install `socat`, then run the helper on the computer:

```sh
tools/nmea-pty.sh tcp 192.168.42.129     # read the phone's TCP stream
tools/nmea-pty.sh tcp localhost          # after `adb forward`
tools/nmea-pty.sh udp 10110              # receive the phone's UDP datagrams
```

It creates `/tmp/gpsrelay-nmea` (change it with `-l`), a symlink to a pty that stays in place while the connection drops and comes back. Point GPS software at it, for example `gpsd -N -n /tmp/gpsrelay-nmea` or `cat /tmp/gpsrelay-nmea`. The `socat` equivalent, if you'd rather run it yourself:

```sh
socat -d PTY,link=/tmp/gpsrelay-nmea,raw,echo=0 TCP:192.168.42.129:10110
```

## Automation (Tasker)

Automation apps such as Tasker can start, stop and configure GPS Relay. It's off by default. Turn on **Settings → Automation → Allow automation apps**. That shows a key; every action must include it, so other apps on the device can't start the relay or redirect NMEA output.

In Tasker, use **System → Send Intent** with:

- **Action:** one of the actions below
- **Extra:** `key:<your key>` (add more extras in the other Extra fields)
- **Package:** `net.internectual.gpsrelay` (`net.internectual.gpsrelay.debug` for debug builds)
- **Class:** `net.internectual.gpsrelay.automation.ControlActivity`
- **Target:** Activity

It has to be an activity, not a broadcast, because Android only lets an app start using location while it has a visible activity. The activity is invisible and closes immediately.

| Action (`net.internectual.gpsrelay.action.…`) | Does |
|---|---|
| `START_SERVER` | Start the server (stops the client if it's running) |
| `START_CLIENT` | Start the client (stops the server if it's running) |
| `STOP` | Stop whichever is running |
| `STOP_SERVER` / `STOP_CLIENT` | Stop just that one |
| `RECONNECT` | Client: drop the connection and reconnect now |
| `OPEN` | Open the app. Optional extra `tab:status`, `map`, `log` or `settings` |
| `CLOSE` | Close the app's screen; the relay keeps running |
| `QUIT` | Stop the relay and close the app's screen |
| `SET` | Change settings, from the extras below |
| `REQUEST_STATE` | Send the state broadcast now |

If a start needs the location notice or a permission that hasn't been granted yet, the app opens to ask, then starts.

`SET` extras (any combination; if one is invalid, nothing changes and a toast says which):

| Extra | Value |
|---|---|
| `port` | Relay port, 1024–65535 (applies the next time the relay starts) |
| `pin` | Pairing PIN, up to 16 characters (next start) |
| `server_address` | Client: `host[:port]`, empty for the Wi-Fi gateway |
| `nav_app` | Client: navigation app package, e.g. `com.waze`; empty for the system default |
| `also_open_locally` | `true` / `false` |
| `start_on_boot` | `true` / `false` |
| `nmea_tcp` | `true` / `false` (applies immediately) |
| `nmea_tcp_port` | 1024–65535 |
| `nmea_udp` | `true` / `false` |
| `nmea_udp_targets` | `host[:port]`, comma-separated |

For example, to start sending NMEA to a laptop: Action `net.internectual.gpsrelay.action.SET`, extras `key:<key>`, `nmea_udp:true`, `nmea_udp_targets:192.168.1.50:10110`.

### State broadcast

While automation is on, the app broadcasts `net.internectual.gpsrelay.event.STATE` whenever its state changes, and when asked with `REQUEST_STATE`. In Tasker, catch it with an **Event → System → Intent Received** profile; the extras arrive as local variables:

| Extra | Value |
|---|---|
| `%mode` | `server` or `client` (the side last selected or started) |
| `%server_running` / `%client_running` | `true` / `false` |
| `%client_state` | `stopped`, `searching`, `connecting`, `connected` or `error` |
| `%clients` | Clients connected to the server |
| `%nmea_clients` | TCP clients reading NMEA |

It never includes location.

### Sending a destination

Destinations don't need the key: send the same intents a maps app would get, e.g. **Send Intent** with Action `android.intent.action.VIEW`, Data `geo:0,0?q=1600 Amphitheatre Pkwy, Mountain View`, Package `net.internectual.gpsrelay`, Target Activity.

## Troubleshooting

- **The client says "Not connected to a Wi-Fi hotspot".** Make sure the client is on the server's Wi-Fi, or set the server address by hand. The server's IP addresses are listed on its Status tab.
- **The client keeps reconnecting.**
  - Check that the server is running and that the port matches on both devices.
  - Some hotspots isolate their clients from each other. That doesn't affect the server, because the server is the gateway.
- **"Select GPS Relay as the mock location app".** Do client setup step 2.
- **Navigation apps ignore the location.** Some apps check for mock locations. Google Maps and Waze accept them.
- **Google Maps links skip GPS Relay.** Use Share → GPS Relay, or turn off "Open supported links" for Google Maps (server setup step 3).
- **No NMEA data.** Check **Settings → NMEA output**. Then run `nc <server-ip> 10110` from the computer. It should print sentences starting with `$G`. No output means no fix yet, or the wrong address or port.
- **Watch the raw data stream.** From any computer on the hotspot, run `nc <server-ip> 47474`, then type `{"t":"hello","version":1,"role":"client"}` and press Enter. Fix messages follow.

## Protocol

The protocol is newline-delimited JSON over TCP. Each message has a type field `t`:

| `t` | Direction | Fields |
|---|---|---|
| `hello` | both | `version`, `role`, `token` (PIN, optional). The client sends it first. |
| `fix` | server → client | `lat`, `lon`, `time`, plus optional `alt`, `acc`, `speed`, `bearing`, `sats` |
| `nav` | server → client | `id`, `raw`, `mode`, plus optional `lat`, `lon`, `query`, `label`, `sourceApp` |
| `ack` | client → server | `id`, `status` (e.g. `launched in Waze`) |
| `ping` / `pong` | client ↔ server | sent every 5 s |
| `error` | server → client | `reason` (e.g. `bad_pin`) |

When a client connects, the server sends it the latest fix and any destination that no client has acknowledged yet. Unacknowledged destinations expire after 2 hours.

## Building

You need JDK 17 or 21 (Gradle doesn't run on newer JDKs yet) and the Android SDK with platform 35 and build-tools 35.0.0.

```sh
# Point Gradle at the JDK if it isn't your default, e.g. in ~/.gradle/gradle.properties:
#   org.gradle.java.home=/path/to/jdk-21
# Tell Gradle where the SDK is:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `net.internectual.gpsrelay.debug`, so it can sit alongside the release build.

### Release builds

Release signing reads `keystore.properties` in the project root. The file is git-ignored; never commit it or the keystore.

```properties
storeFile=/home/you/keystores/gpsrelay-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

To create a keystore:

```sh
keytool -genkeypair -v -keystore ~/keystores/gpsrelay-upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then build:

```sh
./gradlew assembleRelease   # signed APK: app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease     # Play Store bundle: app/build/outputs/bundle/release/app-release.aab
```

Back up the keystore and its passwords. You need them to sign every future update.

### Publishing a release

GitHub Actions builds and publishes releases (`.github/workflows/release.yml`). Push a version tag:

```sh
git tag v0.2.0
git push origin v0.2.0
```

The workflow runs the unit tests, then builds and signs the APK and AAB. It attaches both to a new GitHub release with generated notes and a `SHA256SUMS` file. Tags with a suffix, such as `v0.2.0-beta.1`, are published as pre-releases.

The tag sets the app version: `versionName` is the tag without the `v`, and `versionCode` is `major × 1,000,000 + minor × 1,000 + patch`.

Signing uses these repository secrets: `KEYSTORE_BASE64` (the keystore file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Privacy

Location and destinations only travel between your own devices over the local network. GPS Relay has no servers and no analytics.

NMEA output is off unless you turn it on, and it only sends to the devices that connect to it or that you list.

The server only accepts relay and NMEA connections from local addresses: the hotspot and other private networks (10.x, 172.16–31.x, 192.168.x, 100.64.x, link-local, IPv6 `fd…`/`fe80…`) and the device itself. If the device has a public IPv6 address on mobile data, the internet can't connect to it.

Android backups include the settings but not the pairing PIN, the automation key or the log of destinations. After restoring to a new device, set the PIN again and turn automation back on, which creates a new key.

The only outside requests are:
- map tiles from OpenStreetMap;
- on the server, expanding short map links;
- on the server, Android's built-in geocoder, to turn addresses into coordinates.

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
