package dev.automodpack4paper;

import static pl.skidam.automodpack_core.GlobalVariables.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

/**
 * Runs AutoModpack's core (v4.0.6, one small patch in GlobalVariables) as a modpack host on a dedicated TCP port.
 * Layout under the plugin folder: {@code modpack/} = files served to clients (mods/, config/, ...),
 * {@code automodpack/} = core state (server config, certificate, secrets, generated content index).
 */
public final class PackHost {

    private final Logger log;
    private final Path pluginDir;
    private volatile SafetyScanner.Result lastScan;
    private volatile long lastGeneratedAt;
    private volatile String lastError;

    public PackHost(Logger log, Path pluginDir) {
        this.log = log;
        this.pluginDir = pluginDir.toAbsolutePath().normalize();
    }

    public Path modpackDir() {
        return pluginDir.resolve("modpack");
    }

    /** (Re)loads the core server config, scans and publishes the modpack, and starts the TLS host. Blocking. */
    public synchronized boolean start(FileConfiguration cfg, String mcVersion) {
        lastError = null;
        try {
            relocate(pluginDir.resolve("automodpack"), modpackDir());
            LOADER = "paper";
            MC_VERSION = mcVersion;
            AM_VERSION = cfg.getString("compat.automodpack-version", "4.0.6");

            if (!loadServerConfig(cfg)) return fail("Could not load " + serverConfigFile + " (JSON error?)");
            String tlsProblem = checkTlsSetting(cfg);
            if (tlsProblem != null) return fail(tlsProblem);

            Files.createDirectories(modpackDir().resolve("mods"));
            String tlsError = TlsImporter.apply(log, tlsCert(cfg), tlsKey(cfg), serverCertFile, serverPrivateKeyFile, tlsMarker());
            if (tlsError != null) return fail(tlsError);
            if (!safetyCheck(cfg)) return false;
            if (lastScan != null && lastScan.files() == 0) {
                return fail("The modpack folder is empty, so there is nothing to host yet. Put client mods in " + modpackDir().resolve("mods")
                    + " and run /automodpack4paper generate.");
            }

            hostServer = new NettyServer();
            modpackExecutor = new ModpackExecutor();

            long t = System.currentTimeMillis();
            boolean ok = serverConfig.generateModpackOnStart ? modpackExecutor.generateNew() : modpackExecutor.loadLast();
            if (!ok) return fail("Failed to generate/load modpack");
            lastGeneratedAt = System.currentTimeMillis();
            log.info("Modpack ready in " + (lastGeneratedAt - t) + "ms");

            hostServer.start();
            String fp = hostServer.getCertificateFingerprint();
            log.info("Host on port " + serverConfig.bindPort + " | "
                + (fp != null ? "certificate fingerprint: " + fp : "TLS is OFF here (external TLS termination is configured)"));
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return fail("Failed to start modpack host: " + t);
        }
    }

    /** Stops the host and executor. Safe to call repeatedly. */
    public synchronized void stop() {
        try {
            if (hostServer != null) hostServer.stop();
            if (modpackExecutor != null) modpackExecutor.stop();
        } catch (Throwable t) {
            log.warning("Error stopping host: " + t);
        }
    }

    /** Full restart: stop, re-read configs, re-scan, re-publish, restart the TLS host. */
    public synchronized boolean restart(FileConfiguration cfg, String mcVersion) {
        stop();
        return start(cfg, mcVersion);
    }

    /** Deletes the TLS key pair (clients must re-trust the new fingerprint) and restarts. */
    public synchronized boolean regenerateCertificate(FileConfiguration cfg, String mcVersion) {
        if (TlsImporter.configured(tlsCert(cfg), tlsKey(cfg))) {
            return fail("A CA certificate is configured under tls.*; renew it at its source and run /automodpack4paper reload.");
        }
        stop();
        try {
            Files.deleteIfExists(serverCertFile);
            Files.deleteIfExists(serverPrivateKeyFile);
        } catch (Exception e) {
            return fail("Could not delete old certificate: " + e);
        }
        return start(cfg, mcVersion);
    }

    /** Re-scans and regenerates the served modpack without restarting the listener (starts the host if it is down). Blocking. */
    public synchronized boolean regenerate(FileConfiguration cfg, String mcVersion) {
        if (!running()) return start(cfg, mcVersion); // e.g. startup was blocked by the safety scan
        lastError = null;
        try {
            if (modpackExecutor.isGenerating()) return fail("A generation is already running");
            if (!safetyCheck(cfg)) return false;
            if (lastScan != null && lastScan.files() == 0) return fail("The modpack folder is empty; nothing to host.");
            if (!modpackExecutor.generateNew()) return fail("Failed to generate modpack");
            lastGeneratedAt = System.currentTimeMillis();
            return true;
        } catch (Throwable t) {
            return fail("Regenerate failed: " + t);
        }
    }

