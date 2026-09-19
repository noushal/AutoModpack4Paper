package dev.automodpack4paper;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutoModpack4Paper extends JavaPlugin {

    private PackHost host;
    private LoginHandshake handshake;
    private final AtomicBoolean busy = new AtomicBoolean();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        host = new PackHost(getLogger(), getDataFolder().toPath());
        if (!host.start(getConfig(), getServer().getMinecraftVersion())) {
            getLogger().severe("Modpack host failed to start; players are NOT being served a modpack. See above.");
        }

        handshake = new LoginHandshake(this, getConfig().getString("bedrock-prefix", "."));
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(handshake);

        watchCertificate();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(buildCommand(), "AutoModpack4Paper admin", List.of("am4p")));
    }

    @Override
    public void onDisable() {
        if (host != null) host.stop();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> buildCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("automodpack4paper")
            .requires(src -> src.getSender().hasPermission("automodpack4paper.admin"));

        root.then(Commands.literal("status").executes(ctx -> {
            status(ctx.getSource().getSender());
            return 1;
        }));
        root.then(Commands.literal("fingerprint").executes(ctx -> {
            String fp = host.fingerprint();
            ctx.getSource().getSender().sendPlainMessage(fp == null ? "Host is not running." : "Certificate SHA-256 fingerprint: " + fp);
            return 1;
        }));
        root.then(Commands.literal("generate").executes(ctx -> {
            runAsync(ctx.getSource().getSender(), "Scanning and regenerating modpack...", () -> host.regenerate(getConfig(), getServer().getMinecraftVersion()));
            return 1;
        }));
        root.then(Commands.literal("reload").executes(ctx -> {
            runAsync(ctx.getSource().getSender(), "Reloading config and restarting the modpack host...", () -> {
                reloadConfig();
                handshake.setBedrockPrefix(getConfig().getString("bedrock-prefix", "."));
                return host.restart(getConfig(), getServer().getMinecraftVersion());
            });
            return 1;
        }));
        root.then(Commands.literal("regenerate-cert").executes(ctx -> {
            runAsync(ctx.getSource().getSender(),
                "Regenerating TLS certificate. Players must re-trust the new fingerprint...",
                () -> host.regenerateCertificate(getConfig(), getServer().getMinecraftVersion()));
            return 1;
        }));
        return root.build();
    }

    /** Hourly: when the configured CA certificate file was renewed, restart the host so it serves the new one. */
    private void watchCertificate() {
        String cert = getConfig().getString("tls.certificate-file", "").trim();
        if (cert.isEmpty()) return;
        java.nio.file.Path file = java.nio.file.Path.of(cert);
        long[] seen = {mtime(file)};
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = mtime(file);
            if (now == seen[0] || !busy.compareAndSet(false, true)) return;
            seen[0] = now;
            try {
                getLogger().info("TLS certificate file changed; reloading the modpack host.");
                host.restart(getConfig(), getServer().getMinecraftVersion());
            } finally {
                busy.set(false);
            }
        }, 20L * 3600, 20L * 3600);
    }

    private static long mtime(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Runs a blocking host operation off the main thread, one at a time. */
    private void runAsync(CommandSender who, String startMsg, BooleanSupplier op) {
        if (!busy.compareAndSet(false, true)) {
            who.sendPlainMessage("Another AutoModpack4Paper operation is still running.");
            return;
        }
        who.sendPlainMessage(startMsg);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean ok = op.getAsBoolean();
                who.sendPlainMessage(ok ? "Done. Fingerprint: " + host.fingerprint() : "Failed: " + host.lastError());
            } catch (Throwable t) {
                getLogger().severe("Operation failed: " + t);
                who.sendPlainMessage("Failed: " + t);
            } finally {
                busy.set(false);
            }
        });
    }

    private void status(CommandSender to) {
        var scan = host.lastScan();
        to.sendPlainMessage("AutoModpack4Paper " + getPluginMeta().getVersion() + " (advertises AutoModpack " + getConfig().getString("compat.automodpack-version", "4.0.6") + ")");
        to.sendPlainMessage("Host: " + (host.running() ? "running" : "NOT running") + (host.lastError() != null ? " | last error: " + host.lastError() : ""));
        to.sendPlainMessage("Modpack folder: " + host.modpackDir());
        if (scan != null) {
            to.sendPlainMessage("Files: " + scan.files() + " (" + scan.bytes() / 1024 / 1024 + " MiB) | safety problems: " + scan.violations().size());
        }
        if (host.lastGeneratedAt() > 0) {
            to.sendPlainMessage("Last generated: " + (System.currentTimeMillis() - host.lastGeneratedAt()) / 1000 + "s ago");
        }
        to.sendPlainMessage("Certificate: " + (host.tlsImported()
            ? "CA-signed (imported) - players get no trust prompt"
            : "self-signed - players see a one-time trust prompt (see tls.* in config.yml)")
            + " | fingerprint " + host.fingerprint());
    }
}
