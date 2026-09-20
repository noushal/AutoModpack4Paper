# Changelog

## 0.1.1
Fixes from real-server testing of 0.1.0.
- **TLS key crash fixed.** The host could fail to start (`NoClassDefFoundError: org/bouncycastle/openssl/PEMParser`) on servers where
  another plugin exposes a partial BouncyCastle. The plugin now gives Netty the key and certificate as objects, so Netty's optional
  PEM reader is never used. (Small documented patch to AutoModpack core's `NettyServer`/`NetUtils`, see `NOTICE`.)
- **Clear errors instead of "answered null".** When a client cannot use the host, the log now says why: host not running, TLS not
  active, or "host running with TLS; the client could not reach <address:port> (firewall / advertised address)".
- **Host-error policy.** New `login.on-host-error: kick|allow` (default `kick`); players are always let in when
  `requireAutoModpackOnClient` is false. Kick message no longer exposes internals.
- **TLS-off guard.** `disableInternalTLS: true` is refused (AutoModpack clients always use TLS) unless `tls.external-termination: true`
  is set for a TLS proxy. No more floods of `Frame compressed length ... exceeds limit`.
- `/am4p status`, `fingerprint` and the startup log only show a fingerprint when TLS is on, and say so when it is not.
- Documented which settings live in `automodpack/automodpack-server.json`.
- Jar slimmed from 11.2 MB to about 8.9 MB (unused BouncyCastle parts removed) so it can be hosted on Hangar.

## 0.1.0
First release.
- Runs AutoModpack's host (core v4.0.6) as a Paper plugin on a dedicated TCP port with TLS 1.3.
- Login handshake through packetevents; works with stock AutoModpack 4.0.x clients.
- `/automodpack4paper` (`/am4p`): `status`, `generate`, `reload`, `fingerprint`, `regenerate-cert`.
- Safe-file scanner (fail-closed) before every publish.
- Optional CA-signed certificate (`tls.*`) so players get no trust prompt; hourly renewal check.
- Floodgate/Bedrock players skip the handshake.