    private boolean loadServerConfig(FileConfiguration cfg) {
        boolean fresh = !Files.exists(serverConfigFile);
        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV2.class);
        if (serverConfig == null) return false;
        if (fresh) {
            int port = cfg.getInt("host.port", 30037);
            int advertised = cfg.getInt("host.advertised-port", -1);
            serverConfig.syncedFiles = Set.of(); // never sync files from the Paper server root
            serverConfig.allowEditsInFiles = Set.of("/options.txt", "/config/**");
            serverConfig.acceptedLoaders = new HashSet<>(Set.of("fabric", "neoforge", "forge"));
            serverConfig.autoExcludeServerSideMods = false;
            serverConfig.bindPort = port;
            serverConfig.portToSend = advertised != -1 ? advertised : port;
            serverConfig.addressToSend = cfg.getString("host.advertised-host", "");
            ConfigTools.save(serverConfigFile, serverConfig);
        }
        if (!serverConfig.validateSecrets) {
            log.warning("validateSecrets is FALSE in " + serverConfigFile.getFileName()
                + ": anyone who can reach port " + serverConfig.bindPort + " can download the modpack without joining. Set it to true.");
        }
        if (serverConfig.bindPort == -1) {
            log.warning("bindPort is -1 (modpack on the Minecraft port) which this plugin does not support. Set a dedicated bindPort.");
        }
        if (!serverConfig.syncedFiles.isEmpty()) {
            log.warning("syncedFiles is not empty: files from the server root will be published to clients: " + serverConfig.syncedFiles);
        }
        return true;
    }

    private boolean safetyCheck(FileConfiguration cfg) throws java.io.IOException {
        var section = cfg.getConfigurationSection("safety");
        if (section == null) return true;
        SafetyScanner.Result r = new SafetyScanner(section).scan(modpackDir());
        lastScan = r;
        if (r.clean()) return true;

        boolean enforce = section.getBoolean("enforce", true);
        log.warning("Safety scan found " + r.violations().size() + " problem(s) in " + modpackDir() + ":");
        r.violations().forEach(v -> log.warning("  - " + v));
        if (enforce) {
            return fail("Modpack NOT published (safety.enforce=true). Fix or remove the files above, then run /automodpack4paper generate.");
        }
        log.warning("safety.enforce=false: publishing anyway.");
        return true;
    }

    private boolean fail(String msg) {
        lastError = msg;
        log.severe(msg);
        return false;
    }

    private static String tlsCert(FileConfiguration cfg) {
        return cfg.getString("tls.certificate-file", "").trim();
    }

    private static String tlsKey(FileConfiguration cfg) {
        return cfg.getString("tls.private-key-file", "").trim();
    }

    private Path tlsMarker() {
        return privateDir.resolve("imported-tls");
    }

    /** True when the served certificate came from tls.* (CA-signed, players get no trust prompt). */
    public boolean tlsImported() {
        return Files.exists(tlsMarker());
    }

    /** AutoModpack clients always speak TLS. A plaintext listener only makes sense behind a TLS-terminating proxy. */
    private String checkTlsSetting(FileConfiguration cfg) {
        if (!serverConfig.disableInternalTLS) return null;
        if (cfg.getBoolean("tls.external-termination", false)) {
            log.warning("disableInternalTLS is true: the host port " + serverConfig.bindPort + " speaks plain TCP and MUST sit behind a "
                + "TLS-terminating proxy, because AutoModpack clients always use TLS.");
            return null;
        }
        return "disableInternalTLS is true in " + serverConfigFile.getFileName() + ", but AutoModpack clients always use TLS, so every client would fail. "
            + "Set it to false. Only if a TLS-terminating proxy sits in front of the host port, set tls.external-termination: true in config.yml.";
    }

    /** One-line summary of the certificate situation for /am4p status. */
    public String certificateSummary() {
        if (!running()) return "none (the modpack host is not running)";
        if (serverConfig != null && serverConfig.disableInternalTLS) return "TLS off (external TLS termination); no certificate served by this plugin";
        String fp = fingerprint();
        String mode = tlsImported()
            ? "CA-signed (imported) - players get no trust prompt"
            : "self-signed - players see a one-time trust prompt (see tls.* in config.yml)";
        return mode + (fp != null ? " | fingerprint " + fp : " | no certificate loaded");
    }

    /** Explains, from the server's point of view, why a client could not use the modpack host. */
    public String diagnose() {
        if (serverConfig == null || hostServer == null || !hostServer.isRunning()) {
            return "the modpack host is not running" + (lastError != null ? " (" + lastError + ")" : "");
        }
        if (serverConfig.disableInternalTLS || hostServer.getCertificateFingerprint() == null) {
            return "TLS is not active on the host (disableInternalTLS is true or no certificate was loaded), but AutoModpack clients require TLS";
        }
        String advertised = (serverConfig.addressToSend.isBlank() ? "the address the player joined with" : serverConfig.addressToSend)
            + ":" + serverConfig.portToSend;
        return "the host is running with TLS on port " + serverConfig.bindPort + ", so the client most likely could not reach "
            + advertised + " from outside (firewall / port forwarding / advertised address) or rejected the certificate; "
            + "the client's log has the exact error (lines starting with [AutoModpack])";
    }

    public boolean running() {
        return hostServer != null && hostServer.isRunning();
    }

    public String fingerprint() {
        return hostServer == null ? null : hostServer.getCertificateFingerprint();
    }

    public String lastError() {
        return lastError;
    }

    public SafetyScanner.Result lastScan() {
        return lastScan;
    }

    public long lastGeneratedAt() {
        return lastGeneratedAt;
    }
}
