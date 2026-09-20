package dev.automodpack4paper;

import static pl.skidam.automodpack_core.GlobalVariables.*;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientPluginResponse;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerPluginRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.AutoModpackProtocol;

/**
 * AutoModpack login handshake (docs/PROTOCOL.md). The vanilla LOGIN_SUCCESS packet is held back while two login
 * plugin queries run: {@code automodpack:handshake} (-100) then {@code automodpack:data} (-101). Client answers are
 * cancelled so vanilla never sees them (vanilla disconnects on unknown login answers).
 */
public final class LoginHandshake extends PacketListenerAbstract {

    private static final int HANDSHAKE_ID = -100;
    private static final int DATA_ID = -101;
    private static final String HANDSHAKE_CHANNEL = "automodpack:handshake";
    private static final String DATA_CHANNEL = "automodpack:data";
    private static final long MAX_HOLD_MS = 15 * 60_000L;

    private enum Stage { AWAIT_HANDSHAKE, AWAIT_DATA }

    private static final class Session {
        final WrapperLoginServerLoginSuccess held;
        final Object channel;
        final long started = System.currentTimeMillis();
        volatile Stage stage = Stage.AWAIT_HANDSHAKE;
        volatile BukkitTask keepAlive;

        Session(WrapperLoginServerLoginSuccess held, Object channel) {
            this.held = held;
            this.channel = channel;
        }
    }

    private final Plugin plugin;
    private final PackHost host;
    private final Logger log;
    private volatile String bedrockPrefix;
    private final Map<User, Session> sessions = new ConcurrentHashMap<>();
    private final Set<User> releasing = ConcurrentHashMap.newKeySet();
    private volatile boolean tickResetFailedLogged;

    public LoginHandshake(Plugin plugin, PackHost host, String bedrockPrefix) {
        this.plugin = plugin;
        this.host = host;
        this.log = plugin.getLogger();
        this.bedrockPrefix = bedrockPrefix == null ? "" : bedrockPrefix;
    }

    public void setBedrockPrefix(String prefix) {
        this.bedrockPrefix = prefix == null ? "" : prefix;
    }

    // ---- server -> client: hold LOGIN_SUCCESS -------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Login.Server.LOGIN_SUCCESS) return;
        User user = event.getUser();
        if (releasing.remove(user)) return; // our own re-send
        if (hostNotReady()) return;

        var held = new WrapperLoginServerLoginSuccess(event);
        String name = held.getUserProfile().getName();
        UUID uuid = held.getUserProfile().getUUID();
        if (isBedrock(name, uuid)) return;

        event.setCancelled(true);
        Session s = new Session(held, event.getChannel());
        sessions.put(user, s);
        startKeepAlive(user, s);
        log.info(name + " login held for AutoModpack handshake");

