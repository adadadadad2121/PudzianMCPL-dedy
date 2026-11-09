package pl.pudzianmcpl.dedy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PudzianMCPLDedy extends JavaPlugin implements Listener {

    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    // przedmioty wybrane w trakcie smierci - potwierdzane dopiero na MONITOR
    private final Map<UUID, List<ItemStack>> candidate = new HashMap<>();
    private final Random random = new Random();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PudzianMCPL-dedy wlaczony.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    // SMIERC
    // ------------------------------------------------------------------

    // NORMAL - wykonujemy sie PRZED sakiewka dropu (HIGH) i przed totemem ulaskawienia (HIGHEST).
    // Dzieki temu X% przedmiotow zostaje u ofiary, a reszta (100-X%) zostaje w dropach,
    // wiec sakiewka zabojcy zbiera tylko to, co zostalo.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("enabled", true)) return;

        Player p = e.getEntity();

        if (p.hasPermission("pudzianmcpl.dedy.ignore")) return;
        if (getConfig().getStringList("disabled-worlds").contains(p.getWorld().getName())) return;

        if (getConfig().getBoolean("respect-other-plugins", true) && e.getKeepInventory()) return;
        if (getConfig().getBoolean("only-when-killed-by-player", false) && p.getKiller() == null) return;

        List<ItemStack> drops = e.getDrops();
        if (drops.isEmpty()) return;

        double percent = p.hasPermission("pudzianmcpl.dedy.vip")
                ? getConfig().getDouble("vip-percent", getConfig().getDouble("percent", 25.0))
                : getConfig().getDouble("percent", 25.0);
        percent = Math.max(0.0, Math.min(100.0, percent));
        if (percent <= 0.0) return;

        double chance = percent / 100.0;
        boolean perItem = getConfig().getBoolean("random-per-item", false);

        List<ItemStack> kept = new ArrayList<>();
        Iterator<ItemStack> it = drops.iterator();
        List<ItemStack> toAdd = new ArrayList<>();

        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item == null || item.getType().isAir()) continue;

            int amount = item.getAmount();
            int keep = 0;

            if (perItem) {
                for (int i = 0; i < amount; i++) {
                    if (random.nextDouble() < chance) keep++;
                }
            } else {
                double exact = amount * chance;
                keep = (int) Math.floor(exact);
                if (random.nextDouble() < (exact - keep)) keep++;
            }

            if (keep <= 0) continue;

            if (keep >= amount) {
                kept.add(item.clone());
                it.remove();
            } else {
                ItemStack part = item.clone();
                part.setAmount(keep);
                kept.add(part);
                item.setAmount(amount - keep);
            }
        }

        candidate.put(p.getUniqueId(), kept);
    }

    // MONITOR - na koncu sprawdzamy, czy jakis plugin (np. totem ulaskawienia) nie ustawil
    // keepInventory. Jesli tak, gracz i tak zachowuje caly ekwipunek - nic nie oddajemy,
    // zeby nie bylo dupa.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathFinal(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        List<ItemStack> kept = candidate.remove(id);
        if (kept == null) return;
        if (e.getKeepInventory()) return;

        pending.put(id, kept);
        saveData();
    }

    // ------------------------------------------------------------------
    // ODDAWANIE PRZEDMIOTOW
    // ------------------------------------------------------------------

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> giveBack(p), 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (pending.containsKey(p.getUniqueId())) {
            getServer().getScheduler().runTaskLater(this, () -> giveBack(p), 20L);
        }
    }

    private void giveBack(Player p) {
        if (!p.isOnline() || p.isDead()) return;

        List<ItemStack> items = pending.remove(p.getUniqueId());
        if (items == null) return;
        saveData();

        int count = 0;
        boolean autoEquip = getConfig().getBoolean("auto-equip-armor", true);
        PlayerInventory inv = p.getInventory();

        for (ItemStack item : items) {
            count += item.getAmount();

            if (autoEquip && tryEquip(inv, item)) continue;

            Map<Integer, ItemStack> left = inv.addItem(item);
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }

        String percentText = trim(p.hasPermission("pudzianmcpl.dedy.vip")
                ? getConfig().getDouble("vip-percent", 25.0)
                : getConfig().getDouble("percent", 25.0));

        if (items.isEmpty()) {
            send(p, getConfig().getString("messages.nothing", ""), percentText, 0);
        } else {
            send(p, getConfig().getString("messages.recovered", ""), percentText, count);
        }
    }

    private boolean tryEquip(PlayerInventory inv, ItemStack item) {
        if (item.getAmount() != 1) return false;
        String n = item.getType().name();

        if (n.endsWith("_HELMET") || item.getType() == Material.TURTLE_HELMET) {
            if (isEmpty(inv.getHelmet())) { inv.setHelmet(item); return true; }
        } else if (n.endsWith("_CHESTPLATE") || item.getType() == Material.ELYTRA) {
            if (isEmpty(inv.getChestplate())) { inv.setChestplate(item); return true; }
        } else if (n.endsWith("_LEGGINGS")) {
            if (isEmpty(inv.getLeggings())) { inv.setLeggings(item); return true; }
        } else if (n.endsWith("_BOOTS")) {
            if (isEmpty(inv.getBoots())) { inv.setBoots(item); return true; }
        } else if (item.getType() == Material.SHIELD) {
            if (isEmpty(inv.getItemInOffHand())) { inv.setItemInOffHand(item); return true; }
        }
        return false;
    }

    private boolean isEmpty(ItemStack i) {
        return i == null || i.getType().isAir();
    }

    private void send(Player p, String raw, String percent, int count) {
        if (raw == null || raw.isEmpty()) return;
        String msg = raw.replace("{percent}", percent).replace("{count}", String.valueOf(count));
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
        p.sendMessage(c);
    }

    private String trim(double d) {
        if (d == Math.floor(d)) return String.valueOf((int) d);
        return String.valueOf(d);
    }

    // ------------------------------------------------------------------
    // DANE (zeby przedmioty nie przepadly przy restarcie, gdy ktos jest martwy)
    // ------------------------------------------------------------------

    private void loadData() {
        pending.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : yml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                List<ItemStack> list = new ArrayList<>();
                for (Object o : yml.getList(key, new ArrayList<>())) {
                    if (o instanceof ItemStack) list.add((ItemStack) o);
                }
                pending.put(id, list);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> en : pending.entrySet()) {
            yml.set(en.getKey().toString(), en.getValue());
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nie udalo sie zapisac data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // KOMENDA
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("pudzianmcpl.dedy.admin")) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(getConfig().getString("messages.no-permission", "&cBrak uprawnien.")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(getConfig().getString("messages.reloaded", "&aPrzeladowano.")));
            return true;
        }
        sender.sendMessage(Component.text("/dedy reload"));
        return true;
    }
}
