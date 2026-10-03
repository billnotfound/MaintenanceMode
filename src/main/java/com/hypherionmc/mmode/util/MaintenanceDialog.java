package com.hypherionmc.mmode.util;

import com.hypherionmc.craterlib.api.game.authlib.CraterGameProfile;
import com.hypherionmc.craterlib.api.game.server.CraterGameServer;
import com.hypherionmc.craterlib.api.game.text.Text;
import com.hypherionmc.mmode.CommonClass;
import com.hypherionmc.mmode.ModConstants;
import com.hypherionmc.mmode.config.MaintenanceModeConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the configured maintenance message as a native Minecraft dialog (1.21.6+) instead of
 * only kicking the player with plain text. Dialogs support clickable links, so players can open
 * Discord/website links directly from the maintenance screen.
 * <p>
 * The player is allowed through the login phase and held in the configuration phase, where the
 * dialog is displayed and the configuration is never completed. On Minecraft versions without
 * dialog support everything falls back to the old text kick.
 * <p>
 * Every Minecraft class is accessed by name through reflection, so this class is safe to load on
 * old versions where dialogs do not exist.
 */
public final class MaintenanceDialog {

    private static final String CLASS_DIALOG = "net.minecraft.server.dialog.Dialog";
    private static final String CLASS_SHOW_DIALOG_PACKET = "net.minecraft.network.protocol.common.ClientboundShowDialogPacket";
    private static final String CLASS_CONFIGURATION_LISTENER = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
    private static final String CLASS_PACKET = "net.minecraft.network.protocol.Packet";
    private static final String CLASS_HOLDER = "net.minecraft.core.Holder";
    private static final String CLASS_COMPONENT = "net.minecraft.network.chat.Component";
    private static final String CLASS_COMPONENT_SERIALIZATION = "net.minecraft.network.chat.ComponentSerialization";
    private static final String CLASS_NBT_OPS = "net.minecraft.nbt.NbtOps";
    private static final String CLASS_COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";
    private static final String CLASS_LIST_TAG = "net.minecraft.nbt.ListTag";
    private static final String CLASS_TAG = "net.minecraft.nbt.Tag";
    private static final String CLASS_DECODER = "com.mojang.serialization.Decoder";
    private static final String CLASS_ENCODER = "com.mojang.serialization.Encoder";
    private static final String CLASS_DYNAMIC_OPS = "com.mojang.serialization.DynamicOps";
    private static final String CLASS_DATA_RESULT = "com.mojang.serialization.DataResult";

    private static final long WAIT_TIMEOUT_MS = 30000L;
    private static final long POLL_INTERVAL_MS = 25L;

    private static final Object LOCK = new Object();
    private static final Map<SocketAddress, PendingDialog> PENDING = new ConcurrentHashMap<>();

    private static ScheduledExecutorService watcher;
    private static ScheduledFuture<?> watcherTask;
    private static Boolean supported;

    private MaintenanceDialog() {}

    /**
     * Checks if the current Minecraft version supports dialogs. The result is cached.
     */
    public static boolean isSupported() {
        if (supported == null) {
            try {
                Class.forName(CLASS_DIALOG);
                Class.forName(CLASS_SHOW_DIALOG_PACKET);
                Class.forName(CLASS_CONFIGURATION_LISTENER);
                Class.forName(CLASS_NBT_OPS);
                supported = true;
            } catch (Throwable t) {
                supported = false;
            }
        }

        return supported;
    }

    /**
     * Queues the player for a maintenance dialog. Returns true when the player should not be
     * disconnected, so the dialog can be shown during the configuration phase.
     */
    public static boolean hold(CraterGameServer server, CraterGameProfile profile, SocketAddress address) {
        if (server == null || !isSupported() || MaintenanceModeConfig.INSTANCE == null
                || !MaintenanceModeConfig.INSTANCE.isUseDialog()) {
            return false;
        }

        synchronized (LOCK) {
            // The pre-login event is fired twice (login + end of configuration). If an entry
            // already exists for this connection, the second call should kick the player.
            if (PENDING.containsKey(address)) {
                return false;
            }

            PENDING.put(address, new PendingDialog(profile, address));
            ensureWatcher(server);
            return true;
        }
    }

