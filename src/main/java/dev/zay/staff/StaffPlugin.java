package dev.zay.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class StaffPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm d/M/yyyy");
    static final String[] CMDS = {"sfmode", "sfhelp", "gms", "gmsp", "gmc", "gma", "inventory", "echest", "freeze",
            "flag", "flags", "logs", "sftp", "vstop", "mute", "unmute", "heal", "feed", "fly", "god", "speed",
            "clearchat", "bc", "sc", "cleardata", "tempban", "unban", "data", "punish", "near", "seen", "vstart", "alerts"};

    Data data;
    Gui gui;
    Automod automod;
    AntiCheat anti;

    final Set<UUID> staff = ConcurrentHashMap.newKeySet();
    final Set<UUID> paused = ConcurrentHashMap.newKeySet();
    final Set<UUID> vanished = new HashSet<>();
    final Map<UUID, Location> returnLoc = new HashMap<>();
    final Map<UUID, GameMode> returnMode = new HashMap<>();
    final Map<UUID, UUID> following = new HashMap<>();
    final Map<UUID, Long> exempt = new ConcurrentHashMap<>();
    final Set<UUID> alertsOff = new HashSet<>();
    final Set<String> ours = new HashSet<>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        data = new Data(new File(getDataFolder(), "data.yml"));
        for (String c : CMDS) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
                ours.add(c);
                for (String al : pc.getAliases()) ours.add(al.toLowerCase(Locale.ROOT));
            }
        }
        gui = new Gui(this);
        automod = new Automod(this);
        anti = new AntiCheat(this);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(gui, this);
        pm.registerEvents(automod, this);
        pm.registerEvents(anti, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> gui.refreshAll(), 4L, 4L);
        checkXray();
        getLogger().info("ZayStaff enabled.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(vanished)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unvanish(p, false);
        }
        if (data != null) data.save();
    }

    private void checkXray() {
        File f = new File(getServer().getWorldContainer(), "config/paper-world-defaults.yml");
        if (!f.exists()) {
            getLogger().warning("Could not find config/paper-world-defaults.yml to check anti-xray.");
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        boolean on = y.getBoolean("anticheat.anti-xray.enabled", false);
        if (on) {
            getLogger().info("Paper anti-xray is ON (engine-mode " + y.getInt("anticheat.anti-xray.engine-mode", 1) + ").");
        } else {
            getLogger().warning("Paper anti-xray is OFF. Set anticheat.anti-xray.enabled: true and engine-mode: 2 in config/paper-world-defaults.yml");
        }
    }

    // ------------------------------------------------------------------ helpers

    void log(String text) {
        data.addLog(TS.format(LocalDateTime.now()) + " | " + text);
    }

    static String actor(CommandSender s) {
        return s instanceof Player p ? p.getName() : "Console";
    }

    void alertStaff(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (staff.contains(p.getUniqueId())) Util.send(p, msg);
        }
    }

    void exempt(Player p, long ms) {
        exempt.put(p.getUniqueId(), System.currentTimeMillis() + ms);
    }

    static String strip(String n) {
        return n.startsWith(".") ? n.substring(1) : n;
    }

    Player findPlayer(String n) {
        Player p = Bukkit.getPlayerExact(n);
        if (p != null) return p;
        String q = strip(n).toLowerCase(Locale.ROOT);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (strip(o.getName()).toLowerCase(Locale.ROOT).equals(q)) return o;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (strip(o.getName()).toLowerCase(Locale.ROOT).startsWith(q)) return o;
        }
        return null;
    }

    OfflinePlayer findOffline(String n) {
        Player p = findPlayer(n);
        if (p != null) return p;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(n);
        if (op == null) op = Bukkit.getOfflinePlayerIfCached("." + strip(n));
        return op;
    }

    boolean gate(CommandSender s) {
        if (s instanceof Player p) {
            if (!p.isOp() || !staff.contains(p.getUniqueId())) {
                unknown(p, "");
                return false;
            }
        }
        return true;
    }

    Data.Flag addFlag(UUID id, String name, int level, String reason, String category, String by) {
        Data.Flag f = new Data.Flag();
        f.id = data.nextId++;
        f.uuid = id;
        f.name = name;
        f.level = Math.max(1, Math.min(5, level));
        f.reason = reason;
        f.category = category;
        f.time = System.currentTimeMillis();
        f.by = by;
        data.flags.add(f);
        data.save();
        log(by + " flagged " + name + " (level " + f.level + ", " + category + "): " + reason);
        for (Player sp : Bukkit.getOnlinePlayers()) {
            if (staff.contains(sp.getUniqueId()) && !alertsOff.contains(sp.getUniqueId())) {
                Util.send(sp, "&c[Flag #" + f.id + "] &f" + name + " &7L" + f.level + " &8(" + category + ") &f" + reason);
            }
        }
        checkBan(id, name);
        return f;
    }

    void flagSafe(UUID id, String name, int level, String reason, String category, String by) {
        if (Bukkit.isPrimaryThread()) addFlag(id, name, level, reason, category, by);
        else Bukkit.getScheduler().runTask(this, () -> addFlag(id, name, level, reason, category, by));
    }

    static void unknown(Player p, String typed) {
        p.sendMessage(Component.translatable("command.unknown.command", NamedTextColor.RED));
        p.sendMessage(Component.text("/" + typed, NamedTextColor.GRAY)
                .append(Component.translatable("command.context.here", NamedTextColor.RED).decorate(TextDecoration.ITALIC)));
    }

    static void notFound(CommandSender s) {
        s.sendMessage(Component.translatable("argument.entity.notfound.player", NamedTextColor.RED));
    }

    /** Automatic ban when the 30-day flag score reaches 10. */
    void checkBan(UUID id, String name) {
        if (data.score(id) >= 10.0) {
            banPlayer(name, "Automod: flag score reached 10 within 30 days", "Automod");
        }
    }

    void banPlayer(String name, String reason, String by) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
        log(by + " Banned " + name + " (" + reason + ")");
        alertStaff("&c" + by + " banned " + name);
    }

    void kickPlayer(Player t, String by) {
        t.kick(Component.translatable("multiplayer.disconnect.kicked"));
        log(by + " Kicked " + t.getName());
    }

    void sendData(CommandSender s, UUID id, String name) {
        Player on = Bukkit.getPlayer(id);
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        Util.send(s, "&e&l" + name + " &7(" + (on != null ? "online" : "offline") + ")");
        if (on != null) {
            for (String l : gui.infoLines(on)) Util.send(s, "&7" + l);
        } else {
            Util.send(s, "&7Last seen: " + fmtTime(op.getLastPlayed()));
            Util.send(s, "&7Ban score: " + data.score(id) + "/10");
        }
        Long mu = data.mutes.get(id);
        if (mu != null && System.currentTimeMillis() < mu) Util.send(s, "&7Muted: yes");
        if (data.frozen.contains(id)) Util.send(s, "&7Frozen: yes");
        List<Data.Flag> list = new ArrayList<>();
        for (Data.Flag f : data.flags) if (f.uuid.equals(id)) list.add(f);
        list.sort((a, b) -> Long.compare(b.time, a.time));
        Util.send(s, list.isEmpty() ? "&7No flags." : "&7Flags (newest first):");
        int n = 0;
        for (Data.Flag f : list) {
            if (n++ >= 20) break;
            Util.send(s, "&8#" + f.id + " &7L" + f.level + " " + f.category + " &f" + f.reason + " &8(" + fmtTime(f.time) + ")");
        }
    }

    static String fmtTime(long ms) {
        return ms <= 0 ? "never" : TS.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault()));
    }

    static long parseDur(String s) {
        try {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            switch (s.charAt(s.length() - 1)) {
                case 'm':
                    return n * 60_000L;
                case 'h':
                    return n * 3_600_000L;
                case 'd':
                    return n * 86_400_000L;
                case 'w':
                    return n * 604_800_000L;
                default:
                    return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ vanish

    void vanish(Player p) {
        UUID id = p.getUniqueId();
        if (vanished.contains(id)) return;
        returnLoc.put(id, p.getLocation());
        returnMode.put(id, p.getGameMode());
        vanished.add(id);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.hidePlayer(this, p);
        }
        p.setGameMode(GameMode.SPECTATOR);
        log(p.getName() + " entered Vanish");
    }

    void unvanish(Player p, boolean here) {
        UUID id = p.getUniqueId();
        if (!vanished.remove(id)) return;
        following.remove(id);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.showPlayer(this, p);
        }
        GameMode m = returnMode.remove(id);
        Location back = returnLoc.remove(id);
        p.setGameMode(m == null || m == GameMode.SPECTATOR ? GameMode.SURVIVAL : m);
        if (!here && back != null) {
            exempt(p, 3000);
            p.teleport(back);
        }
        log(p.getName() + " left Vanish" + (here ? " (here)" : ""));
    }

    void sftp(Player staffer, Player target) {
        vanish(staffer);
        following.put(staffer.getUniqueId(), target.getUniqueId());
        exempt(staffer, 3000);
        staffer.teleport(target.getLocation());
        staffer.sendActionBar(Util.c("&7Vanished and following &f" + target.getName()));
        log(staffer.getName() + " Teleported (vanished) to " + target.getName());
    }

    // ------------------------------------------------------------------ data wipe

    void wipe(OfflinePlayer op, String by) {
        UUID id = op.getUniqueId();
        String name = op.getName() == null ? id.toString() : op.getName();
        Player on = Bukkit.getPlayer(id);
        data.removeAllFor(id);
        paused.remove(id);
        if (on != null) {
            on.getInventory().clear();
            on.getEnderChest().clear();
            on.setLevel(0);
            on.setExp(0f);
            on.kick(Component.translatable("multiplayer.disconnect.kicked"));
        }
        log(by + " Cleared Data of " + name);
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> deleteFiles(id), 40L);
    }

    private void deleteFiles(UUID id) {
        String u = id.toString();
        Path root = getServer().getWorldContainer().toPath();
        try (Stream<Path> st = Files.walk(root, 8)) {
            st.filter(Files::isRegularFile).filter(pa -> {
                String nm = pa.getFileName().toString();
                String full = pa.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!nm.startsWith(u) || full.contains("/plugins/")) return false;
                return full.contains("playerdata") || full.contains("/stats/")
                        || full.contains("advancements") || full.contains("/players/");
            }).forEach(pa -> {
                try {
                    Files.delete(pa);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ex) {
            getLogger().warning("Data wipe file cleanup failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("sfmode")) return sfmode(s);
        if (!gate(s)) return true;
        switch (n) {
            case "sfhelp" -> help(s);
            case "gms" -> gm(s, a, GameMode.SURVIVAL, "Survival");
            case "gmsp" -> gm(s, a, GameMode.SPECTATOR, "Spectator");
            case "gmc" -> gm(s, a, GameMode.CREATIVE, "Creative");
            case "gma" -> gm(s, a, GameMode.ADVENTURE, "Adventure");
            case "inventory" -> inventory(s, a);
            case "echest" -> echest(s, a);
            case "freeze" -> freeze(s, a);
            case "flag" -> flag(s, a);
            case "flags" -> flags(s, a);
            case "logs" -> logs(s, a);
            case "sftp" -> sftpCmd(s, a);
            case "vstop" -> vstop(s, a);
            case "mute" -> mute(s, a);
            case "unmute" -> unmute(s, a);
            case "heal", "feed", "fly", "god" -> simple(n, s, a);
            case "speed" -> speed(s, a);
            case "clearchat" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.isOp()) for (int i = 0; i < 100; i++) p.sendMessage(Component.empty());
                }
                Bukkit.broadcast(Util.c("&7Chat was cleared."));
                log(actor(s) + " Cleared Chat");
            }
            case "bc" -> {
                if (a.length == 0) return false;
                Bukkit.broadcast(Util.c("&c[&fBroadcast&c] &f" + String.join(" ", a)));
                log(actor(s) + " Broadcast: " + String.join(" ", a));
            }
            case "sc" -> {
                if (a.length == 0) return false;
                alertStaff("&b[Staff] &f" + actor(s) + "&7: &f" + String.join(" ", a));
                getLogger().info("[Staff] " + actor(s) + ": " + String.join(" ", a));
            }
            case "cleardata" -> cleardata(s, a);
            case "tempban" -> tempban(s, a);
            case "unban" -> unban(s, a);
            case "data" -> {
                OfflinePlayer op = a.length > 0 ? findOffline(a[0]) : null;
                if (op == null) notFound(s);
                else sendData(s, op.getUniqueId(), op.getName() == null ? a[0] : op.getName());
            }
            case "punish" -> {
                OfflinePlayer op = a.length > 0 ? findOffline(a[0]) : null;
                if (!(s instanceof Player pp)) Util.send(s, "&cIn-game only.");
                else if (op == null) notFound(s);
                else gui.openPunish(pp, op.getUniqueId(), op.getName() == null ? a[0] : op.getName());
            }
            case "near" -> near(s, a);
            case "seen" -> {
                OfflinePlayer op = a.length > 0 ? findOffline(a[0]) : null;
                if (op == null) notFound(s);
                else Util.send(s, "&7" + op.getName() + (op.isOnline() ? " is online now." : " was last seen " + fmtTime(op.getLastPlayed())));
            }
            case "vstart" -> {
                if (s instanceof Player pp) {
                    vanish(pp);
                    Util.send(pp, "&aVanish on. Use /vstop to leave.");
                }
            }
            case "alerts" -> {
                if (s instanceof Player pp) {
                    boolean off = alertsOff.add(pp.getUniqueId());
                    if (!off) alertsOff.remove(pp.getUniqueId());
                    Util.send(pp, "&7Flag alerts " + (off ? "&coff" : "&aon"));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean sfmode(CommandSender s) {
        if (!(s instanceof Player p)) {
            Util.send(s, "&cOnly players use staff mode. The console already has full access.");
            return true;
        }
        if (!p.isOp()) {
            unknown(p, "sfmode");
            return true;
        }
        UUID id = p.getUniqueId();
        if (staff.remove(id)) {
            if (vanished.contains(id)) unvanish(p, false);
            Util.send(p, "&cStaff mode disabled.");
            log(p.getName() + " left Staff Mode");
            p.updateCommands();
        } else {
            staff.add(id);
            Util.send(p, "&aStaff mode enabled. Use &f/sfhelp &afor the command list.");
            log(p.getName() + " entered Staff Mode");
            p.updateCommands();
        }
        return true;
    }

    private void help(CommandSender s) {
        Util.send(s, "&b&lStaff commands");
        String[] l = {
                "/sfmode - toggle staff mode (op only)",
                "/gms /gmsp /gmc /gma [player] - gamemodes",
                "/inventory <player> - inventory GUI (text in console)",
                "/echest <player> - view ender chest",
                "/freeze <player> - freeze / unfreeze",
                "/flag add <player> <1-5> <reason>  |  /flag remove <id> <reason>",
                "/flags [filter] - flagged players GUI",
                "/logs [count] - last admin logs",
                "/sftp <player> - vanish and teleport to a player",
                "/vstop [here] - leave vanish",
                "/mute <player> [minutes]  /unmute <player>",
                "/heal /feed /fly /god [player]  /speed <1-10> [player]",
                "/clearchat  /bc <msg>  /sc <msg>",
                "/cleardata <player> - wipe ALL data",
                "/data <player> - player data and flags in chat",
                "/punish <player> - preset punishment menu",
                "/tempban <player> <1h/7d/..> [reason]  /unban <player>",
                "/near [radius]  /seen <player>  /vstart  /alerts"};
        for (String x : l) Util.send(s, "&7" + x);
    }

    private void gm(CommandSender s, String[] a, GameMode m, String nm) {
        Player t = a.length > 0 ? findPlayer(a[0]) : (s instanceof Player p ? p : null);
        if (t == null) {
            notFound(s);
            return;
        }
        if (vanished.contains(t.getUniqueId()) && m != GameMode.SPECTATOR) {
            Util.send(s, "&cThat player is vanished. Use /vstop first.");
            return;
        }
        t.setGameMode(m);
        Component mode = Component.translatable("gameMode." + nm.toLowerCase(Locale.ROOT));
        if (t.equals(s)) s.sendMessage(Component.translatable("commands.gamemode.success.self", mode));
        else s.sendMessage(Component.translatable("commands.gamemode.success.other", Component.text(t.getName()), mode));
        log(actor(s) + " Set Gamemode of " + t.getName() + " to " + nm);
    }

    private void inventory(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /inventory <player>");
            return;
        }
        Player t = findPlayer(a[0]);
        if (t == null) {
            notFound(s);
            return;
        }
        log(actor(s) + " Viewed Inventory of " + t.getName());
        if (s instanceof Player p) {
            gui.openInv(p, t);
            return;
        }
        // console / text fallback
        Util.send(s, "&eInventory of " + t.getName() + (paused.contains(t.getUniqueId()) ? " &c(PAUSED)" : ""));
        ItemStack[] c = t.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] != null && !c[i].getType().isAir()) {
                Util.send(s, "&7 [" + i + "] &f" + c[i].getType() + " x" + c[i].getAmount());
            }
        }
        for (String line : gui.infoLines(t)) Util.send(s, "&7 " + line);
        Util.send(s, "&8Use /inventory in-game for the GUI. Console cannot pause or clear.");
    }

    private void echest(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Util.send(s, "&cIn-game only.");
            return;
        }
        if (a.length < 1) {
            Util.send(s, "&cUsage: /echest <player>");
            return;
        }
        Player t = findPlayer(a[0]);
        if (t == null) {
            notFound(s);
            return;
        }
        p.openInventory(t.getEnderChest());
        log(p.getName() + " Viewed Ender Chest of " + t.getName());
    }

    private void freeze(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /freeze <player>");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        if (op == null) {
            notFound(s);
            return;
        }
        UUID id = op.getUniqueId();
        String name = op.getName() == null ? a[0] : op.getName();
        Player on = Bukkit.getPlayer(id);
        if (data.frozen.remove(id)) {
            data.save();
            Util.send(s, "&aUnfroze " + name + ".");
            log(actor(s) + " Unfroze " + name);
        } else {
            data.frozen.add(id);
            data.save();
            Util.send(s, "&bFroze " + name + ".");
            log(actor(s) + " Froze " + name);
        }
    }

    private void flag(CommandSender s, String[] a) {
        if (a.length >= 4 && a[0].equalsIgnoreCase("add")) {
            OfflinePlayer op = findOffline(a[1]);
            if (op == null) {
                notFound(s);
                return;
            }
            int lvl;
            try {
                lvl = Integer.parseInt(a[2]);
            } catch (NumberFormatException e) {
                lvl = 0;
            }
            if (lvl < 1 || lvl > 5) {
                Util.send(s, "&cLevel must be 1-5.");
                return;
            }
            String reason = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
            String nm = op.getName() == null ? a[1] : op.getName();
            Data.Flag f = addFlag(op.getUniqueId(), nm, lvl, reason, "manual", actor(s));
            Util.send(s, "&aFlag #" + f.id + " added to " + nm + ".");
        } else if (a.length >= 3 && a[0].equalsIgnoreCase("remove")) {
            int id;
            try {
                id = Integer.parseInt(a[1].replace("#", ""));
            } catch (NumberFormatException e) {
                Util.send(s, "&cID must be a number.");
                return;
            }
            String reason = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Data.Flag found = null;
            for (Data.Flag f : data.flags) if (f.id == id) found = f;
            if (found == null) {
                Util.send(s, "&cNo flag with ID " + id + ".");
                return;
            }
            data.flags.remove(found);
            data.save();
            log(actor(s) + " Removed Flag #" + id + " of " + found.name + " (" + reason + ")");
            Util.send(s, "&aRemoved flag #" + id + ".");
        } else {
            Util.send(s, "&cUsage: /flag add <player> <level 1-5> <reason>   or   /flag remove <id> <reason>");
        }
    }

    private void flags(CommandSender s, String[] a) {
        String filter = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "all";
        if (!Gui.FILTERS.contains(filter)) {
            Util.send(s, "&cFilters: " + String.join(", ", Gui.FILTERS));
            return;
        }
        if (s instanceof Player p) {
            gui.openFlags(p, filter, 0);
            return;
        }
        Util.send(s, "&eFlagged players (" + filter + "):");
        boolean any = false;
        for (Data.Flag f : data.flags) {
            if (!Gui.match(f, filter)) continue;
            any = true;
            Util.send(s, "&7#" + f.id + " &f" + f.name + " &7L" + f.level + " (" + f.category + ") " + f.reason);
        }
        if (!any) Util.send(s, "&7None.");
    }

    private void logs(CommandSender s, String[] a) {
        int n = 500;
        if (a.length > 0) {
            try {
                n = Math.max(1, Math.min(500, Integer.parseInt(a[0])));
            } catch (NumberFormatException ignored) {
            }
        }
        List<String> all;
        synchronized (data) {
            all = new ArrayList<>(data.logs);
        }
        int from = Math.max(0, all.size() - n);
        List<String> part = all.subList(from, all.size());
        Util.send(s, "&b&lLast " + part.size() + " admin logs");
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (String line : part) {
            sb.append("&7").append(line.replace("&", "")).append('\n');
            if (++c % 40 == 0) {
                Util.send(s, sb.toString().trim());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) Util.send(s, sb.toString().trim());
    }

    private void sftpCmd(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Util.send(s, "&cIn-game only.");
            return;
        }
        if (a.length < 1) {
            Util.send(s, "&cUsage: /sftp <player>");
            return;
        }
        Player t = findPlayer(a[0]);
        if (t == null || t.equals(p)) {
            notFound(s);
            return;
        }
        sftp(p, t);
    }

    private void vstop(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Util.send(s, "&cIn-game only.");
            return;
        }
        if (!vanished.contains(p.getUniqueId())) {
            Util.send(s, "&cYou are not vanished.");
            return;
        }
        boolean here = a.length > 0 && a[0].equalsIgnoreCase("here");
        unvanish(p, here);
        Util.send(s, here ? "&aVanish off (staying here)." : "&aVanish off, teleported back.");
    }

    private void mute(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /mute <player> [minutes]");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        if (op == null) {
            notFound(s);
            return;
        }
        long until = Long.MAX_VALUE;
        String len = "permanently";
        if (a.length > 1) {
            try {
                long m = Long.parseLong(a[1]);
                until = System.currentTimeMillis() + m * 60_000L;
                len = "for " + m + "m";
            } catch (NumberFormatException ignored) {
            }
        }
        data.mutes.put(op.getUniqueId(), until);
        data.save();
        String nm = op.getName() == null ? a[0] : op.getName();
        Util.send(s, "&aMuted " + nm + " " + len + ".");
        log(actor(s) + " Muted " + nm + " " + len);
    }

    private void unmute(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /unmute <player>");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        if (op == null || data.mutes.remove(op.getUniqueId()) == null) {
            Util.send(s, "&cThat player is not muted.");
            return;
        }
        data.save();
        Util.send(s, "&aUnmuted.");
        log(actor(s) + " Unmuted " + op.getName());
    }

    private void simple(String n, CommandSender s, String[] a) {
        Player t = a.length > 0 ? findPlayer(a[0]) : (s instanceof Player p ? p : null);
        if (t == null) {
            notFound(s);
            return;
        }
        switch (n) {
            case "heal" -> {
                t.setHealth(20.0);
                t.setFireTicks(0);
            }
            case "feed" -> {
                t.setFoodLevel(20);
                t.setSaturation(20f);
            }
            case "fly" -> {
                boolean on = !t.getAllowFlight();
                t.setAllowFlight(on);
                if (!on) t.setFlying(false);
                Util.send(s, "&aFlight " + (on ? "enabled" : "disabled") + " for " + t.getName());
            }
            case "god" -> {
                t.setInvulnerable(!t.isInvulnerable());
                Util.send(s, "&aGod mode " + (t.isInvulnerable() ? "on" : "off") + " for " + t.getName());
            }
            default -> {
            }
        }
        if (n.equals("heal") || n.equals("feed")) Util.send(s, "&aDone for " + t.getName() + ".");
        log(actor(s) + " Used /" + n + " on " + t.getName());
    }

    private void speed(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /speed <1-10> [player]");
            return;
        }
        Player t = a.length > 1 ? findPlayer(a[1]) : (s instanceof Player p ? p : null);
        if (t == null) {
            notFound(s);
            return;
        }
        float v;
        try {
            v = Math.max(1f, Math.min(10f, Float.parseFloat(a[0]))) / 10f;
        } catch (NumberFormatException e) {
            Util.send(s, "&cNumber 1-10.");
            return;
        }
        t.setFlySpeed(v);
        t.setWalkSpeed(Math.min(1f, v * 2f > 1f ? 1f : v * 2f));
        Util.send(s, "&aSpeed set to " + a[0] + " for " + t.getName());
        log(actor(s) + " Set Speed of " + t.getName() + " to " + a[0]);
    }

    private void cleardata(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /cleardata <player>");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        if (op == null) {
            notFound(s);
            return;
        }
        if (s instanceof Player p) {
            gui.openConfirm(p, op.getUniqueId(), op.getName() == null ? a[0] : op.getName(), "wipe");
        } else if (a.length > 1 && a[1].equalsIgnoreCase("confirm")) {
            wipe(op, "Console");
            Util.send(s, "&aData wiped for " + op.getName());
        } else {
            Util.send(s, "&cThis erases EVERYTHING. Run &f/cleardata " + a[0] + " confirm &cto continue.");
        }
    }

    private void tempban(CommandSender s, String[] a) {
        if (a.length < 2) {
            Util.send(s, "&cUsage: /tempban <player> <time e.g. 30m, 12h, 7d, 2w> [reason]");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        if (op == null) {
            notFound(s);
            return;
        }
        long d = parseDur(a[1]);
        if (d <= 0) {
            Util.send(s, "&cTime must look like 30m, 12h, 7d or 2w.");
            return;
        }
        String reason = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "Temporarily banned by staff";
        long until = System.currentTimeMillis() + d;
        String nm = op.getName() == null ? a[0] : op.getName();
        data.tempbans.put(op.getUniqueId(), new String[]{String.valueOf(until), nm, reason});
        data.save();
        Player on = Bukkit.getPlayer(op.getUniqueId());
        if (on != null) on.kick(Util.c("&cYou are temporarily banned.\n&7" + reason + "\n&7Expires: " + fmtTime(until)));
        Util.send(s, "&aTemp-banned " + nm + " until " + fmtTime(until) + ".");
        log(actor(s) + " Temp-banned " + nm + " for " + a[1] + " (" + reason + ")");
    }

    private void unban(CommandSender s, String[] a) {
        if (a.length < 1) {
            Util.send(s, "&cUsage: /unban <player>");
            return;
        }
        OfflinePlayer op = findOffline(a[0]);
        String nm = op != null && op.getName() != null ? op.getName() : a[0];
        if (op != null) data.tempbans.remove(op.getUniqueId());
        data.save();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + nm);
        Util.send(s, "&aUnbanned " + nm + ".");
        log(actor(s) + " Unbanned " + nm);
    }

    private void near(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Util.send(s, "&cIn-game only.");
            return;
        }
        double r = 100;
        if (a.length > 0) {
            try {
                r = Math.min(1000, Double.parseDouble(a[0]));
            } catch (NumberFormatException ignored) {
            }
        }
        boolean any = false;
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || vanished.contains(o.getUniqueId())) continue;
            double d = o.getLocation().distance(p.getLocation());
            if (d <= r) {
                any = true;
                Util.send(p, "&7" + o.getName() + " &f" + Math.round(d) + "m");
            }
        }
        if (!any) Util.send(p, "&7Nobody within " + (int) r + " blocks.");
    }

    // ------------------------------------------------------------------ tab completion

    private List<String> names() {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (s instanceof Player p && !p.isOp()) return List.of();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (n) {
            case "flag" -> {
                if (a.length == 1) out = List.of("add", "remove");
                else if (a.length == 2 && a[0].equalsIgnoreCase("add")) out = names();
                else if (a.length == 3 && a[0].equalsIgnoreCase("add")) out = List.of("1", "2", "3", "4", "5");
            }
            case "vstop" -> {
                if (a.length == 1) out = List.of("here");
            }
            case "flags" -> {
                if (a.length == 1) out = Gui.FILTERS;
            }
            case "speed" -> {
                if (a.length == 1) out = List.of("1", "2", "3", "5", "10");
                else if (a.length == 2) out = names();
            }
            case "sfmode", "sfhelp", "logs", "clearchat", "bc", "sc", "near", "vstart", "alerts" -> {
            }
            default -> {
                if (a.length == 1) out = names();
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> res = new ArrayList<>();
        for (String o : out) if (o.toLowerCase(Locale.ROOT).startsWith(last)) res.add(o);
        return res;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (UUID v : vanished) {
            Player vp = Bukkit.getPlayer(v);
            if (vp != null) p.hidePlayer(this, vp);
        }
        exempt(p, 3000);
        String[] tb = data.tempbans.get(p.getUniqueId());
        if (tb != null) {
            if (System.currentTimeMillis() < Long.parseLong(tb[0])) {
                e.joinMessage(null);
                p.kick(Util.c("&cYou are temporarily banned.\n&7" + tb[2] + "\n&7Expires: " + fmtTime(Long.parseLong(tb[0]))));
            } else {
                data.tempbans.remove(p.getUniqueId());
                data.save();
            }
        }
    }

    // ---- commands are invisible (unknown command) unless the player is an op in staff mode

    private boolean canSeeStaffCommands(Player p) {
        return p.isOp() && staff.contains(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCmdPre(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String m = e.getMessage();
        if (m.length() < 2) return;
        String base = m.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (base.startsWith("zaystaff:")) base = base.substring(9);
        if (!ours.contains(base)) return;
        if (canSeeStaffCommands(p) || (p.isOp() && base.equals("sfmode"))) return;
        e.setCancelled(true);
        unknown(p, m.substring(1));
    }

    @EventHandler
    public void onCmdSend(PlayerCommandSendEvent e) {
        Player p = e.getPlayer();
        boolean staffMode = canSeeStaffCommands(p);
        e.getCommands().removeIf(c -> {
            String b = c.toLowerCase(Locale.ROOT);
            if (b.startsWith("zaystaff:")) b = b.substring(9);
            if (!ours.contains(b)) return false;
            if (staffMode) return false;
            return !(p.isOp() && b.equals("sfmode"));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (vanished.contains(id)) unvanish(p, false);
        staff.remove(id);
        following.remove(id);
        exempt.remove(id);
        if (data.frozen.contains(id)) {
            alertStaff("&c" + p.getName() + " &7left while frozen!");
            log(p.getName() + " Left the server while Frozen");
        }
    }

    /** Frozen players cannot move, but can still look around. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!data.frozen.contains(p.getUniqueId())) return;
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back);
        }
    }

    /** If the investigated player teleports away, follow them. */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player t = e.getPlayer();
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(following.entrySet())) {
            if (!en.getValue().equals(t.getUniqueId())) continue;
            Player st = Bukkit.getPlayer(en.getKey());
            if (st == null || !vanished.contains(st.getUniqueId())) {
                following.remove(en.getKey());
                continue;
            }
            Location from = e.getFrom();
            boolean far = from.getWorld() != to.getWorld() || from.distanceSquared(to) > 256;
            if (far) {
                Location dest = to.clone();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    exempt(st, 3000);
                    st.teleport(dest);
                    st.sendActionBar(Util.c("&7Followed &f" + t.getName()));
                }, 2L);
                log(st.getName() + " Auto-followed " + t.getName() + " after teleport");
            }
        }
    }

    private boolean isLocked(Player p) {
        UUID id = p.getUniqueId();
        return vanished.contains(id) || data.frozen.contains(id) || paused.contains(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isLocked(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (vanished.contains(id) || paused.contains(id)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p
                && (vanished.contains(p.getUniqueId()) || paused.contains(p.getUniqueId()))) e.setCancelled(true);
    }

    // ---- paused inventory: nothing in, nothing out, nothing used, no durability loss

    @EventHandler(ignoreCancelled = true)
    public void onPausedClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && paused.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPausedDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && paused.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPausedSwap(PlayerSwapHandItemsEvent e) {
        if (paused.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPausedConsume(PlayerItemConsumeEvent e) {
        if (paused.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPausedDurability(PlayerItemDamageEvent e) {
        if (paused.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
}
