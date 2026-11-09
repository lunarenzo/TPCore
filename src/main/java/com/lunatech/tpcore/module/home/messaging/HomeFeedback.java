package com.lunatech.tpcore.module.home.messaging;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.economy.HomeEconomy;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.util.MessageFormatter;
import java.math.BigDecimal;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HomeFeedback {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private HomeFeedback() {}

    public static void showStatus(Player player, HomeConfig config, String operation, String home, HomeResultStatus status, int limit, long seconds) {
        var messages = config.messages();
        String template = switch (status) {
            case SUCCESS, SAVED_UNSAFE -> switch (operation) {
                case "set" -> messages.setHomeSuccess();
                case "delete" -> config.runtime().message("archived");
                case "restore" -> config.runtime().message("restored");
                default -> messages.teleportSuccess();
            };
            case HOME_CHANGED -> config.gui().messages().get("home-changed");
            case HOME_NOT_FOUND -> messages.homeNotFound();
            case OVERWRITE_REQUIRED -> operation.equals("restore") ? config.runtime().message("restore-conflict") : messages.setHomeConfirm();
            case LIMIT_REACHED -> messages.limitReached();
            case WORLD_RESTRICTED -> operation.equals("set") ? messages.worldRestricted() : config.runtime().message("travel-world-restricted");
            case UNSAFE_LOCATION -> messages.unsafeLocation();
            case COOLDOWN_ACTIVE -> messages.cooldownActive();
            case DISABLED -> messages.moduleDisabled();
            case MOVED -> messages.warmupCancelledMove();
            case DAMAGED -> messages.warmupCancelledDamage();
            case ECONOMY_UNAVAILABLE, INSUFFICIENT_FUNDS, PAYMENT_REJECTED, PAYMENT_PENDING ->
                config.economy().message(status.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            default -> config.runtime().message(status.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        };
        send(player, config, template, Placeholder.unparsed("home", home), Placeholder.unparsed("max", Integer.toString(limit)),
            Placeholder.unparsed("seconds", Long.toString(seconds)));
        if (status == HomeResultStatus.SAVED_UNSAFE)
            send(player, config, config.runtime().message("saved-unsafe"), Placeholder.unparsed("home", home));
    }

    public static void fee(Player player, HomeConfig config, BigDecimal fee) {
        if (fee.signum() > 0) send(player, config, config.economy().message("fee"),
            Placeholder.unparsed("amount", fee.toPlainString()));
    }

    public static String menuFee(Player player, HomeConfig config, boolean creation) {
        if (config.economy() == null || config.economy().cost(creation).signum() == 0) return "";
        BigDecimal fee = HomeEconomy.fee(player, config, creation);
        return config.economy().message("menu-fee").replace("<amount>", fee.toPlainString());
    }

    public static void send(CommandSender sender, HomeConfig config, String template, TagResolver... values) {
        if (template == null || template.isBlank()) return;
        TagResolver[] resolvers = new TagResolver[values.length + 1];
        resolvers[0] = Placeholder.parsed("prefix", config.messages().prefix());
        System.arraycopy(values, 0, resolvers, 1, values.length);
        sender.sendMessage(MessageFormatter.parse(MINI, template.contains("<prefix>") ? template : config.messages().prefix() + template, resolvers));
    }
}
