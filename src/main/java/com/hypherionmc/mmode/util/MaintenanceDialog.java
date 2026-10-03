package com.hypherionmc.mmode.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypherionmc.craterlib.api.game.authlib.CraterGameProfile;
import com.hypherionmc.craterlib.api.game.server.CraterGameServer;
import com.hypherionmc.craterlib.api.game.text.Text;
import com.hypherionmc.mmode.CommonClass;
import com.hypherionmc.mmode.ModConstants;
import com.hypherionmc.mmode.config.MaintenanceModeConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
 * old versions where dialogs do not exist. Official (Mojang) class and method names are used
 * first. Fabric/Quilt 1.21.6 - 1.21.11 use intermediary names, so those are included as a
 * fallback. Intermediary names are stable between those versions.
 */
public final class MaintenanceDialog {

    private static final String CLASS_DIALOG = "net.minecraft.server.dialog.Dialog";
    private static final String CLASS_DIALOG_INTERMEDIARY = "net.minecraft.class_11419";
    private static final String CLASS_SHOW_DIALOG_PACKET = "net.minecraft.network.protocol.common.ClientboundShowDialogPacket";
    private static final String CLASS_SHOW_DIALOG_PACKET_INTERMEDIARY = "net.minecraft.class_11407";
    private static final String CLASS_HOLDER = "net.minecraft.core.Holder";
    private static final String CLASS_HOLDER_INTERMEDIARY = "net.minecraft.class_6880";
    private static final String CLASS_PACKET = "net.minecraft.network.protocol.Packet";
    private static final String CLASS_PACKET_INTERMEDIARY = "net.minecraft.class_2596";
    private static final String CLASS_CONFIGURATION_LISTENER = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
    private static final String CLASS_CONFIGURATION_LISTENER_INTERMEDIARY = "net.minecraft.class_8610";

    private static final String METHOD_GET_CONNECTION = "getConnection";
    private static final String METHOD_GET_CONNECTION_INTERMEDIARY = "method_3787";
    private static final String METHOD_GET_CONNECTIONS_INTERMEDIARY = "method_37909";
    private static final String METHOD_GET_REMOTE_ADDRESS = "getRemoteAddress";
    private static final String METHOD_GET_REMOTE_ADDRESS_INTERMEDIARY = "method_10755";
    private static final String METHOD_GET_PACKET_LISTENER = "getPacketListener";
    private static final String METHOD_GET_PACKET_LISTENER_INTERMEDIARY = "method_10744";
    private static final String METHOD_SEND = "send";
    private static final String METHOD_SEND_INTERMEDIARY = "method_10743";
    private static final String METHOD_DISCONNECT = "disconnect";
    private static final String METHOD_DISCONNECT_INTERMEDIARY = "method_52396";

    private static final String CLASS_GAME_PROFILE = "com.mojang.authlib.GameProfile";
    private static final String CLASS_COMPONENT = "net.minecraft.network.chat.Component";
    private static final String CLASS_COMPONENT_INTERMEDIARY = "net.minecraft.class_2561";
    private static final String CLASS_CUSTOM_ACTION_PACKET = "net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket";
    private static final String CLASS_CUSTOM_ACTION_PACKET_INTERMEDIARY = "net.minecraft.class_11411";

    private static final String CHANNEL_HANDLER_NAME = "packet_handler";
    private static final String CLOSE_PROBE_NAME = "mmode_dialog_close";
    private static final String CLOSE_ACTION_ID = "mmode:close";

    private static final String DFU_CODEC = "com.mojang.serialization.Codec";
    private static final String DFU_DECODER = "com.mojang.serialization.Decoder";
    private static final String DFU_DYNAMIC_OPS = "com.mojang.serialization.DynamicOps";
    private static final String DFU_JSON_OPS = "com.mojang.serialization.JsonOps";
    private static final String DFU_DATA_RESULT = "com.mojang.serialization.DataResult";

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
                resolveClass(CLASS_DIALOG, CLASS_DIALOG_INTERMEDIARY);
                resolveClass(CLASS_SHOW_DIALOG_PACKET, CLASS_SHOW_DIALOG_PACKET_INTERMEDIARY);
                resolveClass(CLASS_CONFIGURATION_LISTENER, CLASS_CONFIGURATION_LISTENER_INTERMEDIARY);
                Class.forName(DFU_JSON_OPS);
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
            if (pending.dispatched.get()) {
                if (now >= pending.kickAt) {
                    disconnectHeldConnection(pending);
                    PENDING.remove(pending.address);
                }
                continue;
            }

