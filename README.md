# LowLatencyMirror

Two Android apps in one Android Studio project.

- `sender`   -> install on the PHONE. Captures the screen straight into the hardware
  H.264 encoder (low-latency settings) and streams it over TCP.
- `receiver` -> install on the ANDROID TV. Listens on port 5600, decodes with the TV's
  hardware decoder in low-latency mode and renders every frame immediately.

See the chat reply for step-by-step setup. Requirements: Android 11+ (minSdk 30),
phone and TV on the same router (prefer phone on 5 GHz, TV on Ethernet).
No audio is streamed (video only).
