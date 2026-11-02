package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lifestealplus.SettingsCatalog.Setting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * /lifesteal settings: every setting in config.yml as a chest menu. Click a feature to see its
 * settings, then click a setting to switch it, nudge it up or down, or type a new value in
 * chat. /lifesteal config &lt;setting&gt; [value] does the same from chat or the console. Either
 * way the change is written to config.yml, comments and all, and takes effect at once.
 * Presets (casual, standard, hardcore) change a batch at once, from the menu or /lifesteal preset.
 */
final class SettingsMenu implements Listener {

    private static final Duration TYPING_WINDOW = Duration.ofSeconds(60);
    private static final int WRAP = 42;

    /** One open page: the list of features (section null), one feature's settings, or the presets. */
    private static final class Page implements InventoryHolder {
        final String section;
        final boolean presets;
        final Map<Integer, String> slots = new HashMap<>();
        /** The preset clicked once on this page; a second click applies it. */
        String armed;
        Inventory inventory;

        Page(String section, boolean presets) {
            this.section = section;
            this.presets = presets;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Typing(Setting setting, Instant expires) {
    }

    private final LifestealPlugin plugin;
    private static final int PRESET_LINES = 12;

    private final SettingsCatalog catalog;
    private final Presets presets;
    private final Map<UUID, Typing> typing = new ConcurrentHashMap<>();

    SettingsMenu(LifestealPlugin plugin, SettingsCatalog catalog, Presets presets) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.presets = presets;
    }

