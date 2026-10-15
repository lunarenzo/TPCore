package com.lunatech.tpcore.module.warp.service;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.cache.impl.BoundedExpiryCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpSignBinding;
import com.lunatech.tpcore.util.MessageFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Block operations are called only from their owning interaction/event context. No sign registry or world scans. */
public final class WarpSignService {
    public enum Mode { CREATE, REMOVE, INFO }
    private record Selection(Mode mode, String name, long createdAt, UUID creator, long expiresAt) {}
    private final Supplier<WarpConfig> config;
    private final Supplier<WarpService> services;
    private final BiConsumer<Player, String> teleport;
    private final NamespacedKey front;
    private final NamespacedKey back;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final AtomicReference<Map<UUID, Selection>> selections = new AtomicReference<>(Map.of());
    private volatile BoundedExpiryCache clicks;
    private final Predicate<Material> signTypes;

    public WarpSignService(Plugin plugin, Supplier<WarpConfig> config, Supplier<WarpService> services, BiConsumer<Player, String> teleport) {
        this(plugin, config, services, teleport, Tag.ALL_SIGNS::isTagged);
    }

    WarpSignService(Plugin plugin, Supplier<WarpConfig> config, Supplier<WarpService> services,
        BiConsumer<Player, String> teleport, Predicate<Material> signTypes) {
        this.config = config; this.services = services; this.teleport = teleport;
        this.signTypes = signTypes;
        front = new NamespacedKey(plugin, "warp_sign_front"); back = new NamespacedKey(plugin, "warp_sign_back");
        clicks = new BoundedExpiryCache(config.get().maxPlayerStateEntries());
    }

    public boolean allowed(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission(Permissions.WARP_ADMIN);
    }

    public boolean canManage(CommandSender sender) {
        return allowed(sender, Permissions.WARP_SIGN_CREATE) || allowed(sender, Permissions.WARP_SIGN_REMOVE) || allowed(sender, Permissions.WARP_SIGN_INFO);
    }

    private String permission(Mode mode) {
        return switch (mode) { case CREATE -> Permissions.WARP_SIGN_CREATE; case REMOVE -> Permissions.WARP_SIGN_REMOVE; case INFO -> Permissions.WARP_SIGN_INFO; };
    }

    public void help(CommandSender sender) { message(sender, "usage"); }
    public void cancel(CommandSender sender) {
        if (sender instanceof Player player) forget(player.getUniqueId());
        message(sender, "cancelled");
    }

    public void prepare(CommandSender sender, Mode mode, String name) {
        WarpConfig settings = config.get();
        if (!(sender instanceof Player player)) { raw(sender, settings.messages().onlyPlayers()); return; }
        forget(player.getUniqueId());
        if (!allowed(sender, permission(mode))) { message(sender, "no-permission"); return; }
        Warp warp = null;
        if (mode == Mode.CREATE) {
            if (!enabled()) { message(sender, "disabled"); return; }
            WarpService service = services.get();
            if (service == null || !service.isReady()) { raw(sender, settings.messages().storageNotReady()); return; }
            warp = service.getWarp(name).orElse(null);
            if (warp == null) { raw(sender, settings.messages().warpNotFound(), Placeholder.unparsed("warp", name)); return; }
            if (!warp.name().matches("[A-Za-z0-9_-]{1,32}")) { raw(sender, settings.messages().invalidWarpName()); return; }
        }
        long now = System.nanoTime();
        Selection selection = new Selection(mode, warp == null ? "" : warp.name(), warp == null ? 0 : warp.createdAt(),
            warp == null ? null : warp.creatorUuid(), now + TimeUnit.SECONDS.toNanos(settings.signs().selectionTimeoutSeconds()));
        for (;;) {
            Map<UUID, Selection> previous = selections.get();
            Map<UUID, Selection> next = new HashMap<>(previous);
            next.values().removeIf(value -> value.expiresAt() <= now);
            if (next.size() >= settings.maxPlayerStateEntries() && !next.containsKey(player.getUniqueId())) { message(sender, "busy"); return; }
            next.put(player.getUniqueId(), selection);
            if (selections.compareAndSet(previous, Map.copyOf(next))) break;
        }
        String action = settings.signs().messages().get(switch (mode) { case CREATE -> "create-action"; case REMOVE -> "remove-action"; case INFO -> "info-action"; });
        raw(sender, settings.signs().messages().get("selected"), Placeholder.component("action", MessageFormatter.parse(miniMessage, action,
            Placeholder.unparsed("warp", selection.name()))), Placeholder.unparsed("seconds", Integer.toString(settings.signs().selectionTimeoutSeconds())));
    }

    private boolean enabled() { WarpConfig settings = config.get(); return settings.enabled() && settings.signs().enabled(); }
    private NamespacedKey key(Side side) { return side == Side.FRONT ? front : back; }

    public boolean managed(Sign sign, Side side) { return sign.getPersistentDataContainer().has(key(side)); }
    public boolean managed(Sign sign) { return managed(sign, Side.FRONT) || managed(sign, Side.BACK); }

