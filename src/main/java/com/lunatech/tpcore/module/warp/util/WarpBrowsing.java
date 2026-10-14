package com.lunatech.tpcore.module.warp.util;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpBrowsePage;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class WarpBrowsing {
    private static final Comparator<Warp> ALPHABETICAL = Comparator.comparing(Warp::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Warp::name);
    private WarpBrowsing() {}

    public static boolean visible(CommandSender sender, Warp warp) {
        return !warp.unlisted() && (!warp.permissionGated() || !(sender instanceof Player)
            || sender.hasPermission("tpcore.warp." + warp.name().toLowerCase(Locale.ROOT)) || sender.hasPermission(Permissions.WARP_ADMIN));
    }

    public static WarpBrowsePage page(Collection<Warp> source, Predicate<Warp> allowed, WarpBrowseQuery query, int perPage) {
        List<Warp> visible = source.stream().filter(allowed).sorted(ALPHABETICAL).toList();
        List<String> categories = visible.stream().map(Warp::category).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<Warp> matches = visible.stream().filter(warp -> query.category().isEmpty() || warp.category().equals(query.category()))
            .filter(warp -> warp.name().toLowerCase(Locale.ROOT).contains(query.search())).toList();
        int total = query.categories() ? categories.size() : matches.size();
        int size = Math.max(1, Math.min(perPage, Math.max(1, total)));
        int pages = Math.max(1, (total + size - 1) / size), page = Math.min(query.page(), pages);
        int start = Math.min((page - 1) * size, matches.size()), end = query.categories() ? start : Math.min(start + size, matches.size());
        return new WarpBrowsePage(query.withPage(page), matches.subList(start, end), categories, pages, total);
    }
}
