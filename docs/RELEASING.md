# Releasing AutoModpack4Paper

Order matters: GitHub first (the source must be public, LGPL), then the download sites, which all link back to it.

## 0. Before every release
1. Bump `version` in `build.gradle.kts`, add a section to `CHANGELOG.md`, update the compatibility table in `README.md`.
2. `JAVA_HOME=<JDK 25> ./gradlew clean build` -> `build/libs/AutoModpack4Paper-<version>.jar`.
3. Test it on a clean Paper server with packetevents (see README install steps) and, ideally, a real AutoModpack client.
4. Note the jar checksum: `sha256sum build/libs/*.jar`.

## 1. GitHub
1. Create the repository (public), push the project.
2. Tag and release: `git tag v<version> && git push --tags`, then GitHub -> Releases -> "Draft a new release" for that tag.
   Attach the jar and paste the changelog section and the SHA-256.

## 2. Modrinth (https://modrinth.com)
1. Sign in -> "+" -> **Create a project**. Project type: **Plugin**. Name `AutoModpack4Paper`, slug `automodpack4paper`.
2. Fill in from `docs/MODRINTH.md`: summary, description, categories, License `LGPL-3.0-only`, client/server side = server only.
3. Upload the icon (`assets/logo.svg` or `assets/logo-512.png`). Add links: source (GitHub), issues.
4. **Create a version**: upload the jar, version number `0.1.0`, release channel (use *Beta* while Geyser/Forge/NeoForge are untested),
   loader **Paper**, game version **26.2**, add the dependency **packetevents** (required).
5. **Submit for review**. Moderators approve before it goes public (usually a few days). Make sure the description states it is
   unofficial and the Source link (in the project's links section, not the description) points to the GitHub repo.

## 3. Hangar (https://hangar.papermc.io) - PaperMC's plugin site
1. Sign in with a PaperMC account -> **New project**. Name `AutoModpack4Paper`, category, license LGPL-3.0, link to the GitHub repo.
2. Upload the icon and write the description (same text as Modrinth).
3. **New version**: upload the jar, version `0.1.0`, channel, platform **Paper**, supported version **26.2**,
   dependency **packetevents** (required; add it as a Hangar project if one exists, otherwise as an external link to its Modrinth page).
4. Publish. Hangar projects are visible right away but can be reviewed and taken down for rule violations.

## 4. Other platforms (optional)
- **CurseForge** (Bukkit Plugins section): needs a CurseForge author account and project approval; upload the same jar and text.
  Worth it only for extra reach.
- **SpigotMC**: not a fit. The plugin uses Paper-only APIs (`paper-plugin.yml`, Brigadier commands) and Spigot requires Spigot compatibility.
- **BuiltByBit / Polymart**: mainly for paid resources; skip.
- **Server-list sites and Discords** (PaperMC Discord `#plugin-showcase`): optional promotion after it is live.

## 5. After release
- Watch issues; state the tested client versions in every changelog entry.
- If a new AutoModpack client version breaks the protocol, mark the Modrinth/Hangar versions accordingly and release a fix.
