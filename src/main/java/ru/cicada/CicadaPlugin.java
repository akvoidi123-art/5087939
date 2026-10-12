package ru.cicada;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

public final class CicadaPlugin extends JavaPlugin implements Listener {

    private NamespacedKey itemKey;      // marks the cicada item
    private NamespacedKey hitboxKey;    // marks the Interaction entity (value = display uuid)
    private NamespacedKey rolledKey;    // chunk already rolled for spawn

    /** Loaded cicada hitboxes (Interaction entities). */
    private final Set<Interaction> loaded = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "cicada_item");
        hitboxKey = new NamespacedKey(this, "cicada_hitbox");
        rolledKey = new NamespacedKey(this, "rolled");

        getServer().getPluginManager().registerEvents(this, this);
        CicadaCommand cmd = new CicadaCommand(this);
        var pc = getCommand("cicada");
        if (pc != null) { pc.setExecutor(cmd); pc.setTabCompleter(cmd); }

        // pick up cicadas in already-loaded chunks (e.g. after /reload)
        for (World w : getServer().getWorlds())
            for (Chunk c : w.getLoadedChunks())
                for (Entity e : c.getEntities()) track(e);

        startSoundTask();
    }

    @Override
    public void onDisable() { loaded.clear(); }

    // ---------------------------------------------------------------- sound

    private void startSoundTask() {
        long period = Math.max(1, getConfig().getLong("sound.loop-ticks", 60));
        getServer().getScheduler().runTaskTimer(this, () -> {
            String sound = getConfig().getString("sound.name", "cicada:loop");
            double range = getConfig().getDouble("sound.range", 16.0);
            double r2 = range * range;
            float volume = (float) (range / 16.0);
            for (Interaction hb : loaded) {
                if (!hb.isValid()) { loaded.remove(hb); continue; }
                Location l = hb.getLocation();
                for (Player p : hb.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(l) <= r2)
                        p.playSound(l, sound, SoundCategory.AMBIENT, volume, 1.0f);
                }
            }
        }, period, period);
    }

    // ------------------------------------------------------- spawn / remove

    public ItemStack createItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(getConfig().getString("item.name", "&aЦикада"))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        NamespacedKey model = NamespacedKey.fromString(getConfig().getString("model.item-model", "cicada:cicada"));
        if (model != null) m.setItemModel(model);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public boolean isCicadaItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    /** @param ground bottom-centre of the block space the cicada stands in */
    public void spawnCicada(Location ground) {
        World w = ground.getWorld();
        double scale = getConfig().getDouble("model.scale", 1.0);
        double yOff = getConfig().getDouble("model.y-offset", 0.5);

        ItemStack display = createItem();
        ItemDisplay disp = w.spawn(ground.clone().add(0, yOff * scale, 0), ItemDisplay.class, d -> {
            d.setItemStack(display);
            d.setPersistent(true);
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f((float) scale), new org.joml.AxisAngle4f()));
        });
        Interaction hb = w.spawn(ground, Interaction.class, i -> {
            i.setInteractionWidth((float) (0.6 * scale));
            i.setInteractionHeight((float) (0.6 * scale));
            i.setResponsive(true);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(hitboxKey, PersistentDataType.STRING, disp.getUniqueId().toString());
        });
        loaded.add(hb);
    }

    private boolean isHitbox(Entity e) {
        return e instanceof Interaction && e.getPersistentDataContainer().has(hitboxKey, PersistentDataType.STRING);
    }

    private void track(Entity e) { if (isHitbox(e)) loaded.add((Interaction) e); }

    private void removeCicada(Interaction hb) {
        String id = hb.getPersistentDataContainer().get(hitboxKey, PersistentDataType.STRING);
        if (id != null) {
            try {
                Entity d = getServer().getEntity(UUID.fromString(id));
                if (d != null) d.remove();
            } catch (IllegalArgumentException ignored) { }
        }
        loaded.remove(hb);
        hb.remove();
    }

    // ------------------------------------------------------------ listeners

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) { for (Entity en : e.getEntities()) track(en); }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Interaction i) loaded.remove(i);
    }

    /** Shift + right click picks the cicada up. */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e instanceof PlayerInteractAtEntityEvent) return;   // both fire for one click; handle once
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Interaction hb) || !isHitbox(hb)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.isSneaking() || !p.hasPermission("cicada.pickup")) return;
        if (!hb.isValid()) return;
        removeCicada(hb);
        Map<Integer, ItemStack> left = p.getInventory().addItem(createItem());
        left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
    }

    /** Right click on a block with the cicada item places it. */
    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (!isCicadaItem(item)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("cicada.pickup")) return;
        Block clicked = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (clicked == null) return;
        Block target = clicked.getRelative(face);
        if (!target.isPassable()) return;
        Location ground = target.getLocation().add(0.5, 0, 0.5);
        spawnCicada(ground);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        getServer().getScheduler().runTaskLater(this, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            String sha = getConfig().getString("resource-pack.sha1", "");
            Component prompt = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(getConfig().getString("resource-pack.prompt", ""));
            boolean force = getConfig().getBoolean("resource-pack.force", false);
            try {
                if (sha != null && !sha.isBlank())
                    p.setResourcePack(url, HexFormat.of().parseHex(sha.trim()), prompt, force);
                else
                    p.setResourcePack(url, (byte[]) null, prompt, force);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Bad resource-pack settings: " + ex.getMessage());
            }
        }, 20L);
    }

    // --------------------------------------------------------- natural spawn

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!getConfig().getBoolean("spawn.enabled", true)) return;
        Chunk chunk = e.getChunk();
        World w = chunk.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;
        if (!getConfig().getStringList("spawn.worlds").contains(w.getName())) return;

        boolean once = getConfig().getBoolean("spawn.roll-once-per-chunk", true);
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (once) {
            if (pdc.has(rolledKey, PersistentDataType.BYTE)) return;
            pdc.set(rolledKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (ThreadLocalRandom.current().nextDouble() >= getConfig().getDouble("spawn.chance", 0.2)) return;

        long delay = getConfig().getLong("spawn.delay-ticks", 20);
        getServer().getScheduler().runTaskLater(this, () -> trySpawnIn(chunk), delay);
    }

    private void trySpawnIn(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        // don't stack several in one chunk (matters when roll-once-per-chunk is false)
        for (Interaction hb : loaded) {
            Chunk c = hb.getChunk();
            if (c.getX() == chunk.getX() && c.getZ() == chunk.getZ() && c.getWorld().equals(chunk.getWorld())) return;
        }
        World w = chunk.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 6; attempt++) {
            int x = (chunk.getX() << 4) + rnd.nextInt(16);
            int z = (chunk.getZ() << 4) + rnd.nextInt(16);
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = w.getBlockAt(x, y, z);
            Block above = ground.getRelative(BlockFace.UP);
            Material t = ground.getType();
            if (!ground.isSolid() || ground.isLiquid()) continue;
            if (Tag.LOGS.isTagged(t) || Tag.LEAVES.isTagged(t) || t == Material.ICE || t == Material.BEDROCK) continue;
            if (!above.isPassable() || above.isLiquid()) continue;
            spawnCicada(above.getLocation().add(0.5, 0, 0.5));
            return;
        }
    }
}
