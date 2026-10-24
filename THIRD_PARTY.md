# Third-party components

- **IBM Plex Mono, IBM Plex Sans** — © IBM Corp., [SIL Open Font License 1.1](scripts/OFL-IBM-Plex.txt). Files in `app/src/main/res/font/`.
- **Winamp EQ presets** (17 built-ins in `audio/EqPresets.kt`) — values from [webamp](https://github.com/captbaritone/webamp) `presets/builtin.json`, MIT.
- **AndroidX Media3 (ExoPlayer, Session)**, **Jetpack Compose**, **OkHttp**, **kotlinx.serialization**, **Coil** — Apache License 2.0, fetched by Gradle.
- **QR Code generator** (`io.nayuki:qrcodegen`, the QR on the jam screen) — © Project Nayuki, MIT, fetched by Gradle.
- Yandex Music API constants (public OAuth client, download-link signing salt) — the same ones the desktop QiYaa and Yaamp use.
- **librespot** — https://github.com/librespot-org/librespot — MIT License, Copyright (c) 2015 Paul Lietar and the librespot contributors. Spotify's client protocol in `spotify/protocol/` (the access point handshake and session, client-token and login5, extended-metadata, audio keys, the file decryption and its header) follows librespot's code through the desktop QiYaa's port (`src/spotify/protocol`). Spotify's access point key and the Diffie-Hellman group come from there too, and the values sent where Spotify checks them are librespot's.
- **shannon** — https://crates.io/crates/shannon — MIT License, Copyright (c) 2016 Paul Lietar: the Shannon cipher in `spotify/protocol/Shannon.kt` is ported from it through the desktop's `shannon.cpp`, after Qualcomm's reference implementation.

<details><summary>MIT License text (webamp, QR Code generator, librespot, shannon)</summary>

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
</details>