        JsonObject hs = new JsonObject();
        JsonArray loaders = new JsonArray();
        serverConfig.acceptedLoaders.forEach(loaders::add);
        hs.add("loaders", loaders);
        hs.addProperty("amVersion", AM_VERSION);
        hs.addProperty("mcVersion", MC_VERSION);
        user.sendPacket(new WrapperLoginServerPluginRequest(HANDSHAKE_ID, HANDSHAKE_CHANNEL, writeUtf(hs.toString())));
    }

    // ---- client -> server: consume our answers ----------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE) return;
        User user = event.getUser();
        Session s = sessions.get(user);
        if (s == null) return;

        var resp = new WrapperLoginClientPluginResponse(event);
        int id = resp.getMessageId();
        if (id != HANDSHAKE_ID && id != DATA_ID) return;
        event.setCancelled(true);

        try {
            if (id == HANDSHAKE_ID && s.stage == Stage.AWAIT_HANDSHAKE) onHandshakeAnswer(user, s, resp);
            else if (id == DATA_ID && s.stage == Stage.AWAIT_DATA) onDataAnswer(user, s, resp);
            else kick(user, s, "[AutoModpack] Unexpected login packet order.");
        } catch (Exception e) {
            log.warning("Handshake error for " + name(s) + ": " + e);
            kick(user, s, "[AutoModpack] The server failed to process your handshake. Ask the server administrator to check the server log.");
        }
    }

    private void onHandshakeAnswer(User user, Session s, WrapperLoginClientPluginResponse resp) {
        String name = name(s);
        String json = resp.isSuccessful() ? readUtf(resp.getData()) : null;
        if (json == null || json.isEmpty()) {
            // Client has no AutoModpack (or refused our loader list)
            log.warning(name + " has not installed AutoModpack (or loader mismatch).");
            if (serverConfig.requireAutoModpackOnClient) {
                kick(user, s, "AutoModpack mod (" + String.join("/", serverConfig.acceptedLoaders) + ") is required to play on this server! "
                    + serverConfig.nagMessage);
            } else {
                release(user, s);
            }
            return;
        }

        JsonObject c = JsonParser.parseString(json).getAsJsonObject();
        String clientVersion = c.get("amVersion").getAsString();
        boolean loaderOk = false;
        for (var l : c.getAsJsonArray("loaders")) if (serverConfig.acceptedLoaders.contains(l.getAsString())) loaderOk = true;
        if (!loaderOk || !AutoModpackProtocol.acceptsClient(AM_VERSION, clientVersion)) {
            log.warning(name + " AutoModpack mismatch: client " + clientVersion + " / server " + AM_VERSION);
            kick(user, s, "AutoModpack version mismatch! Install version " + AM_VERSION + " of the AutoModpack mod to play on this server!");
            return;
        }
        if (modpackExecutor != null && modpackExecutor.isGenerating()) {
            kick(user, s, "AutoModpack is generating the modpack. Please wait a moment and try again.");
            return;
        }

        log.info(name + " has installed AutoModpack " + clientVersion);
        Secrets.Secret secret = Secrets.generateSecret();
        SecretsStore.saveHostSecret(s.held.getUserProfile().getUUID().toString(), secret);

        Map<String, Object> data = new LinkedHashMap<>();
        boolean requiresMagic = (serverConfig.bindPort == -1 && hostServer.isRunning()) || serverConfig.requireMagicPackets;
        data.put("address", serverConfig.addressToSend);
        data.put("port", serverConfig.portToSend);
        data.put("modpackName", serverConfig.modpackName);
        data.put("secret", secret);
        data.put("modRequired", serverConfig.requireAutoModpackOnClient);
        data.put("requiresMagic", requiresMagic);

        s.stage = Stage.AWAIT_DATA;
        user.sendPacket(new WrapperLoginServerPluginRequest(DATA_ID, DATA_CHANNEL, writeUtf(ConfigTools.GSON.toJson(data))));
    }

    private void onDataAnswer(User user, Session s, WrapperLoginClientPluginResponse resp) {
        String name = name(s);
        if (resp.getData() == null || resp.getData().length == 0) { // upstream lets the login continue on an empty answer
            log.warning(name + " sent an empty data answer; letting the login continue");
            release(user, s);
            return;
        }
        String answer = readUtf(resp.getData());
        String fp = hostServer == null ? null : hostServer.getCertificateFingerprint();
        switch (String.valueOf(answer)) {
            case "false" -> {
                log.info(name + " has installed the whole modpack");
                release(user, s);
            }
            case "true" -> {
                log.warning(name + " has not installed the modpack. Certificate fingerprint: " + fp);
                kick(user, s, "[AutoModpack] Install/Update modpack to join");
            }
            default -> {
                // "null" (or anything unexpected) = the client could not fetch the modpack from the host
                String why = host.diagnose();
                boolean allow = !serverConfig.requireAutoModpackOnClient
                    || "allow".equalsIgnoreCase(plugin.getConfig().getString("login.on-host-error", "kick"));
                log.severe(name + " could not use the modpack host (client answered \"" + answer + "\"): " + why + ".");
                if (allow) {
                    log.warning(name + " is allowed to join WITHOUT the modpack (login.on-host-error=allow or requireAutoModpackOnClient=false).");
                    release(user, s);
                } else {
                    kick(user, s, "[AutoModpack] The modpack download failed. Reconnect and try again; if it keeps failing, tell the server admin.");
                }
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private void release(User user, Session s) {
        finish(user, s);
        releasing.add(user);
        user.sendPacket(s.held);
    }

    private void kick(User user, Session s, String reason) {
        finish(user, s);
        user.sendPacket(new WrapperLoginServerDisconnect(Component.text(reason)));
        user.closeConnection();
    }

    private void finish(User user, Session s) {
        sessions.remove(user);
        BukkitTask t = s.keepAlive;
        if (t != null) t.cancel();
    }

    private static String name(Session s) {
        return s.held.getUserProfile().getName();
    }

    private boolean hostNotReady() {
        return serverConfig == null || hostServer == null || !hostServer.isRunning();
    }

    private boolean isBedrock(String name, UUID uuid) {
        if (!bedrockPrefix.isEmpty() && name != null && name.startsWith(bedrockPrefix)) return true;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, uuid);
        } catch (Exception e) {
            return false; // Floodgate absent or API unavailable
        }
    }

    /**
     * Vanilla disconnects a login after 600 ticks (netty's 30s read timeout still applies: clients must answer the data query within ~30s, as with upstream) ("Took too long to log in"). Modpack downloads take longer, so the
     * login listener's private {@code tick} counter is reset while we hold the login. Best effort.
     */
    private void startKeepAlive(User user, Session s) {
        s.keepAlive = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (System.currentTimeMillis() - s.started > MAX_HOLD_MS) {
                kick(user, s, "[AutoModpack] Timed out waiting for your client.");
                return;
            }
            try {
                Object connection = findConnection(user.getAddress());
                if (connection == null) return; // already disconnected
                Object listener = connection.getClass().getMethod("getPacketListener").invoke(connection);
                Field f = listener.getClass().getDeclaredField("tick");
                f.setAccessible(true);
                f.setInt(listener, 0);
            } catch (Throwable t) {
                if (!tickResetFailedLogged) {
                    tickResetFailedLogged = true;
                    log.warning("Could not reset vanilla login timeout (long downloads may time out at 30s): " + t);
                }
            }
        }, 20L, 100L);
    }

    /** Finds net.minecraft.network.Connection for a remote address through the server's connection list (reflection). */
    private static Object findConnection(java.net.InetSocketAddress addr) throws Exception {
        Object nms = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object listener = nms.getClass().getMethod("getConnection").invoke(nms);
        for (Object conn : (java.util.List<?>) listener.getClass().getMethod("getConnections").invoke(listener)) {
            Object remote = conn.getClass().getMethod("getRemoteAddress").invoke(conn);
            if (addr.equals(remote)) return conn;
        }
        return null;
    }

    private static byte[] writeUtf(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int v = b.length;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static String readUtf(byte[] data) {
        if (data == null || data.length == 0) return null;
        int i = 0, len = 0, shift = 0;
        byte b;
        do {
            b = data[i++];
            len |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0 && i < data.length);
        if (len < 0 || i + len > data.length) return null;
        return new String(data, i, len, StandardCharsets.UTF_8);
    }
}
