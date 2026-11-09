package com.lunatech.tpcore.module.home.gui;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.HomeEconomyConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.hook.HomeForms;
import com.lunatech.tpcore.module.home.listener.HomeMenuListener;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeMenuTest {
    @Test void bareHomeChoosesEmptyMainSoleTravelAndMultipleList() {
        try (var fixture = new Fixture()) {
            fixture.page(0, 1);
            fixture.menus.bareHome(fixture.player);
            fixture.tick();
            assertEquals(27, fixture.top.getSize());
            assertEquals(Material.RED_BED, fixture.materialAt(11));
            assertEquals(Material.BOOK, fixture.materialAt(15));

            fixture.page(1, 1);
            var entry = fixture.entry(0);
            when(fixture.service.teleport(fixture.player, entry)).thenReturn(CompletableFuture.completedFuture(HomeResultStatus.SUCCESS));
            fixture.menus.bareHome(fixture.player);
            fixture.tick();
            verify(fixture.service).teleport(fixture.player, entry);
            fixture.tick();

            fixture.page(29, 1);
            fixture.menus.bareHome(fixture.player);
            fixture.tick();
            assertEquals(54, fixture.top.getSize());
            assertEquals(Material.CYAN_BED, fixture.materialAt(10));
            assertEquals(Material.CYAN_BED, fixture.materialAt(43));
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, fixture.materialAt(45));
            assertEquals(Material.BARRIER, fixture.materialAt(49));
            assertEquals(Material.PLAYER_HEAD, fixture.materialAt(53));
            ItemStack bed = fixture.slots.get(fixture.top).get(10);
            verify(fixture.meta.get(bed)).itemName(argThat(value -> value.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE));
            fixture.page(29, 2);
            fixture.click(53, ClickType.LEFT);
            fixture.tick();
            fixture.tick();
            assertEquals(Material.PLAYER_HEAD, fixture.materialAt(45));
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, fixture.materialAt(53));
        }
    }

    @Test void deletionRequiresConfirmationAndRechecksPermission() {
        try (var fixture = new Fixture()) {
            fixture.page(1, 1);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            fixture.click(10, ClickType.RIGHT);
            assertEquals(54, fixture.top.getSize());
            fixture.tick();
            assertEquals(27, fixture.top.getSize());
            assertEquals(Material.LIME_DYE, fixture.materialAt(11));
            verify(fixture.service, never()).deleteHome(eq(fixture.player), any(HomePage.Entry.class));
            fixture.permissions.remove(Permissions.HOME_DEL);
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            verify(fixture.service, never()).deleteHome(eq(fixture.player), any(HomePage.Entry.class));

            fixture.permissions.add(Permissions.HOME_DEL);
            when(fixture.service.deleteHome(fixture.player, fixture.entry(0)))
                .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.SUCCESS));
            fixture.page(0, 1);
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            fixture.tick();
            fixture.tick();
            assertEquals(54, fixture.top.getSize());
            assertEquals(Material.PAPER, fixture.materialAt(22));
            verify(fixture.service).deleteHome(fixture.player, fixture.entry(0));
        }
    }

    @Test void inventoryEventsBlockTransfersAndRespectAnotherPluginsCancellation() {
        try (var fixture = new Fixture()) {
            fixture.page(1, 1);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            for (var click : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK)) {
                fixture.click(10, click);
            }
            fixture.click(54, ClickType.LEFT);
            var alreadyCancelled = fixture.event(10, ClickType.RIGHT);
            when(alreadyCancelled.isCancelled()).thenReturn(true);
            fixture.listener.click(alreadyCancelled);
            verify(alreadyCancelled).setCancelled(true);
            var drag = mock(InventoryDragEvent.class);
            when(drag.getInventory()).thenReturn(fixture.top);
            fixture.listener.drag(drag);
            verify(drag).setCancelled(true);
            assertTrue(fixture.tasks.isEmpty());
        }
    }

    @Test void delayedReadDoesNotReopenOnCloseQuitReloadOrExternalOpen() {
        for (int scenario = 0; scenario < 4; scenario++) {
            try (var fixture = new Fixture()) {
                fixture.page(29, 1);
                fixture.menus.homes(fixture.player);
                fixture.tick();
                var pending = new CompletableFuture<HomePage>();
                when(fixture.service.page(fixture.id, 2)).thenReturn(pending);
                fixture.click(53, ClickType.LEFT);
                fixture.tick();
                switch (scenario) {
                    case 0 -> fixture.menus.closed(fixture.id, fixture.holder());
                    case 1 -> fixture.menus.quit(fixture.id);
                    case 2 -> fixture.menus.invalidate();
                    case 3 -> fixture.menus.opened(fixture.id, null);
                    default -> throw new AssertionError();
                }
                pending.complete(new HomePage(29, 2, List.of(fixture.entry(28))));
                assertTrue(fixture.tasks.isEmpty());
                verify(fixture.player, times(1)).openInventory(any(Inventory.class));
            }
        }
    }

    @Test void namingPromptIsPrivateSingleUseAndHonorsCreateOnlyResult() {
        try (var fixture = new Fixture()) {
            fixture.page(0, 1);
            fixture.menus.main(fixture.player);
            fixture.tick();
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            assertNull(fixture.top);
            String command = fixture.clicks.stream().filter(event -> event.action() == ClickEvent.Action.SUGGEST_COMMAND)
                .map(ClickEvent::value).findFirst().orElseThrow();
            String token = command.trim().substring("/home gui set ".length());
            assertTrue(fixture.clicks.contains(ClickEvent.runCommand("/home gui set " + token + " home")));
            assertTrue(fixture.clicks.contains(ClickEvent.runCommand("/home gui cancel " + token)));
            assertTrue(fixture.messages.stream().anyMatch(message -> message.toString().contains("/sethome <unused-name>")));
            when(fixture.service.createHome(fixture.player, "home"))
                .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.OVERWRITE_REQUIRED));
            fixture.menus.submit(fixture.player, "wrong", "home");
            verify(fixture.service, never()).createHome(any(), anyString());
            fixture.menus.submit(fixture.player, token, "home");
            fixture.menus.submit(fixture.player, token, "home");
            fixture.tick();
            verify(fixture.service).createHome(fixture.player, "home");
            assertTrue(fixture.messages.stream().anyMatch(message -> message.toString().contains("-f")));
        }
    }

    @Test void anExpiredPromptCannotSave() {
        try (var fixture = new Fixture()) {
            fixture.page(0, 1);
            fixture.menus.main(fixture.player);
            fixture.tick();
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            String command = fixture.clicks.stream().filter(event -> event.action() == ClickEvent.Action.SUGGEST_COMMAND)
                .map(ClickEvent::value).findFirst().orElseThrow();
            String token = command.trim().substring("/home gui set ".length());
            fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            fixture.menus.submit(fixture.player, token, "base");
            verify(fixture.service, never()).createHome(any(), anyString());
            assertTrue(fixture.messages.stream().anyMatch(message -> message.toString().contains("expired")));
        }
    }

    @Test void bedrockDefaultSavesAfterOwnerHandoffAndOpensMyHomes() {
        try (var fixture = new Fixture()) {
            fixture.beginBedrock();
            assertEquals("home", fixture.forms.prompts.getFirst().value());
            assertTrue(fixture.clicks.isEmpty());
            fixture.page(1, 1);
            when(fixture.service.createHome(fixture.player, "home"))
                .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.SUCCESS));
            fixture.forms.submit("home");
            fixture.forms.submit("home");
            assertEquals(1, fixture.tasks.size());
            verify(fixture.service, never()).createHome(any(), anyString());
            fixture.tick();
            fixture.tick();
            fixture.tick();
            assertEquals("My Homes (1/1)", fixture.forms.menus.getLast().title());
            assertNull(fixture.top);
            verify(fixture.service).createHome(fixture.player, "home");
        }
    }

    @Test void lateBedrockSubmissionExplainsExpiryWithoutSaving() {
        try (var fixture = new Fixture()) {
            fixture.beginBedrock();
            fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            fixture.forms.submit("base");
            fixture.forms.submit("other");
            fixture.tick();
            verify(fixture.service, never()).createHome(any(), anyString());
            assertEquals(1, fixture.messages.stream().filter(message -> message.toString().contains("This Home form expired")).count());
        }
    }

    @Test void invalidAndDuplicateFormNamesRetryWithinTheOriginalDeadline() {
        try (var fixture = new Fixture()) {
            fixture.beginBedrock();
            fixture.forms.submit(" ");
            fixture.tick();
            assertTrue(fixture.forms.prompts.getLast().description().startsWith("Enter a name"));
            fixture.forms.submit("bad name");
            fixture.tick();
            assertTrue(fixture.forms.prompts.getLast().description().startsWith("Use 1–32"));
            verify(fixture.service, never()).createHome(any(), anyString());
            when(fixture.service.createHome(fixture.player, "base"))
                .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.OVERWRITE_REQUIRED));
            fixture.forms.submit("base");
            fixture.tick();
            fixture.tick();
            assertTrue(fixture.forms.prompts.getLast().description().startsWith("You already have"));
            assertEquals("base", fixture.forms.prompts.getLast().value());
            fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            fixture.forms.submit("new-base");
            fixture.tick();
            verify(fixture.service, never()).createHome(any(), eq("new-base"));
            assertEquals(4, fixture.forms.prompts.size());
        }
    }

    @Test void closeRevocationAndReloadPreventFormWrites() {
        for (int scenario = 0; scenario < 3; scenario++) {
            try (var fixture = new Fixture()) {
                fixture.beginBedrock();
                if (scenario == 0) fixture.forms.reply.accept(new HomeForms.Reply(HomeForms.Outcome.CLOSED, null));
                else if (scenario == 1) {
                    fixture.permissions.remove(Permissions.HOME_SET);
                    fixture.forms.submit("base");
                } else {
                    fixture.forms.submit("base");
                    fixture.menus.invalidate();
                }
                fixture.tick();
                assertNull(fixture.top);
                verify(fixture.service, never()).createHome(any(), anyString());
            }
        }
    }

    @Test void successfulCustomNameDoesNotReplaceAnExternalInventory() {
        try (var fixture = new Fixture()) {
            fixture.beginBedrock();
            var saved = new CompletableFuture<HomeResultStatus>();
            when(fixture.service.createHome(fixture.player, "my-base")).thenReturn(saved);
            fixture.forms.submit("my-base");
            fixture.tick();
            fixture.menus.opened(fixture.id, null);
            saved.complete(HomeResultStatus.SUCCESS);
            fixture.tick();
            assertTrue(fixture.tasks.isEmpty());
            verify(fixture.player, never()).openInventory(any(Inventory.class));
            verify(fixture.service).createHome(fixture.player, "my-base");
            assertTrue(fixture.messages.stream().anyMatch(message -> message.toString().contains("set to your current location")));
        }
    }

    @Test void formDeliveryFailureShowsATypedFallback() {
        try (var fixture = new Fixture()) {
            fixture.forms.delivers = false;
            fixture.beginBedrock();
            assertTrue(fixture.clicks.isEmpty());
            assertTrue(fixture.messages.stream().anyMatch(message -> message.toString().contains("/sethome <unused-name>")));
        }
    }

    @Test void bedrockBrowsePaginatesAndRechecksPermissionBeforeConfirmedDeletion() {
        try (var fixture = new Fixture()) {
            fixture.forms.supported = true;
            fixture.page(29, 1);
            fixture.page(29, 2);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            assertEquals("My Homes (1/2)", fixture.forms.menus.getLast().title());
            assertEquals("Next Page", fixture.forms.menus.getLast().buttons().get(28));
            fixture.forms.choose(28);
            fixture.tick();
            fixture.tick();
            assertEquals(List.of("base28", "Previous Page", "Back"), fixture.forms.menus.getLast().buttons());
            fixture.forms.choose(0);
            fixture.tick();
            assertEquals(List.of("Teleport", "Delete", "Back"), fixture.forms.menus.getLast().buttons());
            fixture.forms.choose(1);
            fixture.tick();
            assertEquals(List.of("Confirm deletion", "Cancel"), fixture.forms.menus.getLast().buttons());
            verify(fixture.service, never()).deleteHome(any(), any(HomePage.Entry.class));
            fixture.permissions.remove(Permissions.HOME_DEL);
            fixture.forms.choose(0);
            fixture.tick();
            verify(fixture.service, never()).deleteHome(any(), any(HomePage.Entry.class));
        }
    }

    @Test void failedBedrockMenuDeliveryFallsBackToInventory() {
        try (var fixture = new Fixture()) {
            fixture.forms.supported = true;
            fixture.forms.deliversMenu = false;
            fixture.page(0, 1);
            fixture.menus.main(fixture.player);
            fixture.tick();
            assertEquals(27, fixture.top.getSize());
        }
    }

    @Test void duplicateAndStaleBedrockSelectionsDoNotAct() {
        try (var fixture = new Fixture()) {
            fixture.forms.supported = true;
            fixture.page(1, 1);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            var first = fixture.forms.selection;
            first.accept(0);
            first.accept(0);
            assertEquals(1, fixture.tasks.size());
            fixture.tick();
            first.accept(0);
            assertTrue(fixture.tasks.isEmpty());
            fixture.forms.choose(0);
            fixture.menus.invalidate();
            fixture.tick();
            verify(fixture.service, never()).teleport(any(), any(HomePage.Entry.class));
        }
    }

    @Test void bedrockDeleteCancellationAndConfirmationUseTheCurrentPage() {
        try (var fixture = new Fixture()) {
            fixture.forms.supported = true;
            fixture.page(1, 1);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            fixture.forms.choose(0);
            fixture.tick();
            fixture.forms.choose(1);
            fixture.tick();
            fixture.forms.choose(1);
            fixture.tick();
            fixture.tick();
            assertEquals("My Homes (1/1)", fixture.forms.menus.getLast().title());
            verify(fixture.service, never()).deleteHome(any(), any(HomePage.Entry.class));

            var entry = fixture.entry(0);
            when(fixture.service.deleteHome(fixture.player, entry))
                .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.SUCCESS));
            fixture.forms.choose(0);
            fixture.tick();
            fixture.forms.choose(1);
            fixture.tick();
            fixture.page(0, 1);
            fixture.forms.choose(0);
            fixture.tick();
            fixture.tick();
            fixture.tick();
            verify(fixture.service).deleteHome(fixture.player, entry);
            assertEquals(List.of("Back"), fixture.forms.menus.getLast().buttons());
        }
    }

    private static final class Forms implements HomeForms {
        boolean supported;
        boolean delivers = true;
        boolean deliversMenu = true;
        final List<Prompt> prompts = new ArrayList<>();
        final List<Menu> menus = new ArrayList<>();
        Consumer<Reply> reply;
        Consumer<Integer> selection;
        @Override public boolean supports(UUID player) { return supported; }
        @Override public boolean send(UUID player, Prompt prompt, Consumer<Reply> response) {
            prompts.add(prompt);
            reply = response;
            return delivers;
        }
        @Override public boolean sendMenu(UUID player, Menu menu, Consumer<Integer> response) {
            menus.add(menu);
            selection = response;
            return deliversMenu;
        }
        void submit(String name) { reply.accept(new Reply(Outcome.SUBMITTED, name)); }
        void choose(int button) { selection.accept(button); }
    }

    @Test void javaAndBedrockMenusShowEffectivePricesAndHonorBypass() {
        try (var fixture = new Fixture()) {
            fixture.enablePricing();
            fixture.page(0, 1);
            fixture.menus.main(fixture.player);
            fixture.tick();
            ItemMeta button = fixture.meta.get(fixture.slots.get(fixture.top).get(11));
            verify(button).lore(argThat(lines -> lines.toString().contains("Fee: 8.25")));
        }
        try (var fixture = new Fixture()) {
            fixture.enablePricing();
            fixture.beginBedrock();
            assertTrue(fixture.forms.menus.getFirst().description().contains("Fee: 8.25"));
            assertTrue(fixture.forms.prompts.getFirst().description().contains("Fee: 8.25"));
        }
        try (var fixture = new Fixture()) {
            fixture.enablePricing();
            fixture.forms.supported = true;
            fixture.permissions.add(Permissions.HOME_BYPASS_TELEPORT_COST);
            fixture.page(1, 1);
            fixture.menus.homes(fixture.player);
            fixture.tick();
            fixture.forms.choose(0);
            fixture.tick();
            assertTrue(fixture.forms.menus.getLast().description().contains("Fee: 0"));
            assertFalse(fixture.forms.menus.getLast().description().contains("Fee: 4.50"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID();
        final Plugin plugin = mock(Plugin.class);
        final Player player = mock(Player.class);
        final DefaultHomeService service = mock(DefaultHomeService.class);
        HomeConfig config = HomeConfig.createDefault();
        final AtomicLong clock = new AtomicLong(1);
        final Forms forms = new Forms();
        final HomeMenus menus = new HomeMenus(plugin, () -> service, () -> config, clock::get, forms);
        final HomeMenuListener listener = new HomeMenuListener(menus);
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final Set<String> permissions = new HashSet<>(Set.of(Permissions.HOME_USE, Permissions.HOME_LIST,
            Permissions.HOME_SET, Permissions.HOME_DEL));
        final Map<Inventory, Map<Integer, ItemStack>> slots = new IdentityHashMap<>();
        final Map<ItemStack, Material> materials = new IdentityHashMap<>();
        final Map<ItemStack, ItemMeta> meta = new IdentityHashMap<>();
        final List<ClickEvent> clicks = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<ItemStack> items;
        Inventory top;

        Fixture() {
            var scheduler = mock(EntityScheduler.class);
            var task = mock(ScheduledTask.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getScheduler()).thenReturn(scheduler);
            when(player.hasPermission(anyString())).thenAnswer(call -> permissions.contains(call.getArgument(0)));
            when(scheduler.run(eq(plugin), any(), nullable(Runnable.class))).thenAnswer(call -> {
                Consumer<ScheduledTask> action = call.getArgument(1);
                tasks.add(() -> action.accept(task));
                return task;
            });
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class))).thenAnswer(call -> {
                var inventory = mock(Inventory.class);
                int size = call.getArgument(1);
                var holder = call.<InventoryHolder>getArgument(0);
                when(inventory.getHolder(false)).thenReturn(holder);
                when(inventory.getSize()).thenReturn(size);
                var contents = new HashMap<Integer, ItemStack>();
                slots.put(inventory, contents);
                doAnswer(set -> { contents.put(set.getArgument(0), set.getArgument(1)); return null; })
                    .when(inventory).setItem(anyInt(), any(ItemStack.class));
                return inventory;
            });
            items = mockConstruction(ItemStack.class, (item, context) -> {
                materials.put(item, (Material) context.arguments().getFirst());
                var itemMeta = mock(ItemMeta.class);
                meta.put(item, itemMeta);
                when(item.getItemMeta()).thenReturn(itemMeta);
            });
            when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
                if (top != null) menus.closed(id, holder());
                top = call.getArgument(0);
                menus.opened(id, holder());
                return mock(InventoryView.class);
            });
            doAnswer(call -> {
                if (top != null) menus.closed(id, holder());
                top = null;
                return null;
            }).when(player).closeInventory();
            doAnswer(call -> {
                Component component = call.getArgument(0);
                messages.add(component);
                collect(component);
                return null;
            }).when(player).sendMessage(any(Component.class));
        }

        private void collect(Component component) {
            if (component.clickEvent() != null) clicks.add(component.clickEvent());
            component.children().forEach(this::collect);
        }

        HomeMenuHolder holder() { return (HomeMenuHolder) top.getHolder(false); }
        void enablePricing() {
            config = spy(config);
            doReturn(new HomeEconomyConfig(true, "4.50", "8.25", 256, 512, Set.of(), Map.of())).when(config).economy();
        }
        Material materialAt(int slot) { return materials.get(slots.get(top).get(slot)); }
        HomePage.Entry entry(int index) {
            return new HomePage.Entry(index + 1, new Home(id, "base" + index, "world", null, 0, 64, 0, 0, 0, index));
        }
        void page(int count, long page) {
            int start = (int) ((page - 1) * 28);
            var entries = IntStream.range(start, Math.min(count, start + 28)).mapToObj(this::entry).toList();
            when(service.page(id, page)).thenReturn(CompletableFuture.completedFuture(new HomePage(count, page, entries)));
        }
        void tick() { tasks.remove().run(); }
        void beginBedrock() {
            forms.supported = true;
            page(0, 1);
            menus.main(player);
            tick();
            assertEquals(List.of("Set Home", "My Homes"), forms.menus.getLast().buttons());
            forms.choose(0);
            tick();
            assertNull(top);
            assertTrue(forms.prompts.isEmpty());
            tick();
        }
        InventoryClickEvent event(int slot, ClickType click) {
            var event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(top);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(slot);
            when(event.getClick()).thenReturn(click);
            return event;
        }
        void click(int slot, ClickType click) {
            var event = event(slot, click);
            listener.click(event);
            verify(event).setCancelled(true);
        }
        @Override public void close() { items.close(); bukkit.close(); }
    }
}