    LiteralArgumentBuilder<CommandSourceStack> settingsNode() {
        return Commands.literal("settings").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            if (sender instanceof Player player) open(player, null);
            else Msg.send(sender, "settings.console");
            return Command.SINGLE_SUCCESS;
        });
    }

    LiteralArgumentBuilder<CommandSourceStack> configNode() {
        return Commands.literal("config")
                .then(Commands.argument("setting", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String typed = builder.getRemainingLowerCase();
                            catalog.all().stream().map(Setting::key).filter(k -> k.startsWith(typed)).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Setting setting = lookup(sender, StringArgumentType.getString(ctx, "setting"));
                            if (setting != null) describe(sender, setting);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    Setting setting = catalog.find(StringArgumentType.getString(ctx, "setting"));
                                    if (setting == null) return builder.buildFuture();
                                    if (setting.kind() == SettingsCatalog.Kind.FLAG) List.of("on", "off").forEach(builder::suggest);
                                    else builder.suggest(SettingsCatalog.show(current(setting)));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    Setting setting = lookup(sender, StringArgumentType.getString(ctx, "setting"));
                                    if (setting != null) typed(sender, setting, StringArgumentType.getString(ctx, "value"));
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    LiteralArgumentBuilder<CommandSourceStack> presetNode() {
        return Commands.literal("preset")
                .executes(ctx -> {
                    listPresets(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("preset", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            presets.all().stream().map(Presets.Preset::name).filter(n -> n.startsWith(builder.getRemainingLowerCase()))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Presets.Preset preset = findPreset(sender, StringArgumentType.getString(ctx, "preset"));
                            if (preset != null) previewPreset(sender, preset);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm").executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Presets.Preset preset = findPreset(sender, StringArgumentType.getString(ctx, "preset"));
                            if (preset != null) applyPreset(sender, preset);
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private Presets.Preset findPreset(CommandSender sender, String name) {
        Presets.Preset preset = presets.find(name);
        if (preset == null) Msg.send(sender, "preset.unknown", Msg.t("preset", name));
        return preset;
    }

    private void listPresets(CommandSender sender) {
        Msg.send(sender, "preset.list-header");
        for (Presets.Preset preset : presets.all()) {
            Msg.send(sender, preset.inUse(plugin.getConfig()) ? "preset.list-in-use" : "preset.list-entry",
                    Msg.t("preset", preset.name()), Msg.t("description", preset.description()));
        }
    }

    private void previewPreset(CommandSender sender, Presets.Preset preset) {
        Map<String, Object> changes = preset.changes(plugin.getConfig());
        if (changes.isEmpty()) {
            Msg.send(sender, "preset.in-use", Msg.t("preset", preset.name()));
            return;
        }
        Msg.send(sender, "preset.would-change", Msg.t("preset", preset.name()), Msg.t("n", changes.size()));
        changes.forEach((key, value) -> Msg.send(sender, "preset.change", Msg.t("setting", key),
                Msg.t("from", SettingsCatalog.show(plugin.getConfig().get(key))), Msg.t("to", SettingsCatalog.show(value))));
        Msg.send(sender, "preset.confirm", Msg.t("preset", preset.name()));
    }

    /** Writes every change the preset makes, or none of them, and says how it went. */
    private void applyPreset(CommandSender who, Presets.Preset preset) {
        Map<String, Object> changes = preset.changes(plugin.getConfig());
        if (changes.isEmpty()) {
            Msg.send(who, "preset.in-use", Msg.t("preset", preset.name()));
            return;
        }
        String problem = plugin.changeSettings(changes);
        if (problem != null) {
            Msg.send(who, "settings.refused", Msg.t("reason", problem));
            return;
        }
        Msg.send(who, "preset.applied", Msg.t("preset", preset.name()), Msg.t("n", changes.size()));
        plugin.getLogger().info(who.getName() + " applied the " + preset.name() + " preset, changing " + changes.size() + " settings.");
    }

    private Setting lookup(CommandSender sender, String key) {
        Setting setting = catalog.find(key.toLowerCase(Locale.ROOT));
        if (setting == null) Msg.send(sender, "settings.unknown", Msg.t("setting", key));
        return setting;
    }

    private void describe(CommandSender sender, Setting setting) {
        Msg.send(sender, "settings.current", Msg.t("setting", setting.key()), Msg.t("value", SettingsCatalog.show(current(setting))));
        for (String line : setting.description()) sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        if (setting.needsRestart()) Msg.send(sender, "settings.restart-note");
    }

    private Object current(Setting setting) {
        return plugin.getConfig().get(setting.key());
    }

    private boolean isOn(Setting setting) {
        return Boolean.TRUE.equals(current(setting));
    }

    /** Writes the change and says how it went. */
    private void change(CommandSender who, Setting setting, Object value) {
        String problem = plugin.changeSetting(setting.key(), value);
        if (problem != null) {
            Msg.send(who, "settings.refused", Msg.t("reason", problem));
            return;
        }
        String shown = SettingsCatalog.show(value);
        Msg.send(who, setting.needsRestart() ? "settings.changed-restart" : "settings.changed",
                Msg.t("setting", setting.key()), Msg.t("value", shown));
        plugin.getLogger().info(who.getName() + " set " + setting.key() + " to " + shown + ".");
    }

    private void typed(CommandSender who, Setting setting, String text) {
        Object value;
        try {
            value = SettingsCatalog.parse(setting, text);
        } catch (IllegalArgumentException e) {
            Msg.send(who, "settings.bad-value", Msg.t("setting", setting.key()), Msg.t("expected", e.getMessage()));
            return;
        }
        change(who, setting, value);
    }

    // The menu

    private void open(Player player, String section) {
        Page page = new Page(section, false);
        int entries = section == null ? catalog.sections().size() : catalog.in(section).size();
        int size = Math.min(54, (entries / 9 + 2) * 9);
        Component title = section == null ? Msg.get("settings.menu-title")
                : Msg.get("settings.section-title", Msg.t("name", SettingsCatalog.words(section)));
        page.inventory = org.bukkit.Bukkit.createInventory(page, size, title);
        draw(page);
        player.openInventory(page.inventory);
    }

    private void openPresets(Player player) {
        Page page = new Page(null, true);
        page.inventory = org.bukkit.Bukkit.createInventory(page, 27, Msg.get("settings.presets-title"));
        draw(page);
        player.openInventory(page.inventory);
    }

    private void draw(Page page) {
        Inventory inventory = page.inventory;
        inventory.clear();
        page.slots.clear();
        if (page.presets) {
            // Up to five presets, spaced out across the middle row.
            List<Presets.Preset> all = presets.all().subList(0, Math.min(5, presets.all().size()));
            for (int i = 0; i < all.size(); i++) {
                int slot = 13 - (all.size() - 1) + 2 * i;
                page.slots.put(slot, all.get(i).name());
                inventory.setItem(slot, presetIcon(all.get(i), all.get(i).name().equals(page.armed)));
            }
            inventory.setItem(inventory.getSize() - 5, icon(Material.ARROW, Msg.get("settings.back"), List.of()));
            return;
        }
        List<String> targets = page.section == null ? catalog.sections() : catalog.in(page.section).stream().map(Setting::key).toList();
        for (int slot = 0; slot < targets.size() && slot < inventory.getSize() - 9; slot++) {
            String target = targets.get(slot);
            page.slots.put(slot, target);
            inventory.setItem(slot, page.section == null ? sectionIcon(target) : settingIcon(catalog.find(target)));
        }
        inventory.setItem(inventory.getSize() - 5, page.section == null
                ? icon(Material.BARRIER, Msg.get("settings.close"), List.of())
                : icon(Material.ARROW, Msg.get("settings.back"), List.of()));
        if (page.section == null) inventory.setItem(inventory.getSize() - 4, presetsButton());
    }

    private ItemStack presetsButton() {
        String current = presets.all().stream().filter(p -> p.inUse(plugin.getConfig())).map(p -> SettingsCatalog.words(p.name()))
                .findFirst().orElse(null);
        List<Component> lore = new ArrayList<>(List.of(Msg.get("settings.presets-lore")));
        lore.add(current == null ? Msg.get("settings.presets-custom") : Msg.get("settings.presets-current", Msg.t("name", current)));
        lore.add(Component.empty());
        lore.add(Msg.get("settings.hint-open-presets"));
        return icon(Material.NETHER_STAR, Msg.get("settings.presets"), lore);
    }

    private ItemStack presetIcon(Presets.Preset preset, boolean armed) {
        Map<String, Object> changes = preset.changes(plugin.getConfig());
        String name = SettingsCatalog.words(preset.name());
        List<Component> lore = new ArrayList<>(text(List.of(preset.description())));
        lore.add(Component.empty());
        if (changes.isEmpty()) {
            lore.add(Msg.get("settings.preset-active"));
            return icon(presetMaterial(preset), Msg.get("settings.preset-in-use", Msg.t("name", name)), lore);
        }
        lore.add(Msg.get("settings.preset-changes", Msg.t("n", changes.size())));
        changes.entrySet().stream().limit(PRESET_LINES).forEach(change -> lore.add(Msg.get("settings.preset-change",
                Msg.t("setting", change.getKey()), Msg.t("from", SettingsCatalog.show(plugin.getConfig().get(change.getKey()))),
                Msg.t("to", SettingsCatalog.show(change.getValue())))));
        if (changes.size() > PRESET_LINES) lore.add(Msg.get("settings.preset-more", Msg.t("n", changes.size() - PRESET_LINES)));
        lore.add(Component.empty());
        lore.add(Msg.get(armed ? "settings.preset-confirm" : "settings.hint-preset"));
        return icon(presetMaterial(preset), Msg.get("settings.preset", Msg.t("name", name)), lore);
    }

    private static Material presetMaterial(Presets.Preset preset) {
        return switch (preset.name()) {
            case "casual" -> Material.POPPY;
            case "standard" -> Material.IRON_SWORD;
            case "hardcore" -> Material.WITHER_SKELETON_SKULL;
            default -> Material.PAPER;
        };
    }

    private ItemStack sectionIcon(String section) {
        Setting onOff = catalog.find(section + ".enabled");
        Component name = Msg.get("settings.section", Msg.t("name", SettingsCatalog.words(section)));
        List<Component> lore = new ArrayList<>(text(catalog.describe(section)));
        lore.add(Component.empty());
        lore.add(Msg.get("settings.hint-open"));
        if (onOff == null) return icon(Material.BOOK, name, lore);
        boolean on = isOn(onOff);
        lore.add(Msg.get("settings.hint-switch-section"));
        return icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                Msg.get(on ? "settings.section-on" : "settings.section-off", Msg.t("name", SettingsCatalog.words(section))), lore);
    }

    private ItemStack settingIcon(Setting setting) {
        Object value = current(setting);
        Component name = Msg.get("settings.setting", Msg.t("name", setting.label()), Msg.t("value", SettingsCatalog.show(value)));
        List<Component> lore = new ArrayList<>(text(setting.description()));
        if (setting.needsRestart()) lore.add(Msg.get("settings.restart-note"));
        lore.add(Component.empty());
        Material material = switch (setting.kind()) {
            case FLAG -> {
                lore.add(Msg.get("settings.hint-switch"));
                yield isOn(setting) ? Material.LIME_DYE : Material.GRAY_DYE;
            }
            case WHOLE, NUMBER -> {
                Object up = SettingsCatalog.step(setting, 0, 1);
                lore.add(Msg.get("settings.hint-number", Msg.t("step", SettingsCatalog.show(up))));
                lore.add(Msg.get("settings.hint-type-shift"));
                yield Material.CLOCK;
            }
            case LIST -> {
                lore.add(Msg.get("settings.hint-type"));
                yield Material.PAPER;
            }
            case TEXT -> {
                lore.add(Msg.get("settings.hint-type"));
                yield Material.NAME_TAG;
            }
        };
        return icon(material, name, lore);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore.stream()
                .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList()));
        return item;
    }

    /** A config comment as gray tooltip lines, rewrapped to a comfortable width. */
    private static List<Component> text(List<String> description) {
        List<Component> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : String.join(" ", description).split(" ")) {
            if (word.isEmpty()) continue;
            if (!line.isEmpty() && line.length() + word.length() + 1 > WRAP) {
                lines.add(Component.text(line.toString(), NamedTextColor.GRAY));
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(Component.text(line.toString(), NamedTextColor.GRAY));
        return lines;
    }

    @EventHandler
    void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Page page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= page.inventory.getSize()) return;
        if (!player.hasPermission("lifestealplus.admin")) {
            player.closeInventory();
            return;
        }
        if (slot == page.inventory.getSize() - 5) {
            if (page.section == null && !page.presets) player.closeInventory();
            else open(player, null);
            return;
        }
        if (page.section == null && !page.presets && slot == page.inventory.getSize() - 4) {
            openPresets(player);
            return;
        }
        String target = page.slots.get(slot);
        if (target == null) return;

        if (page.presets) {
            // The first click shows the changes are about to happen; the second makes them.
            Presets.Preset preset = presets.find(target);
            if (preset.inUse(plugin.getConfig())) return;
            if (target.equals(page.armed)) {
                page.armed = null;
                applyPreset(player, preset);
            } else {
                page.armed = target;
            }
            draw(page);
            return;
        }

        if (page.section == null) {
            Setting onOff = catalog.find(target + ".enabled");
            if (onOff != null && event.isShiftClick()) {
                change(player, onOff, !isOn(onOff));
                draw(page);
            } else {
                open(player, target);
            }
            return;
        }
        Setting setting = catalog.find(target);
        switch (setting.kind()) {
            case FLAG -> change(player, setting, !isOn(setting));
            case WHOLE, NUMBER -> {
                if (event.isShiftClick()) {
                    askToType(player, setting);
                    return;
                }
                change(player, setting, SettingsCatalog.step(setting, current(setting), event.isRightClick() ? -1 : 1));
            }
            case LIST, TEXT -> {
                askToType(player, setting);
                return;
            }
        }
        draw(page);
    }

    @EventHandler
    void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Page) event.setCancelled(true);
    }

    private void askToType(Player player, Setting setting) {
        typing.put(player.getUniqueId(), new Typing(setting, Instant.now().plus(TYPING_WINDOW)));
        player.closeInventory();
        Msg.send(player, "settings.type-prompt", Msg.t("setting", setting.key()), Msg.t("value", SettingsCatalog.show(current(setting))));
    }

    /** The next chat line after "type a value" is the value, and nobody else sees it. */
    @EventHandler(priority = EventPriority.LOWEST)
    void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Typing waiting = typing.remove(player.getUniqueId());
        if (waiting == null || Instant.now().isAfter(waiting.expires())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (text.strip().equalsIgnoreCase("cancel")) Msg.send(player, "settings.type-cancelled");
            else typed(player, waiting.setting(), text);
            if (player.isOnline()) open(player, waiting.setting().section());
        });
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        typing.remove(event.getPlayer().getUniqueId());
    }
}
