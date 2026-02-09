package com.tripu1404.anarchycore;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityItemFrame;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerDropItemEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerItemHeldEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.event.player.PlayerToggleGlideEvent;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class AnarchyCore extends PluginBase implements Listener {

    private int frameDupeChance;
    private boolean bedDupeEnabled;
    private int bedDupeChance;
    private boolean antiIllegalEnabled;
    
    private boolean rtpEnabled;
    private int rtpRadius;
    private String rtpWorld;

    private boolean antiElytraEnabled;
    private double antiElytraTpsLimit;
    private boolean safeFallEnabled;
    private double elytraSpeedLimitSq;   
    private double elytraMaxKmh;         
    private boolean elytraHudEnabled;

    private boolean customMessagesEnabled;
    private List<String> joinMessages;
    private List<String> quitMessages;

    private final Random random = new Random();
    private final Set<Integer> illegalIds = new HashSet<>();
    private final Set<String> safeFallPlayers = new HashSet<>(); 

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Config config = this.getConfig();

        this.frameDupeChance = config.getInt("frame-dupe-chance", 40);
        this.bedDupeEnabled = config.getBoolean("bed-dupe-enabled", true);
        this.bedDupeChance = config.getInt("bed-dupe-chance", 50);
        this.antiIllegalEnabled = config.getBoolean("anti-illegal-enabled", true);

        this.rtpEnabled = config.getBoolean("rtp-enabled", true);
        this.rtpRadius = config.getInt("rtp-radius", 1000);
        this.rtpWorld = config.getString("rtp-world", "world");

        this.antiElytraEnabled = config.getBoolean("anti-elytra-enabled", true);
        this.antiElytraTpsLimit = config.getDouble("anti-elytra-tps-limit", 15.0);
        this.safeFallEnabled = config.getBoolean("anti-elytra-safe-fall", true);
        this.elytraHudEnabled = config.getBoolean("elytra-hud-enabled", true);
        
        double speedLimit = config.getDouble("elytra-speed-limit", 3.5);
        this.elytraSpeedLimitSq = speedLimit * speedLimit;
        this.elytraMaxKmh = speedLimit * 72.0; 

        this.customMessagesEnabled = config.getBoolean("custom-messages-enabled", true);
        this.joinMessages = config.getStringList("join-messages");
        this.quitMessages = config.getStringList("quit-messages");

        if (antiIllegalEnabled) loadIllegalIds();

        if (antiElytraEnabled) {
            this.getServer().getScheduler().scheduleRepeatingTask(this, this::checkElytraLag, 40);
        }

        // Registrar Eventos del Core
        this.getServer().getPluginManager().registerEvents(this, this);
        
        // Registrar Eventos de Muerte (Sistema Multi-Archivo)
        this.getServer().getPluginManager().registerEvents(new DeathListener(this), this);

        this.getLogger().info(TextFormat.GREEN + "Anarchy Core (tripu1404) cargado completamente.");
    }

    private void loadIllegalIds() {
        illegalIds.add(BlockID.BEDROCK);
        illegalIds.add(BlockID.WATER);
        illegalIds.add(BlockID.STILL_WATER);
        illegalIds.add(BlockID.LAVA);
        illegalIds.add(BlockID.STILL_LAVA);
        illegalIds.add(26); 
        illegalIds.add(BlockID.FIRE);
        illegalIds.add(BlockID.MONSTER_SPAWNER);
        illegalIds.add(BlockID.INVISIBLE_BEDROCK);
        illegalIds.add(BlockID.BARRIER);
        illegalIds.add(90); // Portal Block
        illegalIds.add(BlockID.END_PORTAL);
        illegalIds.add(BlockID.END_PORTAL_FRAME);
        illegalIds.add(BlockID.COMMAND_BLOCK);
        illegalIds.add(BlockID.REPEATING_COMMAND_BLOCK);
        illegalIds.add(BlockID.CHAIN_COMMAND_BLOCK);
        illegalIds.add(BlockID.ALLOW);
        illegalIds.add(BlockID.DENY);
        illegalIds.add(BlockID.BORDER_BLOCK);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("setrtpradius")) {
            if (!sender.hasPermission("anarchy.admin")) return false;
            if (args.length != 1) return false;
            try {
                int newRadius = Integer.parseInt(args[0]);
                this.rtpRadius = newRadius;
                this.getConfig().set("rtp-radius", newRadius);
                this.saveConfig();
                sender.sendMessage(TextFormat.GREEN + "RTP Radius actualizado: " + newRadius);
            } catch (NumberFormatException e) {
                sender.sendMessage(TextFormat.RED + "Número inválido.");
            }
            return true;
        }
        return false;
    }

    // --- RTP ---
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!rtpEnabled) return;
        if (event.getRespawnPosition().getLevel().getDimension() != Level.DIMENSION_OVERWORLD) return;

        Position respawnPos = event.getRespawnPosition();
        Position worldSpawn = respawnPos.getLevel().getSpawnLocation();

        // Si tiene cama (spawn distinto al world spawn), no hacemos RTP
        if (respawnPos.distanceSquared(worldSpawn) > 0.1) return;

        Level level = this.getServer().getLevelByName(rtpWorld);
        if (level == null) return;

        Position safePos = findSafePosition(level, rtpRadius);
        if (safePos != null) {
            event.setRespawnPosition(safePos);
            this.getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (event.getPlayer().isOnline()) event.getPlayer().sendTip(TextFormat.GREEN + "¡RTP Activado!");
            }, 10);
        }
    }

    private Position findSafePosition(Level level, int radius) {
        for (int i = 0; i < 20; i++) {
            int x = random.nextInt(radius * 2) - radius;
            int z = random.nextInt(radius * 2) - radius;
            int y = level.getHighestBlockAt(x, z);
            Block ground = level.getBlock(x, y, z);
            int id = ground.getId();
            if (id == BlockID.WATER || id == BlockID.STILL_WATER || id == BlockID.LAVA || id == BlockID.STILL_LAVA || id == BlockID.FIRE || id == BlockID.CACTUS) continue;
            return new Position(x + 0.5, y + 1, z + 0.5, level);
        }
        return null;
    }

    // --- Elytra ---
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isGliding()) {
            double distSq = event.getFrom().distanceSquared(event.getTo());
            if (distSq > elytraSpeedLimitSq) {
                event.setCancelled(true);
                return;
            }
            if (elytraHudEnabled) {
                double speedKmh = Math.sqrt(distSq) * 72.0;
                String color = (speedKmh < elytraMaxKmh * 0.8) ? "&a" : "&c";
                player.sendTip(TextFormat.colorize("&bSpeed: " + color + String.format("%.0f", speedKmh) + " &fkm/h"));
            }
        }
    }

    private void checkElytraLag() {
        if (this.getServer().getTicksPerSecond() >= antiElytraTpsLimit) return;
        for (Player player : this.getServer().getOnlinePlayers().values()) {
            if (player.isGliding()) {
                player.setGliding(false);
                player.sendMessage(TextFormat.RED + "⚠ Lag detected. Elytras disabled.");
                if (safeFallEnabled) safeFallPlayers.add(player.getName());
            }
        }
    }

    @EventHandler
    public void onGlideToggle(PlayerToggleGlideEvent event) {
        if (antiElytraEnabled && event.isGliding() && this.getServer().getTicksPerSecond() < antiElytraTpsLimit) {
            event.setCancelled(true);
        }
    }

    // --- Dupes & Interactions ---
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();

        if (antiIllegalEnabled && isItemIllegal(item)) {
            player.getInventory().setItemInHand(Item.get(0));
            event.setCancelled(true);
            return;
        }

        // Frame Dupe (Click Izquierdo a Bloque ItemFrame)
        if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK && event.getBlock().getId() == 199) {
            BlockEntity tile = event.getBlock().getLevel().getBlockEntity(event.getBlock());
            if (tile instanceof BlockEntityItemFrame) {
                Item frameItem = ((BlockEntityItemFrame) tile).getItem();
                if (frameItem != null && frameItem.getId() != 0 && random.nextInt(100) < frameDupeChance) {
                    event.getBlock().getLevel().dropItem(event.getBlock().add(0.5,0.5,0.5), frameItem.clone());
                }
            }
        }

        // Bed Dupe (Click Derecho a Cama)
        if (bedDupeEnabled && event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && 
            event.getBlock().getId() == BlockID.BED_BLOCK && item.getId() != 0) {
            if (random.nextInt(100) < bedDupeChance) {
                player.getLevel().dropItem(player.getPosition(), item.clone());
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (safeFallPlayers.contains(event.getEntity().getName())) {
                event.setCancelled(true);
                safeFallPlayers.remove(event.getEntity().getName());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (antiIllegalEnabled) cleanInventory(event.getPlayer());
        if (customMessagesEnabled && !joinMessages.isEmpty()) {
            String msg = joinMessages.get(random.nextInt(joinMessages.size()));
            event.setJoinMessage(TextFormat.colorize(msg.replace("{player}", event.getPlayer().getName())));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        safeFallPlayers.remove(event.getPlayer().getName());
        if (customMessagesEnabled && !quitMessages.isEmpty()) {
            String msg = quitMessages.get(random.nextInt(quitMessages.size()));
            event.setQuitMessage(TextFormat.colorize(msg.replace("{player}", event.getPlayer().getName())));
        }
    }

    // --- Utils ---
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (antiIllegalEnabled && isItemIllegal(event.getItem())) event.getPlayer().getInventory().setItem(event.getSlot(), Item.get(0));
    }
    
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (antiIllegalEnabled && isItemIllegal(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().getInventory().remove(event.getItem());
        }
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (antiIllegalEnabled && isItemIllegal(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().getInventory().setItemInHand(Item.get(0));
        }
    }

    private void cleanInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        for (Map.Entry<Integer, Item> entry : inv.getContents().entrySet()) {
            if (isItemIllegal(entry.getValue())) inv.setItem(entry.getKey(), Item.get(0));
        }
    }

    private boolean isItemIllegal(Item item) {
        if (item == null || item.getId() == 0) return false;
        if (illegalIds.contains(item.getId())) return true;
        String name = item.getName().toLowerCase();
        return name.contains("reinforced deepslate") || name.contains("soul fire");
    }
}
