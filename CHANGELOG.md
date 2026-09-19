# Changelog

## 0.1.0
First release.
- Runs AutoModpack's host (core v4.0.6) as a Paper plugin on a dedicated TCP port with TLS 1.3.
- Login handshake through packetevents; works with stock AutoModpack 4.0.x clients.
- `/automodpack4paper` (`/am4p`): `status`, `generate`, `reload`, `fingerprint`, `regenerate-cert`.
- Safe-file scanner (fail-closed) before every publish.
- Optional CA-signed certificate (`tls.*`) so players get no trust prompt; hourly renewal check.
- Floodgate/Bedrock players skip the handshake.