            if (now > pending.deadline || !stillDenied(pending)) {
                PENDING.remove(pending.address);
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

            if (isConfigurationListener(listener)) {
                if (pending.dispatched.compareAndSet(false, true)) {
                    dispatchDialog(serverHandle, connection, listener, pending);
                }
            } else if (isPlayerOnline(server, pending.profile)) {
                // Safety net: the player somehow made it into the game while maintenance denies them
                disconnectOnlinePlayer(server, pending.profile);
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
            Object connectionListener = findMethod(serverHandle.getClass(), METHOD_GET_CONNECTION, METHOD_GET_CONNECTION_INTERMEDIARY).invoke(serverHandle);
            List<?> connections = (List<?>) findMethod(connectionListener.getClass(), "getConnections", METHOD_GET_CONNECTIONS_INTERMEDIARY).invoke(connectionListener);

            if (connections == null) {
                return null;
            }

            if (pending.address != null) {
                for (Object connection : connections) {
                    Object address = findMethod(connection.getClass(), METHOD_GET_REMOTE_ADDRESS, METHOD_GET_REMOTE_ADDRESS_INTERMEDIARY).invoke(connection);
                    if (pending.address.equals(address)) {
                        return connection;
                    }
                }
            }

            // Fallback for proxies/weird addresses: match the game profile of configuration listeners
            for (Object connection : connections) {
                Object listener = getPacketListener(connection);
                if (listener != null && isConfigurationListener(listener) && matchesProfile(listener, pending.profile)) {
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
            return findMethod(connection.getClass(), METHOD_GET_PACKET_LISTENER, METHOD_GET_PACKET_LISTENER_INTERMEDIARY).invoke(connection);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isConfigurationListener(Object listener) {
        String name = listener.getClass().getName();
        return CLASS_CONFIGURATION_LISTENER.equals(name) || CLASS_CONFIGURATION_LISTENER_INTERMEDIARY.equals(name);
    }

    private static boolean isPlayerOnline(CraterGameServer server, CraterGameProfile profile) {
        try {
            return server.getPlayers().stream()
                    .anyMatch(player -> profile.getId() != null && profile.getId().equals(player.getUUID()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void disconnectOnlinePlayer(CraterGameServer server, CraterGameProfile profile) {
        try {
            server.getPlayers().stream()
                    .filter(player -> profile.getId() != null && profile.getId().equals(player.getUUID()))
                    .findFirst()
                    .ifPresent(player -> player.disconnect(Text.formatted(getMaintenanceMessage())));
        } catch (Throwable t) {
            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                ModConstants.LOG.error("Failed to disconnect player without dialog support", t);
            }
        }
    }

    private static boolean matchesProfile(Object listener, CraterGameProfile profile) {
        try {
            Field field = findFieldByType(listener.getClass(), Class.forName(CLASS_GAME_PROFILE));

            if (field == null) {
                return false;
            }

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

    private static void dispatchDialog(Object serverHandle, Object connection, Object listener, PendingDialog pending) {
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
                Class<?> packetClass = resolveClass(CLASS_PACKET, CLASS_PACKET_INTERMEDIARY);
                Method send = findMethod(connection.getClass(), METHOD_SEND, METHOD_SEND_INTERMEDIARY, packetClass);
                send.invoke(connection, packet);
                clearConfigurationTasks(listener);

                pending.connection = connection;
                pending.listener = listener;

                int timeout = MaintenanceModeConfig.INSTANCE.getDialogTimeout();
                pending.kickAt = timeout > 0 ? System.currentTimeMillis() + timeout * 1000L : Long.MAX_VALUE;

                installCloseProbe(serverHandle, pending);
            } catch (Throwable t) {
                ModConstants.LOG.error("Failed to show the maintenance dialog: {}", t.getMessage());
            }
        });
    }

    /**
     * Watches the connection for the custom click action that the dialog's close button sends.
     * That way the player gets kicked as soon as the dialog is closed instead of being held in
     * the configuration phase.
     */
    private static void installCloseProbe(Object serverHandle, PendingDialog pending) {
        try {
            Object connection = pending.connection;
            Field channelField = connection == null ? null : findFieldByType(connection.getClass(), Class.forName("io.netty.channel.Channel"));
            Channel channel = channelField == null ? null : (Channel) channelField.get(connection);

            if (channel == null || !channel.isOpen()) {
                return;
            }

            ChannelPipeline pipeline = channel.pipeline();

            if (pipeline.get(CLOSE_PROBE_NAME) != null || pipeline.get(CHANNEL_HANDLER_NAME) == null) {
                return;
            }

            pipeline.addBefore(CHANNEL_HANDLER_NAME, CLOSE_PROBE_NAME, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        String packetClass = msg.getClass().getName();

                        if (CLASS_CUSTOM_ACTION_PACKET.equals(packetClass) || CLASS_CUSTOM_ACTION_PACKET_INTERMEDIARY.equals(packetClass)) {
                            ctx.pipeline().remove(this);

                            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                                ModConstants.LOG.info("Maintenance dialog closed by the player, disconnecting");
                            }

                            onDialogClosed(serverHandle, pending);
                        }
                    } catch (Throwable ignored) {
                    }

                    super.channelRead(ctx, msg);
                }
            });
        } catch (Throwable t) {
            if (MaintenanceModeConfig.INSTANCE != null && MaintenanceModeConfig.INSTANCE.isDebug()) {
                ModConstants.LOG.error("Failed to install the dialog close listener", t);
            }
        }
    }

    private static void onDialogClosed(Object serverHandle, PendingDialog pending) {
        executeOnServer(serverHandle, () -> {
            if (PENDING.remove(pending.address) != null) {
                disconnectHeldConnection(pending);
            }
        });
    }

    private static void disconnectHeldConnection(PendingDialog pending) {
        try {
            Object listener = pending.listener;

            if (listener == null) {
                return;
            }

            Class<?> componentClass = resolveClass(CLASS_COMPONENT, CLASS_COMPONENT_INTERMEDIARY);
            Method disconnect = findMethod(listener.getClass(), METHOD_DISCONNECT, METHOD_DISCONNECT_INTERMEDIARY, componentClass);
            disconnect.invoke(listener, Text.formatted(getMaintenanceMessage()).toGame());
        } catch (Throwable t) {
            ModConstants.LOG.error("Failed to disconnect player after closing the maintenance dialog: {}", t.getMessage());
        }
    }

    private static void clearConfigurationTasks(Object listener) throws Exception {
        Field field = findFieldByType(listener.getClass(), Queue.class);

        if (field == null) {
            throw new NoSuchFieldException("configurationTasks");
        }

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
        Class<?> dialogClass = resolveClass(CLASS_DIALOG, CLASS_DIALOG_INTERMEDIARY);
        Class<?> holderClass = resolveClass(CLASS_HOLDER, CLASS_HOLDER_INTERMEDIARY);

        Object codec = findDialogCodec(dialogClass, holderClass);
        Object holder = parse(codec, buildDialogJson());

        Class<?> packetClass = resolveClass(CLASS_SHOW_DIALOG_PACKET, CLASS_SHOW_DIALOG_PACKET_INTERMEDIARY);
        return packetClass.getConstructor(holderClass).newInstance(holder);
    }

    private static JsonObject buildDialogJson() {
        MaintenanceModeConfig config = MaintenanceModeConfig.INSTANCE;
        String title = config.getDialogTitle() == null || config.getDialogTitle().isEmpty()
                ? "Server is currently in maintenance mode" : config.getDialogTitle();

        List<MaintenanceModeConfig.DialogLink> links = config.getDialogLinks() == null
                ? List.of() : config.getDialogLinks().stream().filter(MaintenanceDialog::validLink).toList();

        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", links.isEmpty() ? "minecraft:notice" : "minecraft:multi_action");
        dialog.addProperty("pause", false);
        dialog.addProperty("after_action", "none");
        dialog.add("title", Text.formatted(title).toJson());

        JsonObject closeAction = new JsonObject();
        closeAction.addProperty("type", "minecraft:custom");
        closeAction.addProperty("id", CLOSE_ACTION_ID);

        JsonObject closeButton = new JsonObject();
        closeButton.add("label", Text.translatable("gui.done").toJson());
        closeButton.add("action", closeAction);

        JsonObject plainMessage = new JsonObject();
        plainMessage.addProperty("type", "minecraft:plain_message");
        plainMessage.add("contents", Text.formatted(getMaintenanceMessage()).toJson());
        plainMessage.addProperty("width", 300);

        JsonArray body = new JsonArray();
        body.add(plainMessage);
        dialog.add("body", body);

        if (links.isEmpty()) {
            dialog.add("action", closeButton);
        } else {
            JsonArray actions = new JsonArray();

            for (MaintenanceModeConfig.DialogLink link : links) {
                JsonObject action = new JsonObject();
                action.addProperty("type", "minecraft:open_url");
                action.addProperty("url", link.getUrl());

                JsonObject button = new JsonObject();
                button.add("label", Text.formatted(link.getLabel()).toJson());
                button.add("action", action);

                actions.add(button);
            }

            dialog.add("actions", actions);
            dialog.add("exit_action", closeButton);
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

    private static Object findDialogCodec(Class<?> dialogClass, Class<?> holderClass) throws Exception {
        Class<?> codecClass = Class.forName(DFU_CODEC);

        for (Field field : dialogClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != codecClass) {
                continue;
            }

            Type genericType = field.getGenericType();

            if (!(genericType instanceof ParameterizedType codecType) || codecType.getActualTypeArguments().length != 1) {
                continue;
            }

            Type argument = codecType.getActualTypeArguments()[0];

            if (!(argument instanceof ParameterizedType holderType) || holderType.getRawType() != holderClass) {
                continue;
            }

            Type[] holderArguments = holderType.getActualTypeArguments();

            if (holderArguments.length != 1 || holderArguments[0] != dialogClass) {
                continue;
            }

            field.setAccessible(true);
            return field.get(null);
        }

        throw new NoSuchFieldException("Dialog.CODEC");
    }

    private static Object parse(Object codec, Object json) throws Exception {
        Class<?> dynamicOpsClass = Class.forName(DFU_DYNAMIC_OPS);
        Object jsonOps = Class.forName(DFU_JSON_OPS).getField("INSTANCE").get(null);
        Method parse = Class.forName(DFU_DECODER).getMethod("parse", dynamicOpsClass, Object.class);
        Object result = parse.invoke(codec, jsonOps, json);

        return Class.forName(DFU_DATA_RESULT).getMethod("getOrThrow").invoke(result);
    }

    private static Class<?> resolveClass(String officialName, String intermediaryName) throws ClassNotFoundException {
        try {
            return Class.forName(officialName);
        } catch (ClassNotFoundException e) {
            return Class.forName(intermediaryName);
        }
    }

    private static Method findMethod(Class<?> owner, String officialName, String intermediaryName, Class<?>... parameters) throws NoSuchMethodException {
        try {
            return owner.getMethod(officialName, parameters);
        } catch (NoSuchMethodException e) {
            return owner.getMethod(intermediaryName, parameters);
        }
    }

    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }

        return null;
    }

    private static final class PendingDialog {
        private final CraterGameProfile profile;
        private final SocketAddress address;
        private final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        private final AtomicBoolean dispatched = new AtomicBoolean(false);
        private volatile Object connection;
        private volatile Object listener;
        private volatile long kickAt = Long.MAX_VALUE;

        private PendingDialog(CraterGameProfile profile, SocketAddress address) {
            this.profile = profile;
            this.address = address;
        }
    }
}
