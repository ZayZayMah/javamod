package dev.zay.staff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

final class Util {
    private Util() {}

    private static final LegacyComponentSerializer LEG = LegacyComponentSerializer.legacyAmpersand();

    static Component c(String s) {
        return LEG.deserialize(s);
    }

    static void send(CommandSender s, String msg) {
        s.sendMessage(c(msg));
    }

    static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(c(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s).decoration(TextDecoration.ITALIC, false));
            im.lore(l);
        }
        it.setItemMeta(im);
        return it;
    }

    static String dur(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    /** Looks a statistic up by name so a rename between versions cannot break the build. */
    static Statistic stat(String... names) {
        for (String n : names) {
            try {
                return (Statistic) Statistic.class.getField(n).get(null);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static int getStat(Player p, String... names) {
        Statistic s = stat(names);
        if (s == null) return 0;
        try {
            return p.getStatistic(s);
        } catch (Exception e) {
            return 0;
        }
    }

    static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