    /**
     * Stops the watcher and clears all pending dialogs. Called when the server stops.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            PENDING.clear();

            if (watcherTask != null) {
                watcherTask.cancel(false);
                watcherTask = null;
            }

            if (watcher != null) {
                watcher.shutdownNow();
                watcher = null;
            }
        }
    }

    private static void ensureWatcher(CraterGameServer server) {
        if (watcher == null || watcher.isShutdown()) {
            watcher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "MaintenanceMode-Dialog");
                thread.setDaemon(true);
                return thread;
            });
            watcherTask = null;
        }

        if (watcherTask == null || watcherTask.isCancelled() || watcherTask.isDone()) {
            watcherTask = watcher.scheduleWithFixedDelay(() -> runPoll(server), 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void runPoll(CraterGameServer server) {
        try {
            if (PENDING.isEmpty()) {
                synchronized (LOCK) {
                    if (PENDING.isEmpty()) {
                        if (watcherTask != null) {
                            watcherTask.cancel(false);
                            watcherTask = null;
                        }
                        if (watcher != null) {
                            watcher.shutdown();
                            watcher = null;
                        }
                        return;
                    }
                }
            }

            poll(server);
        } catch (Throwable t) {
            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                ModConstants.LOG.error("Maintenance dialog watcher failed", t);
            }
        }
    }

    private static void poll(CraterGameServer server) {
        Object serverHandle = server.unwrap();
        long now = System.currentTimeMillis();

        for (PendingDialog pending : PENDING.values()) {
            if (now > pending.deadline || !stillDenied(pending)) {
                PENDING.remove(pending.address);
                continue;
            }

            if (pending.dispatched.get()) {
                continue;
            }

            Object connection = findConnection(serverHandle, pending);
            if (connection == null) {
                continue;
            }

            Object listener = getPacketListener(connection);
            if (listener == null) {
                continue;
            }

            String listenerName = listener.getClass().getName();

            if (CLASS_CONFIGURATION_LISTENER.equals(listenerName)) {
                if (pending.dispatched.compareAndSet(false, true)) {
                    dispatchDialog(serverHandle, connection, listener);
                }
            } else if (listenerName.contains("ServerGamePacketListener")) {
                // Safety net: the player somehow made it into the game while maintenance denies them
                disconnectPlayer(listener);
                PENDING.remove(pending.address);
            }
        }
    }

    private static boolean stillDenied(PendingDialog pending) {
        try {
            MaintenanceModeConfig config = MaintenanceModeConfig.INSTANCE;
            return config != null && config.isEnabled() && CommonClass.isNotAllowedToJoin(pending.profile);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object findConnection(Object serverHandle, PendingDialog pending) {
        try {
            Object connectionListener = serverHandle.getClass().getMethod("getConnection").invoke(serverHandle);
            List<?> connections = (List<?>) connectionListener.getClass().getMethod("getConnections").invoke(connectionListener);

            if (connections == null) {
                return null;
            }

            if (pending.address != null) {
                for (Object connection : connections) {
                    Object address = connection.getClass().getMethod("getRemoteAddress").invoke(connection);
                    if (pending.address.equals(address)) {
                        return connection;
                    }
                }
            }

            // Fallback for proxies/weird addresses: match the game profile of configuration listeners
            for (Object connection : connections) {
                Object listener = getPacketListener(connection);
                if (listener != null && CLASS_CONFIGURATION_LISTENER.equals(listener.getClass().getName())
                        && matchesProfile(listener, pending.profile)) {
                    return connection;
                }
            }
        } catch (Throwable t) {
            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                ModConstants.LOG.error("Failed to locate player connection", t);
            }
        }

        return null;
    }

    private static Object getPacketListener(Object connection) {
        try {
            return connection.getClass().getMethod("getPacketListener").invoke(connection);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean matchesProfile(Object listener, CraterGameProfile profile) {
        try {
            Field field = listener.getClass().getDeclaredField("gameProfile");
            field.setAccessible(true);
            Object gameProfile = field.get(listener);

            if (gameProfile == null) {
                return false;
            }

            Object id;
            try {
                id = gameProfile.getClass().getMethod("id").invoke(gameProfile);
            } catch (NoSuchMethodException e) {
                id = gameProfile.getClass().getMethod("getId").invoke(gameProfile);
            }

            return profile.getId() != null && profile.getId().equals(id);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void dispatchDialog(Object serverHandle, Object connection, Object listener) {
        Object packet;

        try {
            packet = createDialogPacket();
        } catch (Throwable t) {
            // The dialog could not be created, so leave the configuration running. The second
            // pre-login check will kick the player with the normal text message.
            ModConstants.LOG.error("Failed to create the maintenance dialog: {}", t.getMessage());
            return;
        }

        executeOnServer(serverHandle, () -> {
            try {
                Method send = connection.getClass().getMethod("send", Class.forName(CLASS_PACKET));
                send.invoke(connection, packet);
                clearConfigurationTasks(listener);
            } catch (Throwable t) {
                ModConstants.LOG.error("Failed to show the maintenance dialog: {}", t.getMessage());
            }
        });
    }

    private static void disconnectPlayer(Object listener) {
        try {
            Object message = Text.formatted(getMaintenanceMessage()).toGame();
            Method disconnect = listener.getClass().getMethod("disconnect", Class.forName(CLASS_COMPONENT));
            disconnect.invoke(listener, message);
        } catch (Throwable t) {
            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                ModConstants.LOG.error("Failed to disconnect player without dialog support", t);
            }
        }
    }

    private static void clearConfigurationTasks(Object listener) throws Exception {
        Field field = listener.getClass().getDeclaredField("configurationTasks");
        field.setAccessible(true);
        Queue<?> tasks = (Queue<?>) field.get(listener);

        if (tasks != null) {
            tasks.clear();
        }
    }

    private static void executeOnServer(Object serverHandle, Runnable runnable) {
        try {
            ((Executor) serverHandle).execute(runnable);
        } catch (Throwable t) {
            ModConstants.LOG.error("Failed to schedule the maintenance dialog: {}", t.getMessage());
        }
    }

    private static Object createDialogPacket() throws Exception {
        Object dialogTag = buildDialogTag();

        Object dialogCodec = Class.forName(CLASS_DIALOG).getField("CODEC").get(null);
        Object nbtOps = Class.forName(CLASS_NBT_OPS).getField("INSTANCE").get(null);
        Method parse = Class.forName(CLASS_DECODER).getMethod("parse", Class.forName(CLASS_DYNAMIC_OPS), Object.class);
        Object result = parse.invoke(dialogCodec, nbtOps, dialogTag);
        Object holder = Class.forName(CLASS_DATA_RESULT).getMethod("getOrThrow").invoke(result);

        return Class.forName(CLASS_SHOW_DIALOG_PACKET).getConstructor(Class.forName(CLASS_HOLDER)).newInstance(holder);
    }

    private static Object buildDialogTag() throws Exception {
        MaintenanceModeConfig config = MaintenanceModeConfig.INSTANCE;
        String title = config.getDialogTitle() == null || config.getDialogTitle().isEmpty()
                ? "Server is currently in maintenance mode" : config.getDialogTitle();

        List<MaintenanceModeConfig.DialogLink> links = config.getDialogLinks() == null
                ? List.of() : config.getDialogLinks().stream().filter(MaintenanceDialog::validLink).toList();

        Object dialog = newCompoundTag();
        putString(dialog, "type", links.isEmpty() ? "minecraft:notice" : "minecraft:multi_action");
        putTag(dialog, "title", encodeComponent(Text.formatted(title).toGame()));

        Object body = newListTag();
        Object plainMessage = newCompoundTag();
        putString(plainMessage, "type", "minecraft:plain_message");
        putTag(plainMessage, "contents", encodeComponent(Text.formatted(getMaintenanceMessage()).toGame()));
        putInt(plainMessage, "width", 300);
        addToList(body, plainMessage);
        putTag(dialog, "body", body);

        if (!links.isEmpty()) {
            Object actions = newListTag();

            for (MaintenanceModeConfig.DialogLink link : links) {
                Object button = newCompoundTag();
                putTag(button, "label", encodeComponent(Text.formatted(link.getLabel()).toGame()));

                Object action = newCompoundTag();
                putString(action, "type", "minecraft:open_url");
                putString(action, "url", link.getUrl());

                putTag(button, "action", action);
                addToList(actions, button);
            }

            putTag(dialog, "actions", actions);
        }

        return dialog;
    }

    private static boolean validLink(MaintenanceModeConfig.DialogLink link) {
        if (link == null || link.getLabel() == null || link.getLabel().isEmpty()
                || link.getUrl() == null || link.getUrl().isEmpty()) {
            return false;
        }

        try {
            new URI(link.getUrl());
            return true;
        } catch (Exception e) {
            ModConstants.LOG.warn("Skipping invalid maintenance dialog link: {}", link.getUrl());
            return false;
        }
    }

    private static String getMaintenanceMessage() {
        String message = MaintenanceModeConfig.INSTANCE.getMessage();

        if (message == null || message.isEmpty()) {
            message = "Server is currently undergoing maintenance. Please try connecting again later";
        }

        return message;
    }

    private static Object encodeComponent(Object component) throws Exception {
        Object codec = Class.forName(CLASS_COMPONENT_SERIALIZATION).getField("CODEC").get(null);
        Object nbtOps = Class.forName(CLASS_NBT_OPS).getField("INSTANCE").get(null);
        Method encodeStart = Class.forName(CLASS_ENCODER).getMethod("encodeStart", Class.forName(CLASS_DYNAMIC_OPS), Object.class);
        Object result = encodeStart.invoke(codec, nbtOps, component);

        return Class.forName(CLASS_DATA_RESULT).getMethod("getOrThrow").invoke(result);
    }

    private static Object newCompoundTag() throws Exception {
        return Class.forName(CLASS_COMPOUND_TAG).getConstructor().newInstance();
    }

    private static Object newListTag() throws Exception {
        return Class.forName(CLASS_LIST_TAG).getConstructor().newInstance();
    }

    private static void putString(Object compound, String key, String value) throws Exception {
        Class.forName(CLASS_COMPOUND_TAG).getMethod("putString", String.class, String.class).invoke(compound, key, value);
    }

    private static void putInt(Object compound, String key, int value) throws Exception {
        Class.forName(CLASS_COMPOUND_TAG).getMethod("putInt", String.class, int.class).invoke(compound, key, value);
    }

    private static void putTag(Object compound, String key, Object value) throws Exception {
        Class.forName(CLASS_COMPOUND_TAG).getMethod("put", String.class, Class.forName(CLASS_TAG)).invoke(compound, key, value);
    }

    private static void addToList(Object list, Object value) throws Exception {
        int size = (int) list.getClass().getMethod("size").invoke(list);
        list.getClass().getMethod("add", int.class, Class.forName(CLASS_TAG)).invoke(list, size, value);
    }

    private static final class PendingDialog {
        private final CraterGameProfile profile;
        private final SocketAddress address;
        private final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        private final AtomicBoolean dispatched = new AtomicBoolean(false);

        private PendingDialog(CraterGameProfile profile, SocketAddress address) {
            this.profile = profile;
            this.address = address;
        }
    }
}
