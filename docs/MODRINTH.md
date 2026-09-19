# Modrinth listing (copy into the project page)

**Name:** AutoModpack4Paper
**Slug:** automodpack4paper (checked free on Modrinth and Hangar on 2026-09-19)
**Summary:** Unofficial add-on that lets Paper servers work with AutoModpack clients.
**Categories:** Utility, Management. **Loader:** Paper. **Game versions:** 26.2. **Environment:** server-side.
**License:** LGPL-3.0-only. **Dependency:** packetevents 2.13.0 (required).

## Body

Unofficial add-on that lets **Paper** servers work with [AutoModpack](https://modrinth.com/project/automodpack) clients.

AutoModpack only runs on Fabric, Forge and NeoForge servers. This plugin makes a Paper server behave like an AutoModpack server, so
players with the AutoModpack mod get your mods, configs, resource packs and shaders downloaded and updated automatically.

**Not affiliated with AutoModpack or its author.**

### How it works
- Drop client mods into `plugins/AutoModpack4Paper/modpack/mods/`, run `/am4p generate`, open TCP 30037.
- Players install the AutoModpack mod once; the rest is automatic.
- Bedrock (Geyser/Floodgate) players are unaffected.

### Requirements
Paper 26.2, Java 25, packetevents 2.13.0 (other versions untested), AutoModpack 4.0.x on the client. Players see a one-time certificate prompt unless you use a
CA-signed certificate (`tls.*` in `config.yml`).

### Safety
Only files in the modpack folder are served, and only after a strict scan (no symlinks, no executables, `.jar` only in `mods/`).
