# AutoModpack login protocol (release 4.0.6)

Source: tag `v4.0.6` of Skidamek/AutoModpack (`src/main/java/pl/skidam/automodpack/networking/**`, `core/`).
NOTE: the `main` branch is an unreleased rewrite with a different data packet and MUST NOT be used as reference.

## Transport
LOGIN-state custom queries (login plugin messages), payload = `writeUtf(json)` (VarInt length + UTF-8).

| Channel | Query id | Direction |
|---|---|---|
| `automodpack:handshake` | -100 | S2C request, C2S answer |
| `automodpack:data` | -101 | S2C request, C2S answer |

An answer with `successful=false` / no data = client does not understand the query (no mod).

## Sequence
1. Server holds login, sends handshake: `{"loaders":["fabric","neoforge","forge"],"amVersion":"4.0.6","mcVersion":"26.2"}`.
   `loaders` must contain the CLIENT's loader (client aborts otherwise), so the plugin advertises client loaders.
2. Client answers `{"loaders":[<its loader>],"amVersion":..,"mcVersion":..}` (empty answer if loader not in list).
3. Server accepts if loader ok and `AutoModpackProtocol.acceptsClient(server, client)`: equal versions, or both stable 4.0.x.
4. Server makes a per-player secret (32 random bytes base64url + timestamp), stores it (`SecretsStore.saveHostSecret(uuid, secret)`), sends data:
   `{"address":"","port":30037,"modpackName":"","secret":{"secret":..,"timestamp":..},"modRequired":true,"requiresMagic":false}`
   Blank address = host the player joined by; port -1 = join port (plugin sends the dedicated bind port).
5. Client contacts host over TLS 1.3 (fingerprint pinning / trust prompt on the client), syncs, answers with a UTF string:
   `false` = fully synced, continue; `true` = update needed (client disconnects, downloads after leaving, server kicks);
   anything else (e.g. `null`) = host error. Empty answer = login continues.

## Host
Core `NettyServer` on a dedicated port (`bindPort`), TLS 1.3 self-signed certificate (`.private/cert.crt`, `key.pem`), files served by
SHA-1 from the generated content index. `validateSecrets=true` makes it require the per-player secret.

## Plugin implementation (`LoginHandshake`, `PackHost`)
- packetevents holds vanilla `LOGIN_SUCCESS` (cancel + stash), sends -100 then -101, cancels the client answers (vanilla kicks on unknown
  login answers), releases the stashed packet on `false`.
- Netty's ~30s read timeout still applies while the login is held (same as upstream): on-screen prompts must be answered in ~30s.
- Floodgate/Bedrock players skip the handshake (name prefix `.` and the Floodgate API).
- Safe-file scan runs before every publish (`SafetyScanner`).
