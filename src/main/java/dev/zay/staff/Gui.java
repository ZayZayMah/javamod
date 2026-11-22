package dev.zay.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Chest GUIs. Plain left clicks only, so Bedrock players (through Geyser) can use them.
 * Layout rule everywhere: bottom-left = cancel/back/previous, bottom-right = confirm/next, middle/top = options.
 * Empty slots are left empty (no filler panes).
 *
 * /inventory (54 slots): 0-3 helmet/chest/legs/boots, 4 offhand, rows 2-4 storage, row 5 hotbar,
 * bottom row middle: 48 Clear, 49 Player info, 50 Pause.
 */
final class Gui implements Listener {

    static final List<String> FILTERS = List.of("all", "automod", "hacking", "esp", "manual",
            "level1", "level2", "level3", "level4", "level5");

    static final int CLEAR = 48, INFO = 49, PAUSE = 50;

    abstract static class H implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static final class InvH extends H {
        UUID target;
    }

    static final class ConfirmH extends H {
        UUID target;
        String name;
        String kind; // clearinv, wipe, ban
    }

    static final class FlagsH extends H {
        int page;
        String filter = "all";
        final List<UUID> shown = new ArrayList<>();
        final Map<UUID, String> names = new HashMap<>();
    }

    static final class OptH extends H {
        UUID target;
        String name;
        String filter;
    }

    static final class PFlagsH extends H {
        UUID target;
        String name;
        String filter;
        int page;
        final List<Integer> shown = new ArrayList<>();
    }

    static final class EditH extends H {
        int flagId;
        UUID target;
        String name;
        String filter;
    }

    static final class PunishH extends H {
        UUID target;
        String name;
        final List<String[]> presets = new ArrayList<>();
    }

    private final StaffPlugin pl;

    Gui(StaffPlugin pl) {
        this.pl = pl;
    }

    // ================================================================== helpers

