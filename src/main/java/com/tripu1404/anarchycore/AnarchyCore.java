package com.tripu1404.anarchycore;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityItemFrame;
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
import cn.nukkit.event.player.PlayerToggleGlideEvent;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class AnarchyCore extends PluginBase implements Listener {

    // --- Configuración ---
    private int frameDupeChance;
    private boolean bedDupeEnabled;
    private int bedDupeChance;
    private boolean anti32kEnabled;
    private boolean antiIllegalEnabled;
    private boolean antiBurrowEnabled;
    
    // Anti-Elytra
    private boolean antiElytraEnabled;
    private double antiElytraTpsLimit;
    private boolean safeFallEnabled;
    private double elytraSpeedLimitSq; 

    // Mensajes
    private boolean customMessagesEnabled;
    private List<String> joinMessages;
    private List<String> quitMessages;

    // Estructuras de datos
    private final Random random = new Random();
    private final Set<Integer> illegalIds = new HashSet<>();
    private final Set<String> safeFallPlayers = new HashSet<>(); 

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Config config = this.getConfig();

        // 1. Cargar Variables
        this.frameDupeChance = config.getInt("frame-dupe-chance", 40);
        this.bedDupeEnabled = config.getBoolean("bed-dupe-enabled", true);
        this.bedDupeChance = config.getInt("bed-dupe-chance", 50);
        this.anti32kEnabled = config.getBoolean("anti-32k-enabled", true);
        this.antiIllegalEnabled = config.getBoolean("anti-illegal-enabled", true);
        this.antiBurrowEnabled = config.getBoolean("anti-burrow-enabled", true);
        
        this.antiElytraEnabled = config.getBoolean("anti-elytra-enabled", true);
        this.antiElytraTpsLimit = config.getDouble("anti-elytra-tps-limit", 15.0);
        this.safeFallEnabled = config.getBoolean("anti-elytra-safe-fall", true);
        
        double speedLimit = config.getDouble("elytra-speed-limit", 3.5);
        this.elytraSpeedLimitSq = speedLimit * speedLimit; 

        this.customMessagesEnabled = config.getBoolean("custom-messages-enabled", true);
        this.joinMessages = config.getStringList("join-messages");
        this.quitMessages = config.getStringList("quit-messages");

        // 2. Cargar IDs Ilegales
        if (antiIllegalEnabled) {
            loadIllegalIds();
        }

        // 3. Tarea Anti-Elytra 
        if (antiElytraEnabled) {
            this.getServer().getScheduler().scheduleRepeatingTask(this, this::checkElytraLag, 40);
        }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info(TextFormat.GREEN + "Anarchy Core (tripu1404) activado correctamente.");
    }

    private void loadIllegalIds() {
        // Usamos IDs numéricos para evitar errores de compilación si cambia la API
        illegalIds.add(BlockID.BEDROCK); // 7
        illegalIds.add(BlockID.WATER); // 8
        illegalIds.add(BlockID.STILL_WATER); // 9
        illegalIds.add(BlockID.LAVA); // 10
        illegalIds.add(BlockID.STILL_LAVA); // 11
        illegalIds.add(26); // Bed Block
        illegalIds.add(BlockID.FIRE); // 51
        illegalIds.add(BlockID.MONSTER_SPAWNER); // 52
        illegalIds.add(95); // Invisible Bedrock (antes BlockID.INVISIBLE_BEDROCK)
        illegalIds.add(90); // Nether Portal (antes BlockID.PORTAL)
        illegalIds.add(119); // End Portal (antes BlockID.END_PORTAL)
        illegalIds.add(120); // End Portal Frame (antes BlockID.END_PORTAL_FRAME)
        illegalIds.add(BlockID.COMMAND_BLOCK); // 137
        illegalIds.add(BlockID.REPEATING_COMMAND_BLOCK); // 188
        illegalIds.add(BlockID.CHAIN_COMMAND_BLOCK); // 189
        illegalIds.add(BlockID.ALLOW); // 210
        illegalIds.add(BlockID.DENY); // 211
        illegalIds.add(BlockID.BORDER_BLOCK); // 212
        illegalIds.add(BlockID.BARRIER); // Variable
        
        // IDs manuales extra
        illegalIds.add(665); // Soul Fire
        illegalIds.add(-60); // Reinforced Deepslate
    }

    // =========================================================
    //                EVENTOS DE JUGADOR (Join/Quit)
    // =========================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (antiIllegalEnabled || anti32kEnabled) {
            cleanInventory(player);
        }

        if (customMessagesEnabled && !joinMessages.isEmpty()) {
            String msg = joinMessages.get(random.nextInt(joinMessages.size()));
            event.setJoinMessage(TextFormat.colorize(msg.replace("{player}", player.getName())));
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
    //                MOVIMIENTO (Burrow & Elytra Speed)
    // =========================================================

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        boolean sameBlock = event.getFrom().getFloorX() == event.getTo().getFloorX() && 
                            event.getFrom().getFloorY() == event.getTo().getFloorY() && 
                            event.getFrom().getFloorZ() == event.getTo().getFloorZ();

        if (player.isGliding()) {
            if (event.getFrom().distanceSquared(event.getTo()) > elytraSpeedLimitSq) {
                event.setCancelled(true); 
                return;
            }
        }

        if (antiBurrowEnabled && !sameBlock) {
            Block block = player.getLevel().getBlock(player.getPosition());
            
            // Usamos ID 130 para Ender Chest por seguridad
            if (block.getId() == 130) {
                AxisAlignedBB playerBB = player.getBoundingBox();
                AxisAlignedBB blockBB = block.getBoundingBox();

                if (blockBB != null) {
                    blockBB.contract(0.1, 0.1, 0.1); 

                    if (blockBB.intersectsWith(playerBB)) {
                        player.attack(new EntityDamageEvent(player, EntityDamageEvent.DamageCause.SUFFOCATION, 2));
                        player.teleport(player.getPosition().add(0, 1.5, 0));
                    }
                }
            }
        }
    }

    // =========================================================
    //                ANTI-ELYTRA (TPS & Safe Fall)
    // =========================================================

    private void checkElytraLag() {
        double currentTps = this.getServer().getTicksPerSecond();
        if (currentTps >= antiElytraTpsLimit) return;

        for (Player player : this.getServer().getOnlinePlayers().values()) {
            if (player.isGliding()) {
                player.setGliding(false);
                player.sendMessage(TextFormat.RED + "⚠ Elytras desactivadas por lag del servidor.");
                if (safeFallEnabled) {
                    safeFallPlayers.add(player.getName());
                }
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
                event.getPlayer().sendPopup(TextFormat.RED + "Vuelo bloqueado (TPS Bajo: " + String.format("%.1f", currentTps) + ")");
            }
        }
    }

    // =========================================================
    //                INTERACCIONES Y DAÑO (Dupes + Anti32k)
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
        // PvP Anti-32k
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

        // 1. Anti-32k / Anti-Illegal (Limpieza previa)
        Item validated = validateItem(itemInHand);
        if (!itemInHand.equals(validated)) {
            player.getInventory().setItemInHand(validated);
            event.setCancelled(true);
            return; 
        }

        // 2. FRAME DUPE (Bloque Item Frame = ID 199)
        if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
            Block block = event.getBlock();
            // Usamos 199 directamente para evitar errores de compilación con BlockID
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

        // 3. BED DUPE (Cama = ID 26)
        if (bedDupeEnabled && event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            // Usamos 26 directamente
            if (event.getBlock().getId() == 26) {
                if (itemInHand.getId() != 0) {
                    if ((random.nextInt(100) + 1) <= bedDupeChance) {
                        player.getLevel().dropItem(player.getPosition(), itemInHand.clone());
                    }
                }
            }
        }
    }

    // =========================================================
    //                INVENTARIO Y LIMPIEZA
    // =========================================================

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!antiIllegalEnabled && !anti32kEnabled) return;
        Item item = event.getItem();
        Item validated = validateItem(item);
        
        if (!item.equals(validated)) {
            event.getPlayer().getInventory().setItem(event.getSlot(), validated);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!antiIllegalEnabled && !anti32kEnabled) return;
        Item item = event.getItem();
        Item validated = validateItem(item);
        
        if (!item.equals(validated)) {
            event.setCancelled(true); 
            event.getPlayer().getInventory().remove(item); 
            
            if (validated.getId() != 0) {
                 event.getPlayer().dropItem(validated);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!antiIllegalEnabled) return;
        if (isItemIllegal(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().getInventory().setItemInHand(Item.get(0));
            event.getPlayer().sendPopup(TextFormat.RED + "¡Bloque Ilegal!");
        }
    }

    // =========================================================
    //                MÉTODOS AUXILIARES
    // =========================================================

    private boolean cleanInventory(Player player) {
        boolean modified = false;
        PlayerInventory inv = player.getInventory();
        
        Map<Integer, Item> contents = inv.getContents();
        for (Map.Entry<Integer, Item> entry : contents.entrySet()) {
            Item original = entry.getValue();
            Item validated = validateItem(original);
            if (!original.equals(validated)) {
                inv.setItem(entry.getKey(), validated);
                modified = true;
            }
        }
        
        Item[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            Item original = armor[i];
            Item validated = validateItem(original);
            if (!original.equals(validated)) {
                inv.setArmorItem(i, validated);
                modified = true;
            }
        }
        return modified;
    }

    private Item validateItem(Item item) {
        if (item == null || item.getId()
