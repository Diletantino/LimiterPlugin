package me.example.banthings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.stream.Collectors;

public final class BanThingsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private enum NotifyMode { OFF, CHAT, ACTIONBAR, BOTH }

    private final Set<String> bannedItems = new HashSet<>();
    private final Map<String, Integer> itemLimits = new HashMap<>();
    private final Set<String> bannedEffects = new HashSet<>();

    private NotifyMode notifyMode = NotifyMode.CHAT;

    private String msgRemovedBanned;
    private String msgRemovedLimited;
    private String msgClearedEffects;

    private String msgListEmpty;
    private String msgHeaderBanned;
    private String msgHeaderLimits;
    private String msgHeaderEffects;

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Команды
        for (String cmd : List.of(
                "banitem", "unbanitem", "banlist",
                "limititem", "unlimititem", "limitlist",
                "baneffect", "unbaneffect", "effectlist"
        )) {
            PluginCommand pc = getCommand(cmd);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            } else {
                getLogger().warning("Command not found in plugin.yml: " + cmd);
            }
        }
    }

    private void reloadAll() {
        reloadConfig();
        loadFromConfig();
        loadMessages();
    }

    private void loadFromConfig() {
        bannedItems.clear();
        itemLimits.clear();
        bannedEffects.clear();

        bannedItems.addAll(getConfig().getStringList("banned-items")
                .stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));

        bannedEffects.addAll(getConfig().getStringList("banned-effects")
                .stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));

        ConfigurationSection sec = getConfig().getConfigurationSection("item-limits");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                int v = sec.getInt(key, -1);
                if (v >= 0) itemLimits.put(key.toLowerCase(Locale.ROOT), v);
            }
        }

        String mode = getConfig().getString("notify-mode", "CHAT").toUpperCase(Locale.ROOT);
        try {
            notifyMode = NotifyMode.valueOf(mode);
        } catch (IllegalArgumentException ignored) {
            notifyMode = NotifyMode.CHAT;
        }
    }

    private void loadMessages() {
        msgRemovedBanned = getConfig().getString("messages.removed-banned-item",
                "&c[BanThings] &fRemoved banned item: &e%item% &7x%amount%");
        msgRemovedLimited = getConfig().getString("messages.removed-limited-item",
                "&6[BanThings] &fLimit exceeded: &e%item% &7(limit %limit%). Removed: x%amount%");
        msgClearedEffects = getConfig().getString("messages.cleared-effects",
                "&c[BanThings] &fBanned effect received (&e%effect%&f). Cleared all effects.");

        msgListEmpty = getConfig().getString("command.list-empty", "&7(empty)");
        msgHeaderBanned = getConfig().getString("command.list-header-banned", "&eBanned items:&f");
        msgHeaderLimits = getConfig().getString("command.list-header-limits", "&eItem limits:&f");
        msgHeaderEffects = getConfig().getString("command.list-header-effects", "&eBanned effects:&f");
    }

    private void saveToConfig() {
        getConfig().set("banned-items", new ArrayList<>(bannedItems));
        getConfig().set("banned-effects", new ArrayList<>(bannedEffects));

        Map<String, Object> limits = new LinkedHashMap<>();
        itemLimits.keySet().stream().sorted().forEach(k -> limits.put(k, itemLimits.get(k)));
        getConfig().set("item-limits", limits);

        saveConfig();
    }

    // ----------------------------
    // Helpers: parsing
    // ----------------------------

    private Material parseMaterial(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        Material m = Material.matchMaterial(s, true);
        if (m == null) m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        return m;
    }

    private String keyOf(Material m) {
        return m.getKey().toString().toLowerCase(Locale.ROOT);
    }

    private PotionEffectType parseEffectType(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        NamespacedKey key;
        if (s.contains(":")) {
            String[] parts = s.split(":", 2);
            key = new NamespacedKey(parts[0], parts[1]);
        } else {
            key = NamespacedKey.minecraft(s);
        }
        return PotionEffectType.getByKey(key);
    }

    private String keyOf(PotionEffectType t) {
        NamespacedKey k = t.getKey();
        return (k == null) ? null : k.toString().toLowerCase(Locale.ROOT);
    }

    // ----------------------------
    // Notifications
    // ----------------------------

    private void notifyPlayer(Player p, String raw) {
        if (notifyMode == NotifyMode.OFF) return;
        Component c = legacy.deserialize(raw);
        if (notifyMode == NotifyMode.CHAT || notifyMode == NotifyMode.BOTH) {
            p.sendMessage(c);
        }
        if (notifyMode == NotifyMode.ACTIONBAR || notifyMode == NotifyMode.BOTH) {
            p.sendActionBar(c);
        }
    }

    private String fmt(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    // ----------------------------
    // Inventory enforcement
    // ----------------------------

    private static final class EnforceResult {
        // key -> removed amount
        final Map<String, Integer> removedBanned = new HashMap<>();
        final Map<String, Integer> removedLimited = new HashMap<>();
        final Map<String, Integer> limitValue = new HashMap<>();

        boolean hasAnything() {
            return !removedBanned.isEmpty() || !removedLimited.isEmpty();
        }
    }

    private EnforceResult enforcePlayerInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        EnforceResult res = new EnforceResult();

        removeBannedFromAllSlots(inv, res);
        applyLimits(inv, res);

        return res;
    }

    private void removeBannedFromAllSlots(PlayerInventory inv, EnforceResult res) {
        // storage
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType().isAir()) continue;
            String k = keyOf(it.getType());
            if (bannedItems.contains(k)) {
                res.removedBanned.merge(k, it.getAmount(), Integer::sum);
                storage[i] = null;
                changed = true;
            }
        }
        if (changed) inv.setStorageContents(storage);

        // armor
        ItemStack[] armor = inv.getArmorContents();
        changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack it = armor[i];
            if (it == null || it.getType().isAir()) continue;
            String k = keyOf(it.getType());
            if (bannedItems.contains(k)) {
                res.removedBanned.merge(k, it.getAmount(), Integer::sum);
                armor[i] = null;
                changed = true;
            }
        }
        if (changed) inv.setArmorContents(armor);

        // offhand
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            String k = keyOf(off.getType());
            if (bannedItems.contains(k)) {
                res.removedBanned.merge(k, off.getAmount(), Integer::sum);
                inv.setItemInOffHand(null);
            }
        }
    }

    private void applyLimits(PlayerInventory inv, EnforceResult res) {
        if (itemLimits.isEmpty()) return;

        // count totals for every limited item
        Map<String, Integer> counts = new HashMap<>();
        for (String k : itemLimits.keySet()) counts.put(k, 0);

        java.util.function.Consumer<ItemStack> countOne = (it) -> {
            if (it == null || it.getType().isAir()) return;
            String k = keyOf(it.getType());
            if (counts.containsKey(k)) {
                counts.put(k, counts.get(k) + it.getAmount());
            }
        };

        for (ItemStack it : inv.getStorageContents()) countOne.accept(it);
        for (ItemStack it : inv.getArmorContents()) countOne.accept(it);
        countOne.accept(inv.getItemInOffHand());

        // remove extras
        for (String k : new ArrayList<>(itemLimits.keySet())) {
            int limit = itemLimits.getOrDefault(k, Integer.MAX_VALUE);
            if (limit < 0) continue;

            int have = counts.getOrDefault(k, 0);
            int extra = have - limit;
            if (extra <= 0) continue;

            res.limitValue.put(k, limit);

            ItemStack[] storage = inv.getStorageContents();
            extra = removeExtraFromArray(storage, k, extra, res);
            inv.setStorageContents(storage);

            if (extra > 0) {
                ItemStack[] armor = inv.getArmorContents();
                extra = removeExtraFromArray(armor, k, extra, res);
                inv.setArmorContents(armor);
            }

            if (extra > 0) {
                ItemStack off = inv.getItemInOffHand();
                if (off != null && !off.getType().isAir() && keyOf(off.getType()).equals(k)) {
                    int take = Math.min(extra, off.getAmount());
                    off.setAmount(off.getAmount() - take);
                    res.removedLimited.merge(k, take, Integer::sum);
                    extra -= take;
                    if (off.getAmount() <= 0) inv.setItemInOffHand(null);
                }
            }
        }
    }

    private int removeExtraFromArray(ItemStack[] arr, String key, int extra, EnforceResult res) {
        if (extra <= 0) return 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            if (!keyOf(it.getType()).equals(key)) continue;

            int take = Math.min(extra, it.getAmount());
            it.setAmount(it.getAmount() - take);
            res.removedLimited.merge(key, take, Integer::sum);
            extra -= take;

            if (it.getAmount() <= 0) arr[i] = null;
            if (extra <= 0) break;
        }
        return extra;
    }

    private void notifyEnforcement(Player p, EnforceResult r) {
        if (!r.removedBanned.isEmpty()) {
            for (Map.Entry<String, Integer> e : r.removedBanned.entrySet()) {
                notifyPlayer(p, fmt(msgRemovedBanned, Map.of(
                        "item", e.getKey(),
                        "amount", String.valueOf(e.getValue())
                )));
            }
        }
        if (!r.removedLimited.isEmpty()) {
            for (Map.Entry<String, Integer> e : r.removedLimited.entrySet()) {
                int limit = r.limitValue.getOrDefault(e.getKey(), itemLimits.getOrDefault(e.getKey(), 0));
                notifyPlayer(p, fmt(msgRemovedLimited, Map.of(
                        "item", e.getKey(),
                        "amount", String.valueOf(e.getValue()),
                        "limit", String.valueOf(limit)
                )));
            }
        }
    }

    private void enforceSoon(Player p) {
        Bukkit.getScheduler().runTask(this, () -> {
            EnforceResult r = enforcePlayerInventory(p);
            if (r.hasAnything()) notifyEnforcement(p, r);
        });
    }

    // ----------------------------
    // Inventory events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) enforceSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) enforceSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) enforceSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        enforceSoon(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        enforceSoon(e.getPlayer());

        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            for (PotionEffect eff : p.getActivePotionEffects()) {
                String k = keyOf(eff.getType());
                if (k != null && bannedEffects.contains(k)) {
                    p.clearActivePotionEffects();
                    notifyPlayer(p, fmt(msgClearedEffects, Map.of("effect", k)));
                    break;
                }
            }
        });
    }

    // ----------------------------
    // Effect banning
    // ----------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionChange(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        PotionEffect newEff = e.getNewEffect();
        if (newEff == null) return;

        String k = keyOf(newEff.getType());
        if (k == null) return;

        if (bannedEffects.contains(k)) {
            Bukkit.getScheduler().runTask(this, () -> {
                p.clearActivePotionEffects();
                notifyPlayer(p, fmt(msgClearedEffects, Map.of("effect", k)));
            });
        }
    }

    // ----------------------------
    // Commands
    // ----------------------------

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("banthings.admin");
    }

    private void send(CommandSender sender, String raw) {
        if (sender instanceof Player p) {
            p.sendMessage(legacy.deserialize(raw));
        } else {
            sender.sendMessage(raw.replace('&', '§'));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (!isAdmin(sender)) {
            send(sender, "&cНет прав.");
            return true;
        }

        switch (cmd) {
            case "banitem" -> {
                if (args.length != 1) return false;
                Material m = parseMaterial(args[0]);
                if (m == null) {
                    send(sender, "&cНеизвестный предмет: " + args[0]);
                    return true;
                }
                bannedItems.add(keyOf(m));
                saveToConfig();
                send(sender, "&aЗабанен предмет: &e" + keyOf(m));
                for (Player p : Bukkit.getOnlinePlayers()) enforceSoon(p);
                return true;
            }
            case "unbanitem" -> {
                if (args.length != 1) return false;
                Material m = parseMaterial(args[0]);
                if (m == null) {
                    send(sender, "&cНеизвестный предмет: " + args[0]);
                    return true;
                }
                bannedItems.remove(keyOf(m));
                saveToConfig();
                send(sender, "&aРазбанен предмет: &e" + keyOf(m));
                return true;
            }
            case "banlist" -> {
                send(sender, msgHeaderBanned);
                if (bannedItems.isEmpty()) {
                    send(sender, "  " + msgListEmpty);
                    return true;
                }
                bannedItems.stream().sorted().forEach(s -> send(sender, "  &7- &e" + s));
                return true;
            }
            case "limititem" -> {
                if (args.length != 2) return false;
                Material m = parseMaterial(args[0]);
                if (m == null) {
                    send(sender, "&cНеизвестный предмет: " + args[0]);
                    return true;
                }
                int limit;
                try {
                    limit = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    send(sender, "&cЛимит должен быть числом.");
                    return true;
                }
                if (limit < 0) {
                    send(sender, "&cЛимит не может быть отрицательным.");
                    return true;
                }
                itemLimits.put(keyOf(m), limit);
                saveToConfig();
                send(sender, "&aЛимит установлен: &e" + keyOf(m) + " &a-> &e" + limit);
                for (Player p : Bukkit.getOnlinePlayers()) enforceSoon(p);
                return true;
            }
            case "unlimititem" -> {
                if (args.length != 1) return false;
                Material m = parseMaterial(args[0]);
                if (m == null) {
                    send(sender, "&cНеизвестный предмет: " + args[0]);
                    return true;
                }
                itemLimits.remove(keyOf(m));
                saveToConfig();
                send(sender, "&aЛимит убран: &e" + keyOf(m));
                return true;
            }
            case "limitlist" -> {
                send(sender, msgHeaderLimits);
                if (itemLimits.isEmpty()) {
                    send(sender, "  " + msgListEmpty);
                    return true;
                }
                itemLimits.keySet().stream().sorted().forEach(k ->
                        send(sender, "  &7- &e" + k + " &7: &f" + itemLimits.get(k))
                );
                return true;
            }
            case "baneffect" -> {
                if (args.length != 1) return false;
                PotionEffectType t = parseEffectType(args[0]);
                if (t == null) {
                    send(sender, "&cНеизвестный эффект: " + args[0]);
                    return true;
                }
                String k = keyOf(t);
                if (k == null) {
                    send(sender, "&cНе удалось получить ключ эффекта.");
                    return true;
                }
                bannedEffects.add(k);
                saveToConfig();
                send(sender, "&aЗабанен эффект: &e" + k);

                Bukkit.getScheduler().runTask(this, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        for (PotionEffect eff : p.getActivePotionEffects()) {
                            String kk = keyOf(eff.getType());
                            if (kk != null && bannedEffects.contains(kk)) {
                                p.clearActivePotionEffects();
                                notifyPlayer(p, fmt(msgClearedEffects, Map.of("effect", kk)));
                                break;
                            }
                        }
                    }
                });
                return true;
            }
            case "unbaneffect" -> {
                if (args.length != 1) return false;
                PotionEffectType t = parseEffectType(args[0]);
                if (t == null) {
                    send(sender, "&cНеизвестный эффект: " + args[0]);
                    return true;
                }
                String k = keyOf(t);
                if (k == null) {
                    send(sender, "&cНе удалось получить ключ эффекта.");
                    return true;
                }
                bannedEffects.remove(k);
                saveToConfig();
                send(sender, "&aРазбанен эффект: &e" + k);
                return true;
            }
            case "effectlist" -> {
                send(sender, msgHeaderEffects);
                if (bannedEffects.isEmpty()) {
                    send(sender, "  " + msgListEmpty);
                    return true;
                }
                bannedEffects.stream().sorted().forEach(s -> send(sender, "  &7- &e" + s));
                return true;
            }
        }

        return false;
    }

    // ----------------------------
    // Tab Complete
    // ----------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!isAdmin(sender)) return Collections.emptyList();

        if ((cmd.equals("banitem") || cmd.equals("unbanitem") || cmd.equals("limititem") || cmd.equals("unlimititem"))
                && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            if (cmd.equals("unbanitem")) {
                return bannedItems.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
            }
            if (cmd.equals("unlimititem")) {
                return itemLimits.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList();
            }

            return Arrays.stream(Material.values())
                    .filter(m -> !m.isAir())
                    .map(m -> m.getKey().toString())
                    .filter(s -> s.startsWith(prefix))
                    .limit(50)
                    .toList();
        }

        if (cmd.equals("limititem") && args.length == 2) {
            return List.of("0", "1", "2", "4", "8", "16", "32", "64");
        }

        if ((cmd.equals("baneffect") || cmd.equals("unbaneffect")) && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            if (cmd.equals("unbaneffect")) {
                return bannedEffects.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
            }

            List<String> all = new ArrayList<>();
            for (PotionEffectType t : PotionEffectType.values()) {
                if (t == null) continue;
                NamespacedKey k = t.getKey();
                if (k != null) all.add(k.toString().toLowerCase(Locale.ROOT));
            }
            return all.stream().filter(s -> s.startsWith(prefix)).sorted().limit(50).toList();
        }

        return Collections.emptyList();
    }
}