    static boolean match(Data.Flag f, String filter) {
        if (filter.equals("all")) return true;
        if (filter.startsWith("level")) {
            try {
                return f.level == Integer.parseInt(filter.substring(5));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return f.category.equalsIgnoreCase(filter);
    }

    private static Component nameC(String s) {
        return Util.c(s).decoration(TextDecoration.ITALIC, false);
    }

    private static void put(Inventory inv, int slot, ItemStack it) {
        ItemStack old = inv.getItem(slot);
        if (Objects.equals(old, it)) return;
        inv.setItem(slot, it);
    }

    private ItemStack head(OfflinePlayer op, String title, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(op);
        sm.displayName(nameC(title));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(nameC(s));
        sm.lore(l);
        it.setItemMeta(sm);
        return it;
    }

    private static Material levelMat(int level) {
        return switch (level) {
            case 1 -> Material.LIME_DYE;
            case 2 -> Material.YELLOW_DYE;
            case 3 -> Material.ORANGE_DYE;
            case 4 -> Material.RED_DYE;
            default -> Material.PURPLE_DYE;
        };
    }

    private static String fmtScore(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d);
    }

    private boolean allowed(Player p) {
        if (!p.isOp() || !pl.staff.contains(p.getUniqueId())) {
            p.closeInventory();
            return false;
        }
        return true;
    }

    private Data.Flag flagById(int id) {
        for (Data.Flag f : pl.data.flags) if (f.id == id) return f;
        return null;
    }

    // ================================================================== /inventory

    void openInv(Player viewer, Player target) {
        InvH h = new InvH();
        h.target = target.getUniqueId();
        h.inv = Bukkit.createInventory(h, 54, Util.c("&8" + target.getName() + "'s Inventory"));
        fill(h);
        viewer.openInventory(h.inv);
    }

    private static int mapInv(int g) {
        if (g >= 9 && g <= 35) return g;       // storage
        if (g >= 36 && g <= 44) return g - 36; // hotbar
        return -1;
    }

    private static boolean editable(int g) {
        return (g >= 0 && g <= 4) || mapInv(g) >= 0;
    }

    private static ItemStack tGet(PlayerInventory pi, int g) {
        ItemStack it;
        switch (g) {
            case 0 -> it = pi.getHelmet();
            case 1 -> it = pi.getChestplate();
            case 2 -> it = pi.getLeggings();
            case 3 -> it = pi.getBoots();
            case 4 -> it = pi.getItemInOffHand();
            default -> {
                int i = mapInv(g);
                it = i >= 0 ? pi.getItem(i) : null;
            }
        }
        return (it == null || it.getType().isAir()) ? null : it;
    }

    private static void tSet(PlayerInventory pi, int g, ItemStack it) {
        switch (g) {
            case 0 -> pi.setHelmet(it);
            case 1 -> pi.setChestplate(it);
            case 2 -> pi.setLeggings(it);
            case 3 -> pi.setBoots(it);
            case 4 -> pi.setItemInOffHand(it == null ? new ItemStack(Material.AIR) : it);
            default -> {
                int i = mapInv(g);
                if (i >= 0) pi.setItem(i, it);
            }
        }
    }

    /** Armor slots only accept the matching armor piece (like vanilla). */
    private static boolean fits(int g, ItemStack it) {
        if (it == null) return true;
        String n = it.getType().name();
        return switch (g) {
            case 0 -> n.endsWith("_HELMET") || n.equals("CARVED_PUMPKIN") || n.endsWith("_HEAD") || n.endsWith("_SKULL");
            case 1 -> n.endsWith("_CHESTPLATE") || n.equals("ELYTRA");
            case 2 -> n.endsWith("_LEGGINGS");
            case 3 -> n.endsWith("_BOOTS");
            default -> true;
        };
    }

    List<String> infoLines(Player t) {
        long ticks = Util.getStat(t, "PLAY_ONE_MINUTE", "PLAY_TIME");
        String first = DateTimeFormatter.ofPattern("d/M/yyyy")
                .format(Instant.ofEpochMilli(t.getFirstPlayed()).atZone(ZoneId.systemDefault()));
        return List.of(
                "Time online: " + Util.dur(ticks / 20),
                "Deaths: " + Util.getStat(t, "DEATHS"),
                "Player kills: " + Util.getStat(t, "PLAYER_KILLS"),
                "Mob kills: " + Util.getStat(t, "MOB_KILLS"),
                "First joined: " + first,
                "Gamemode: " + t.getGameMode(),
                "Ping: " + t.getPing() + "ms",
                "Ban score: " + fmtScore(pl.data.score(t.getUniqueId())) + "/10");
    }

    void fill(InvH h) {
        Player t = Bukkit.getPlayer(h.target);
        if (t == null) return;
        Inventory inv = h.inv;
        PlayerInventory pi = t.getInventory();
        for (int g = 0; g < 54; g++) {
            if (editable(g)) put(inv, g, tGet(pi, g));
        }
        put(inv, CLEAR, Util.item(Material.RED_STAINED_GLASS, "&c&lClear inventory", "&7Click to open confirmation"));
        boolean paused = pl.paused.contains(h.target);
        put(inv, PAUSE, paused
                ? Util.item(Material.LIME_STAINED_GLASS, "&a&lUnpause inventory", "&7Currently paused", "&7Click to unpause")
                : Util.item(Material.ORANGE_STAINED_GLASS, "&6&lPause inventory", "&7Stops items, use and durability"));
        List<String> lore = new ArrayList<>();
        for (String l : infoLines(t)) lore.add("&7" + l);
        put(inv, INFO, head(t, "&e&l" + t.getName(), lore));
    }

    void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = p.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof InvH h) fill(h);
        }
    }

    // ================================================================== confirm

    void openConfirm(Player viewer, UUID target, String name, String kind) {
        ConfirmH h = new ConfirmH();
        h.target = target;
        h.name = name;
        h.kind = kind;
        h.inv = Bukkit.createInventory(h, 27, Util.c("&8Are You Sure?"));
        String[] info = switch (kind) {
            case "wipe" -> new String[]{"&cWipe ALL data of " + name, "&7Inventory, ender chest, playtime,", "&7stats, advancements, flags..."};
            case "ban" -> new String[]{"&cBan " + name, "&7Permanent ban"};
            default -> new String[]{"&cClear inventory of " + name};
        };
        h.inv.setItem(4, Util.item(Material.PAPER, info[0], Arrays.copyOfRange(info, 1, info.length)));
        h.inv.setItem(18, Util.item(Material.RED_STAINED_GLASS, "&c&lCancel"));
        h.inv.setItem(26, Util.item(Material.LIME_STAINED_GLASS, "&a&lConfirm"));
        viewer.openInventory(h.inv);
    }

    // ================================================================== /flags

    void openFlags(Player viewer, String filter, int page) {
        Map<UUID, List<Data.Flag>> by = new LinkedHashMap<>();
        for (Data.Flag f : pl.data.flags) {
            if (match(f, filter)) by.computeIfAbsent(f.uuid, k -> new ArrayList<>()).add(f);
        }
        List<Map.Entry<UUID, List<Data.Flag>>> es = new ArrayList<>(by.entrySet());
        es.sort((x, y) -> Double.compare(pl.data.score(y.getKey()), pl.data.score(x.getKey())));

        FlagsH h = new FlagsH();
        h.filter = filter;
        h.page = page;
        h.inv = Bukkit.createInventory(h, 54, Util.c("&8Flagged players (" + filter + ")"));
        int start = page * 45;
        for (int i = 0; i < 45 && start + i < es.size(); i++) {
            Map.Entry<UUID, List<Data.Flag>> en = es.get(start + i);
            String name = en.getValue().get(0).name;
            boolean online = Bukkit.getPlayer(en.getKey()) != null;
            h.inv.setItem(i, head(Bukkit.getOfflinePlayer(en.getKey()), "&e&l" + name,
                    List.of("&7Ban score: &c" + fmtScore(pl.data.score(en.getKey())) + "&7/10",
                            online ? "&aOnline" : "&cOffline", "&eClick for options")));
            h.shown.add(en.getKey());
            h.names.put(en.getKey(), name);
        }
        if (page > 0) h.inv.setItem(45, Util.item(Material.ARROW, "&ePrevious page"));
        h.inv.setItem(49, Util.item(Material.HOPPER, "&bFilter: &f" + filter, "&7Click to change filter"));
        if (start + 45 < es.size()) h.inv.setItem(53, Util.item(Material.ARROW, "&eNext page"));
        viewer.openInventory(h.inv);
    }

    void openOptions(Player viewer, UUID target, String name, String filter) {
        OptH h = new OptH();
        h.target = target;
        h.name = name;
        h.filter = filter;
        h.inv = Bukkit.createInventory(h, 27, Util.c("&8" + name));
        boolean online = Bukkit.getPlayer(target) != null;
        h.inv.setItem(4, head(Bukkit.getOfflinePlayer(target), "&e&l" + name,
                List.of(online ? "&aOnline" : "&cOffline", "&7Ban score: &c" + fmtScore(pl.data.score(target)) + "&7/10")));
        h.inv.setItem(10, Util.item(Material.ENDER_PEARL, "&bTeleport (vanished)", "&7Vanish and teleport to them"));
        h.inv.setItem(11, Util.item(Material.CHEST, "&eSee inventory"));
        h.inv.setItem(12, Util.item(Material.BOOK, "&eView data in chat"));
        h.inv.setItem(13, Util.item(Material.WRITABLE_BOOK, "&eView flags", "&7Edit, remove or change levels"));
        h.inv.setItem(14, Util.item(Material.LEATHER_BOOTS, "&6Kick"));
        h.inv.setItem(15, Util.item(Material.IRON_AXE, "&cBan"));
        h.inv.setItem(16, Util.item(Material.RED_STAINED_GLASS, "&c&lClear data", "&7Wipe everything"));
        h.inv.setItem(18, Util.item(Material.ARROW, "&eBack"));
        viewer.openInventory(h.inv);
    }

    void openPlayerFlags(Player viewer, UUID target, String name, String filter, int page) {
        PFlagsH h = new PFlagsH();
        h.target = target;
        h.name = name;
        h.filter = filter;
        h.page = page;
        h.inv = Bukkit.createInventory(h, 54, Util.c("&8" + name + "'s flags"));
        List<Data.Flag> list = new ArrayList<>();
        for (Data.Flag f : pl.data.flags) if (f.uuid.equals(target)) list.add(f);
        list.sort((a, b) -> Long.compare(b.time, a.time));
        SimpleDateFormat df = new SimpleDateFormat("HH:mm d/M/yyyy");
        int start = page * 45;
        for (int i = 0; i < 45 && start + i < list.size(); i++) {
            Data.Flag f = list.get(start + i);
            h.inv.setItem(i, Util.item(levelMat(f.level), "&f#" + f.id + " &7- &cLevel " + f.level,
                    "&7Reason: &f" + Util.clip(f.reason, 60),
                    "&7Type: &f" + f.category,
                    "&7By: &f" + f.by,
                    "&7Date: &f" + df.format(new Date(f.time)),
                    "&7Score: &f" + fmtScore(Data.scoreOf(f.level)),
                    "&eClick to edit"));
            h.shown.add(f.id);
        }
        h.inv.setItem(45, Util.item(Material.ARROW, "&eBack"));
        if (page > 0) h.inv.setItem(46, Util.item(Material.ARROW, "&ePrevious page"));
        h.inv.setItem(49, Util.item(Material.PAPER, "&e" + name, "&7Ban score: &c" + fmtScore(pl.data.score(target)) + "&7/10",
                "&7Flags expire from the score after 30 days"));
        if (start + 45 < list.size()) h.inv.setItem(53, Util.item(Material.ARROW, "&eNext page"));
        viewer.openInventory(h.inv);
    }

    void openEdit(Player viewer, int flagId, UUID target, String name, String filter) {
        Data.Flag f = flagById(flagId);
        if (f == null) {
            openPlayerFlags(viewer, target, name, filter, 0);
            return;
        }
        EditH h = new EditH();
        h.flagId = flagId;
        h.target = target;
        h.name = name;
        h.filter = filter;
        h.inv = Bukkit.createInventory(h, 27, Util.c("&8Flag #" + flagId));
        h.inv.setItem(4, Util.item(levelMat(f.level), "&f#" + f.id + " &7- &cLevel " + f.level,
                "&7Reason: &f" + Util.clip(f.reason, 60), "&7Type: &f" + f.category, "&7By: &f" + f.by));
        h.inv.setItem(11, Util.item(Material.LIGHT_BLUE_STAINED_GLASS, "&bDecrease level"));
        h.inv.setItem(13, Util.item(Material.RED_STAINED_GLASS, "&c&lRemove flag"));
        h.inv.setItem(15, Util.item(Material.ORANGE_STAINED_GLASS, "&6Increase level"));
        h.inv.setItem(18, Util.item(Material.ARROW, "&eBack"));
        viewer.openInventory(h.inv);
    }

    void openPunish(Player viewer, UUID target, String name) {
        PunishH h = new PunishH();
        h.target = target;
        h.name = name;
        for (String s : pl.getConfig().getStringList("punish-presets")) {
            String[] p = s.split("\\|", 3);
            if (p.length == 3) h.presets.add(p);
        }
        h.inv = Bukkit.createInventory(h, 27, Util.c("&8Punish " + name));
        for (int i = 0; i < h.presets.size() && i < 18; i++) {
            String[] p = h.presets.get(i);
            int lvl = 1;
            try {
                lvl = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException ignored) {
            }
            h.inv.setItem(i, Util.item(levelMat(lvl), "&f" + p[0], "&7Adds a level " + lvl + " flag", "&7Reason: &f" + p[2]));
        }
        viewer.openInventory(h.inv);
    }

    // ================================================================== clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof H) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof H)) return;
        if (!(e.getWhoClicked() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        if (!allowed(p)) {
            e.setCancelled(true);
            return;
        }
        Inventory clicked = e.getClickedInventory();
        boolean inTop = clicked != null && clicked.equals(top);

        // In the inventory viewer the staff member may use their OWN inventory with plain clicks
        // (to pick up items and drop them into the target's slots). Anything that could move items
        // into the viewer automatically is blocked.
        if (holder instanceof InvH && !inTop) {
            ClickType ct = e.getClick();
            if (clicked == null || (ct != ClickType.LEFT && ct != ClickType.RIGHT)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (!inTop) return;
        int slot = e.getRawSlot();

        if (holder instanceof InvH h) clickInv(p, h, slot, e);
        else if (holder instanceof ConfirmH h) clickConfirm(p, h, slot);
        else if (holder instanceof FlagsH h) clickFlags(p, h, slot);
        else if (holder instanceof OptH h) clickOpt(p, h, slot);
        else if (holder instanceof PFlagsH h) clickPFlags(p, h, slot);
        else if (holder instanceof EditH h) clickEdit(p, h, slot);
        else if (holder instanceof PunishH h) clickPunish(p, h, slot);
    }

    private void clickInv(Player p, InvH h, int slot, InventoryClickEvent e) {
        Player t = Bukkit.getPlayer(h.target);
        if (t == null) {
            p.closeInventory();
            return;
        }
        if (slot == CLEAR) {
            openConfirm(p, h.target, t.getName(), "clearinv");
            return;
        }
        if (slot == PAUSE) {
            boolean now = !pl.paused.contains(h.target);
            if (now) pl.paused.add(h.target);
            else pl.paused.remove(h.target);
            pl.log(p.getName() + (now ? " Paused" : " Unpaused") + " Inventory of " + t.getName());
            fill(h);
            return;
        }
        if (!editable(slot)) return;
        ClickType ct = e.getClick();
        if (ct != ClickType.LEFT && ct != ClickType.RIGHT) return;
        if (pl.paused.contains(h.target)) return; // silently refuse while paused

        PlayerInventory pi = t.getInventory();
        ItemStack cursor = e.getCursor();
        if (cursor != null && cursor.getType().isAir()) cursor = null;
        ItemStack slotItem = tGet(pi, slot);
        ItemStack newSlot = slotItem == null ? null : slotItem.clone();
        ItemStack newCursor = cursor == null ? null : cursor.clone();
        boolean right = ct == ClickType.RIGHT;

        if (newCursor == null) {
            if (newSlot != null) {
                if (right && newSlot.getAmount() > 1) {
                    int take = (newSlot.getAmount() + 1) / 2;
                    newCursor = newSlot.clone();
                    newCursor.setAmount(take);
                    newSlot.setAmount(newSlot.getAmount() - take);
                } else {
                    newCursor = newSlot;
                    newSlot = null;
                }
            }
        } else if (!fits(slot, newCursor)) {
            return; // wrong armor type for that slot
        } else if (newSlot == null) {
            if (right && newCursor.getAmount() > 1) {
                newSlot = newCursor.clone();
                newSlot.setAmount(1);
                newCursor.setAmount(newCursor.getAmount() - 1);
            } else {
                newSlot = newCursor;
                newCursor = null;
            }
        } else if (newSlot.isSimilar(newCursor)) {
            int max = newSlot.getMaxStackSize();
            int room = max - newSlot.getAmount();
            if (room <= 0) return;
            int move = right ? 1 : Math.min(room, newCursor.getAmount());
            newSlot.setAmount(newSlot.getAmount() + move);
            if (newCursor.getAmount() - move <= 0) newCursor = null;
            else newCursor.setAmount(newCursor.getAmount() - move);
        } else {
            ItemStack tmp = newSlot;
            newSlot = newCursor;
            newCursor = tmp; // swap
        }

        tSet(pi, slot, newSlot);
        p.setItemOnCursor(newCursor == null ? new ItemStack(Material.AIR) : newCursor);
        pl.log(p.getName() + " Modified Inventory of " + t.getName());
        fill(h);
        t.updateInventory();
        Bukkit.getScheduler().runTask(pl, p::updateInventory);
    }

    private void clickConfirm(Player p, ConfirmH h, int slot) {
        if (slot == 18) {
            p.closeInventory();
            Player t = Bukkit.getPlayer(h.target);
            if (h.kind.equals("clearinv") && t != null) openInv(p, t);
            else openOptions(p, h.target, h.name, "all");
            return;
        }
        if (slot != 26) return;
        p.closeInventory();
        switch (h.kind) {
            case "wipe" -> {
                pl.wipe(Bukkit.getOfflinePlayer(h.target), p.getName());
                Util.send(p, "&aWiped all data of " + h.name + ".");
            }
            case "ban" -> pl.banPlayer(h.name, "Banned by staff", p.getName());
            default -> {
                Player t = Bukkit.getPlayer(h.target);
                if (t == null || pl.paused.contains(h.target)) return;
                t.getInventory().clear();
                t.getInventory().setArmorContents(new ItemStack[4]);
                t.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                t.updateInventory();
                pl.log(p.getName() + " Cleared Inventory of " + t.getName());
                Util.send(p, "&aCleared inventory of " + t.getName() + ".");
            }
        }
    }

    private void clickFlags(Player p, FlagsH h, int slot) {
        if (slot < 45 && slot < h.shown.size()) {
            UUID id = h.shown.get(slot);
            openOptions(p, id, h.names.get(id), h.filter);
        } else if (slot == 45 && h.page > 0) {
            openFlags(p, h.filter, h.page - 1);
        } else if (slot == 53 && h.inv.getItem(53) != null) {
            openFlags(p, h.filter, h.page + 1);
        } else if (slot == 49) {
            int i = (FILTERS.indexOf(h.filter) + 1) % FILTERS.size();
            openFlags(p, FILTERS.get(i), 0);
        }
    }

    private void clickOpt(Player p, OptH h, int slot) {
        Player t = Bukkit.getPlayer(h.target);
        switch (slot) {
            case 10 -> {
                if (t == null) return;
                p.closeInventory();
                pl.sftp(p, t);
            }
            case 11 -> {
                if (t != null) openInv(p, t);
            }
            case 12 -> {
                p.closeInventory();
                pl.sendData(p, h.target, h.name);
            }
            case 13 -> openPlayerFlags(p, h.target, h.name, h.filter, 0);
            case 14 -> {
                if (t == null) return;
                p.closeInventory();
                pl.kickPlayer(t, p.getName());
            }
            case 15 -> openConfirm(p, h.target, h.name, "ban");
            case 16 -> openConfirm(p, h.target, h.name, "wipe");
            case 18 -> openFlags(p, h.filter, 0);
            default -> {
            }
        }
    }

    private void clickPFlags(Player p, PFlagsH h, int slot) {
        if (slot < 45 && slot < h.shown.size()) {
            openEdit(p, h.shown.get(slot), h.target, h.name, h.filter);
        } else if (slot == 45) {
            openOptions(p, h.target, h.name, h.filter);
        } else if (slot == 46 && h.page > 0) {
            openPlayerFlags(p, h.target, h.name, h.filter, h.page - 1);
        } else if (slot == 53 && h.inv.getItem(53) != null) {
            openPlayerFlags(p, h.target, h.name, h.filter, h.page + 1);
        }
    }

    private void clickEdit(Player p, EditH h, int slot) {
        Data.Flag f = flagById(h.flagId);
        if (f == null || slot == 18) {
            openPlayerFlags(p, h.target, h.name, h.filter, 0);
            return;
        }
        if (slot == 11 && f.level > 1) {
            f.level--;
            pl.data.save();
            pl.log(p.getName() + " Decreased Flag #" + f.id + " of " + h.name + " to level " + f.level);
            openEdit(p, f.id, h.target, h.name, h.filter);
        } else if (slot == 15 && f.level < 5) {
            f.level++;
            pl.data.save();
            pl.log(p.getName() + " Increased Flag #" + f.id + " of " + h.name + " to level " + f.level);
            pl.checkBan(h.target, h.name);
            openEdit(p, f.id, h.target, h.name, h.filter);
        } else if (slot == 13) {
            pl.data.flags.remove(f);
            pl.data.save();
            pl.log(p.getName() + " Removed Flag #" + f.id + " of " + h.name + " (" + f.reason + ")");
            openPlayerFlags(p, h.target, h.name, h.filter, 0);
        }
    }

    private void clickPunish(Player p, PunishH h, int slot) {
        if (slot < 0 || slot >= h.presets.size()) return;
        String[] pr = h.presets.get(slot);
        int lvl = 1;
        try {
            lvl = Integer.parseInt(pr[1].trim());
        } catch (NumberFormatException ignored) {
        }
        p.closeInventory();
        pl.addFlag(h.target, h.name, lvl, pr[2], "manual", p.getName());
        Util.send(p, "&aFlagged " + h.name + " (level " + lvl + "): " + pr[2]);
    }
}