    public Sign sign(Block block) {
        return block != null && signTypes.test(block.getType()) && block.getState() instanceof Sign sign ? sign : null;
    }

    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Selection selection = selections.get().get(id);
        if (selection != null && selection.expiresAt() <= System.nanoTime()) {
            event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
            forget(id); message(player, "expired"); return;
        }
        Block block = event.getClickedBlock();
        Sign sign = sign(block);
        if (sign == null) {
            if (selection != null) message(player, "not-sign");
            return;
        }
        Side side = sign.getInteractableSideFor(player);
        if (selection == null && !managed(sign, side)) return;
        event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
        if (selection != null) {
            forget(id);
            if (!allowed(player, permission(selection.mode()))) { message(player, "no-permission"); return; }
            if (selection.mode() == Mode.CREATE) create(player, block, sign, side, selection);
            else if (selection.mode() == Mode.REMOVE) remove(player, sign, side);
            else inspect(player, block, sign, side);
            return;
        }
        if (!enabled()) { message(player, "disabled"); return; }
        if (!allowed(player, Permissions.WARP_SIGN_USE)) { message(player, "no-permission"); return; }
        Long until = clicks.get(id);
        if (until != null) return;
        if (!clicks.put(id, System.currentTimeMillis() + config.get().signs().clickCooldownMillis())) { message(player, "busy"); return; }
        WarpSignBinding binding = binding(sign, side);
        WarpService service = services.get();
        if (service == null || !service.isReady()) { raw(player, config.get().messages().storageNotReady()); return; }
        if (binding == null || !binding.at(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())
            || !binding.matches(service.getWarp(binding.name()).orElse(null))) { message(player, "stale"); return; }
        // The command path rechecks base/per-warp permission, passwords, closure, policy, safety and payment.
        teleport.accept(player, binding.name());
    }

    private WarpSignBinding binding(Sign sign, Side side) {
        try { return WarpSignBinding.decode(sign.getPersistentDataContainer().get(key(side), PersistentDataType.STRING)).orElse(null); }
        catch (IllegalArgumentException malformed) { return null; }
    }

    private void create(Player player, Block block, Sign sign, Side side, Selection selection) {
        if (!enabled()) { message(player, "disabled"); return; }
        if (managed(sign, side)) { message(player, "already-bound"); return; }
        WarpService service = services.get();
        if (service == null || !service.isReady()) { raw(player, config.get().messages().storageNotReady()); return; }
        Warp warp = service.getWarp(selection.name()).orElse(null);
        if (warp == null || warp.createdAt() != selection.createdAt() || !Objects.equals(warp.creatorUuid(), selection.creator())) { message(player, "stale"); return; }
        WarpSignBinding binding = new WarpSignBinding(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), warp.name(), warp.createdAt(), warp.creatorUuid());
        var lines = config.get().signs().lines();
        for (int index = 0; index < 4; index++) sign.getSide(side).line(index, MessageFormatter.parse(miniMessage, lines.get(index), Placeholder.unparsed("warp", warp.name())));
        sign.getPersistentDataContainer().set(key(side), PersistentDataType.STRING, binding.encode());
        message(player, sign.update(false, false) ? "created" : "failed", Placeholder.unparsed("warp", warp.name()));
    }

    private void remove(Player player, Sign sign, Side side) {
        if (!managed(sign, side)) { message(player, "not-bound"); return; }
        sign.getPersistentDataContainer().remove(key(side));
        for (int index = 0; index < 4; index++) sign.getSide(side).line(index, MessageFormatter.parse(miniMessage, ""));
        message(player, sign.update(false, false) ? "removed" : "failed");
    }

    private void inspect(Player player, Block block, Sign sign, Side side) {
        if (!managed(sign, side)) { message(player, "not-bound"); return; }
        WarpSignBinding binding = binding(sign, side);
        if (binding == null) { message(player, "stale"); return; }
        WarpService service = services.get();
        boolean current = service != null && service.isReady() && binding.matches(service.getWarp(binding.name()).orElse(null))
            && binding.at(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        message(player, "info", Placeholder.unparsed("warp", binding.name()), Placeholder.unparsed("valid", Boolean.toString(current)));
    }

    public void forget(UUID id) {
        for (;;) {
            Map<UUID, Selection> previous = selections.get();
            if (!previous.containsKey(id)) break;
            Map<UUID, Selection> next = new HashMap<>(previous); next.remove(id);
            if (selections.compareAndSet(previous, Map.copyOf(next))) break;
        }
        clicks.remove(id);
    }

    public void invalidate() { selections.set(Map.of()); clicks = new BoundedExpiryCache(config.get().maxPlayerStateEntries()); }
    public void message(CommandSender sender, String key, TagResolver... values) { raw(sender, config.get().signs().messages().get(key), values); }
    private void raw(CommandSender sender, String template, TagResolver... values) {
        TagResolver[] all = new TagResolver[values.length + 1];
        all[0] = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.get().messages().prefix()));
        System.arraycopy(values, 0, all, 1, values.length);
        sender.sendMessage(MessageFormatter.parse(miniMessage, template, all));
    }
}
