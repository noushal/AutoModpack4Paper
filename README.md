<p align="center"><img src="assets/logo-512.png" alt="AutoModpack4Paper logo" width="128"></p>

# AutoModpack4Paper

Unofficial add-on that lets **Paper** servers work with [AutoModpack](https://modrinth.com/project/automodpack) clients.

Put client mods (and configs, resource packs, shaders) in a folder on your Paper server. Players who have the AutoModpack mod
installed get them downloaded and kept in sync automatically when they join. Players install AutoModpack once, by hand; there is
nothing else to install on their side.

> **Not affiliated with AutoModpack or its author.** This is an independent community project. Please do not report problems
> with this plugin to the AutoModpack developers.

## Requirements
- Paper 26.2, Java 25
- [packetevents](https://modrinth.com/plugin/packetevents) **2.13.0** (required, installed as a separate plugin; other versions are untested)
- One free TCP port for the modpack host (default **30037**), reachable by players
- Players: Minecraft with **AutoModpack 4.0.x** (Fabric, NeoForge or Forge)

## Compatibility
| AutoModpack4Paper | Paper | Java | packetevents | AutoModpack client |
|---|---|---|---|---|
| 0.1.1 | 26.2 (tested on build 124) | 25 | 2.13.0 | 4.0.6 tested; any stable 4.0.x is accepted |
| 0.1.0 | 26.2 | 25 | 2.13.0 | same (has a known TLS start-up crash on some servers; use 0.1.1) |

- Only the **4.0.x** client protocol is supported. AutoModpack's development branch uses a different, incompatible protocol; this
  plugin does not target it, and a future AutoModpack protocol change may require a plugin update.
- Tested with a Fabric client. NeoForge/Forge clients use the same protocol but have not been tested.
- Bedrock (Geyser/Floodgate) players skip the handshake and are unaffected. Tested with a `.`-prefixed name; not yet with a real Geyser join.
- Not supported behind Velocity/BungeeCord. (For Velocity, see the separate AutoModpack Velocity plugin.)

## Install
1. Install packetevents and this plugin in `plugins/`, then start the server once.
2. Put client mods in `plugins/AutoModpack4Paper/modpack/mods/` (other folders: `config/`, `resourcepacks/`, `shaderpacks/`, ...).
3. Run `/automodpack4paper generate` (or restart).
4. Open **TCP 30037** (`host.port` in `config.yml`) on your firewall/host panel.
5. Players join. On the first join their client downloads the files and asks them to reconnect or restart.

The modpack must match your players' loader (for example Fabric mods for Fabric players).

## Commands
`/automodpack4paper`, alias `/am4p`, permission `automodpack4paper.admin` (ops by default).

| Command | What it does |
|---|---|
| `status` | Host state, file count, safety problems, certificate mode and fingerprint |
| `generate` | Scan and regenerate the modpack (starts the host if a scan blocked startup) |
| `reload` | Reload `config.yml`, re-read `automodpack/automodpack-server.json`, restart the host |
| `fingerprint` | Show the TLS certificate SHA-256 fingerprint |
| `regenerate-cert` | New self-signed key pair and restart (players must re-trust it) |

Changes inside `modpack/` are only published by `generate`; there is no file watcher.

## Files
- `plugins/AutoModpack4Paper/modpack/`: what clients receive.
- `plugins/AutoModpack4Paper/config.yml`: this plugin's settings.
- `plugins/AutoModpack4Paper/automodpack/automodpack-server.json`: AutoModpack's own server config, created on first start. These
  settings live **here, not in `config.yml`**: `bindPort`, `addressToSend`, `portToSend`, `validateSecrets` (keep `true`),
  `requireAutoModpackOnClient`, `disableInternalTLS` (keep `false`; clients always use TLS). The plugin's `host.port` etc. only seed this
  file the first time; afterwards edit the JSON and run `/am4p reload`.

## The player experience and certificates
The host serves files over TLS. By default it uses a self-signed certificate, so each player sees a **one-time "trust this server?"
prompt** in the AutoModpack client the first time they join. That check is done by the client, not by this plugin.

To remove the prompt, use a certificate from a public CA (for example Let's Encrypt):
1. Get a certificate for a domain that points at your server.
2. Set `tls.certificate-file` (fullchain PEM) and `tls.private-key-file` (PEM key, any format) in `config.yml`, then `/am4p reload`.
3. Players must join **by that domain name**; the certificate name has to match the address the client connects to.

Renewed certificates are picked up hourly or with `/am4p reload`. Leave both settings empty to keep the self-signed certificate.

## Safety
Clients automatically download everything you publish, so the plugin is strict about what it will serve. Before every publish,
`modpack/` is scanned and, with `safety.enforce: true` (default), **nothing is published** until problems are fixed:
- no symlinks or junctions
- only these top-level folders: `mods`, `config`, `resourcepacks`, `shaderpacks`, `kubejs`, `emotes` (and `options.txt`)
- `.jar` files only under `mods/`
- no executables, scripts or native libraries (`.exe .bat .sh .dll .so` ...)
- no Windows-reserved names, and a per-file size cap

Files are served only from the generated index, per player, using a per-login secret. The plugin never serves anything outside `modpack/`,
and never exposes RCON or any other server port. Clients still verify the TLS certificate.

If the host is not running (for example the folder is empty or a scan failed), players are **not** blocked from joining; they just
get no modpack. `/am4p status` and the server log say why.

## When a player has AutoModpack but cannot download
If a client cannot reach the modpack host (firewall, wrong `addressToSend`, TLS problem) it answers with an error and the server, by default,
disconnects it with "The modpack download failed". Set `login.on-host-error: allow` to let such players join without the modpack instead
(they are always let in when `requireAutoModpackOnClient` is `false`). The server log explains the likely cause: host not running, TLS not
active, or "the host is running with TLS, so the client could not reach <address:port> from outside".

## Known limitations
- While a join is waiting on the client, Netty's ~30 s read timeout applies (same as AutoModpack itself), so on-screen prompts must be answered within about 30 seconds.
- Players without AutoModpack are kicked with a message (AutoModpack's `requireAutoModpackOnClient`, default on).

## Building
```
JAVA_HOME=<JDK 25> ./gradlew build
```
Output: `build/libs/AutoModpack4Paper-<version>.jar`.

## License and source
Licensed under the **GNU LGPL v3** (see `LICENSE`), because it includes AutoModpack's LGPL-3.0 `core`. Full source, including an unmodified copy of AutoModpack
core v4.0.6 (three small documented patches: `GlobalVariables.java`, `NettyServer.java`, `NetUtils.java`) in `third_party/automodpack-core/`, is in this repository.
Because the release jar bundles that code, you may modify it and rebuild it from this source. See `NOTICE` for attribution and bundled libraries.
Design notes: `docs/PROTOCOL.md`.
