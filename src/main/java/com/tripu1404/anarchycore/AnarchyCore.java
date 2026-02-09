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

    // --- Configuración General ---
    private int frameDupeChance;
    private boolean bedDupeEnabled;
    private int bedDupeChance;
    private boolean antiIllegalEnabled;
    
    // --- RTP (Random Spawn) ---
    private boolean rtpEnabled;
    private int rtpRadius;
    private String rtpWorld;

    // --- Elytra & HUD ---
    private boolean antiElytraEnabled;
    private double antiElytraTpsLimit;
    private boolean safeFallEnabled;
    private double elytraSpeedLimit;     
    private double elytraSpeedLimitSq;   
    private double elytraMaxKmh;         
    private boolean elytraHudEnabled;

    // --- Mensajes ---
    private boolean customMessagesEnabled;
    private List<String> joinMessages;
    private List<String> quitMessages;

    // --- Estructuras ---
    private final Random random = new Random();
    private final Set<Integer> illegalIds = new HashSet<>();
    private final Set<String> safeFallPlayers = new HashSet<>(); 

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Config config = this.getConfig();

        // 1. Cargar Dupes y Anti-Illegal
        this.frameDupeChance = config.getInt("frame-dupe-chance", 40);
        this.bedDupeEnabled = config.getBoolean("bed-dupe-enabled", true);
        this.bedDupeChance = config.getInt("bed-dupe-chance", 50);
        this.antiIllegalEnabled = config.getBoolean("anti-illegal-enabled", true);

        // 2. Cargar RTP
        this.rtpEnabled = config.getBoolean("rtp-enabled", true);
        this.rtpRadius = config.getInt("rtp-radius", 1000);
        this.rtpWorld = config.getString("rtp-world", "world");

        // 3. Cargar Elytra
        this.antiElytraEnabled = config.getBoolean("anti-elytra-enabled", true);
        this.antiElytraTpsLimit = config.getDouble("anti-elytra-tps-limit", 15.0);
        this.safeFallEnabled = config.getBoolean("anti-elytra-safe-fall", true);
        this.elytraHudEnabled = config.getBoolean("elytra-hud-enabled", true);
        
        this.elytraSpeedLimit = config.getDouble("elytra-speed-limit", 3.5);
        this.elytraSpeedLimitSq = elytraSpeedLimit * elytraSpeedLimit;
        this.elytraMaxKmh = elytraSpeedLimit * 72.0; 

        // 4. Mensajes y limpieza
        this.customMessagesEnabled = config.getBoolean("custom-messages-enabled", true);
        this.joinMessages = config.getStringList("join-messages");
        this.quitMessages = config.getStringList("quit-messages");

        if (antiIllegalEnabled) loadIllegalIds();

        // Tarea Anti-Lag Elytra
        if (antiElytraEnabled) {
            this.getServer().getScheduler().scheduleRepeatingTask(this, this::checkElytraLag, 40);
        }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info(TextFormat.GREEN + "Anarchy Core (tripu1404) activado.");
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
        illegalIds.add(90); // Portal
        illegalIds.add(BlockID.END_PORTAL);
        illegalIds.add(BlockID.END_PORTAL_FRAME);
        illegalIds.add(BlockID.COMMAND_BLOCK);
        illegalIds.add(BlockID.REPEATING_COMMAND_BLOCK);
        illegalIds.add(BlockID.CHAIN_COMMAND_BLOCK);
        illegalIds.add(BlockID.ALLOW);
        illegalIds.add(BlockID.DENY);
        illegalIds.add(BlockID.BORDER_BLOCK);
    }

    // =========================================================
    //                COMANDOS (GUARDA EN CONFIG)
    // =========================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("setrtpradius")) {
            
            // 1. Permisos
            if (!sender.hasPermission("anarchy.admin")) {
                sender.sendMessage(TextFormat.RED + "No tienes permiso para usar esto.");
                return true;
            }

            // 2. Argumentos
            if (args.length != 1) {
                sender.sendMessage(TextFormat.RED + "Uso: /setrtpradius <radio>");
                return true;
            }

            try {
                int newRadius = Integer.parseInt(args[0]);
                
                if (newRadius < 100) {
                    sender.sendMessage(TextFormat.RED + "El radio debe ser al menos 100 bloques.");
                    return true;
                }

                // 3. Actualizar variable en memoria (para que funcione ya)
                this.rtpRadius = newRadius;
                
                // 4. GUARDAR EN DISCO (config.yml)
                this.getConfig().set("rtp-radius", newRadius);
                this.saveConfig(); // <-- ESTA LÍNEA GUARDA EL ARCHIVO

                sender.sendMessage(TextFormat.GREEN + "Radio RTP guardado y actualizado a: " + TextFormat.WHITE + newRadius + " bloques.");
                
            } catch (NumberFormatException e) {
                sender.sendMessage(TextFormat.RED + "Por favor, introduce un número válido.");
            }
            return true;
        }
        return false;
    }

    // =========================================================
    //                RANDOM SPAWN (RTP INTELIGENTE)
    // =========================================================

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!rtpEnabled) return;

        // Solo Overworld
        int dimension = event.getRespawnPosition().getLevel().getDimension();
        if (dimension != Level.DIMENSION_OVERWORLD) {
            return; 
        }

        // Respetar Cama / Nexo
        Position respawnPos = event.getRespawnPosition();
        Position worldSpawn = respawnPos.getLevel().getSpawnLocation();

        if (respawnPos.distanceSquared(worldSpawn) > 0.1) {
            return; 
        }

        Player player = event.getPlayer();
        Level level = this.getServer().getLevelByName(rtpWorld);
        
        if (level == null) return;

        Position safePos = findSafePosition(level, rtpRadius);
        
        if (safePos != null) {
            event.setRespawnPosition(safePos);
            this.getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (player.isOnline()) {
                    player.sendTip(TextFormat.GREEN + "¡Respawn Aleatorio!");
                }
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
            if (id == BlockID.WATER || id == BlockID.STILL_WATER || 
                id == BlockID.LAVA || id == BlockID.STILL_LAVA || 
                id == BlockID.FIRE || id == BlockID.CACTUS) {
                continue;
            }
            return new Position(x + 0.5, y + 1, z + 0.5, level);
        }
        return null;
    }

    // =========================================================
    //                MOVIMIENTO Y HUD ELYTRA
    // =========================================================

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
                double dist = Math.sqrt(distSq);
                double speedKmh = dist * 72.0;

                String color;
                if (speedKmh < elytraMaxKmh * 0.5) color = "&a"; 
                else if (speedKmh < elytraMaxKmh * 0.8) color = "&e"; 
                else color = "&c"; 

                String hud = TextFormat.colorize("&bVelocidad: " + color + String.format("%.0f", speedKmh) + " &fkm/h &7/ &c" + String.format("%.0f", elytraMaxKmh) + " &7km/h");
                player.sendTip(hud);
            }
        }
    }

    // =========================================================
    //                ANTI-ELYTRA (Lag & Safe Fall)
    // =========================================================

    private void checkElytraLag() {
        double currentTps = this.getServer().getTicksPerSecond();
        if (currentTps >= antiElytraTpsLimit) return;

        for (Player player : this.getServer().getOnlinePlayers().values()) {
            if (player.isGliding()) {
                player.setGliding(false);
                player.sendMessage(TextFormat.RED + "⚠ Elytras desactivadas por lag.");
                if (safeFallEnabled) safeFallPlayers.add(player.getName());
            }
        }
    }

    @EventHandler
    public void onGlideToggle(PlayerToggleGlideEvent event) {
        if (!antiElytraEnabled) return;
        if (event.isGliding()) {
            double currentTps = this.getServer().getTicksPerSecond();
            if (currentTps < antiElytraTpsLimit) {
                event.setCancelled(true);
                event.getPlayer().sendPopup(TextFormat.RED + "Vuelo bloqueado por bajo TPS.");
            }
        }
    }

    // =========================================================
    //                INTERACCIONES & CLEANUP
    // =========================================================

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Player player = (Player) event.getEntity();
            if (safeFallPlayers.contains(player.getName())) {
                event.setCancelled(true);
                safeFallPlayers.remove(player.getName());
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (cleanInventory(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item itemInHand = event.getItem();

        if (antiIllegalEnabled && isItemIllegal(itemInHand)) {
            player.getInventory().setItemInHand(Item.get(0));
            event.setCancelled(true);
            return;
        }

        // Frame Dupe
        if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
            Block block = event.getBlock();
            if (block.getId() == 199) { 
                BlockEntity tile = block.getLevel().getBlockEntity(block);
                if (tile instanceof BlockEntityItemFrame) {
                    BlockEntityItemFrame frame = (BlockEntityItemFrame) tile;
                    Item itemInFrame = frame.getItem();
                    if (itemInFrame != null && itemInFrame.getId() != 0) {
                        if ((random.nextInt(100) + 1) <= frameDupeChance) {
                            block.getLevel().dropItem(block.add(0.5, 0.5, 0.5), itemInFrame.clone());
                        }
                    }
                }
            }
        }

        // Bed Dupe
        if (bedDupeEnabled && event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            if (event.getBlock().getId() == BlockID.BED_BLOCK) {
                if (itemInHand.getId() != 0) {
                    if ((random.nextInt(100) + 1) <= bedDupeChance) {
                        player.getLevel().dropItem(player.getPosition(), itemInHand.clone());
                    }
                }
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

    // =========================================================
    //                UTILIDADES
    // =========================================================

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (antiIllegalEnabled && isItemIllegal(event.getItem())) {
            event.getPlayer().getInventory().setItem(event.getSlot(), Item.get(0));
        }
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

    private boolean cleanInventory(Player player) {
        boolean modified = false;
        PlayerInventory inv = player.getInventory();
        Map<Integer, Item> contents = inv.getContents();
        for (Map.Entry<Integer, Item> entry : contents.entrySet()) {
            if (isItemIllegal(entry.getValue())) {
                inv.setItem(entry.getKey(), Item.get(0));
                modified = true;
            }
        }
        return modified;
    }

    private boolean isItemIllegal(Item item) {
        if (item == null || item.getId() == 0) return false;
        if (illegalIds.contains(item.getId())) return true;
        String name = item.getName().toLowerCase();
        return name.contains("reinforced deepslate") || name.contains("soul fire");
    }
}
