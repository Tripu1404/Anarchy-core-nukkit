package com.tripu1404.anarchycore;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.entity.item.EntityItemFrame;
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
    private double elytraSpeedLimitSq; // Usamos el cuadrado para optimizar matemáticas

    // Mensajes
    private boolean customMessagesEnabled;
    private List<String> joinMessages;
    private List<String> quitMessages;

    // Estructuras de datos
    private final Random random = new Random();
    private final Set<Integer> illegalIds = new HashSet<>();
    private final Set<String> safeFallPlayers = new HashSet<>(); // Lista temporal de jugadores protegidos

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
        this.elytraSpeedLimitSq = speedLimit * speedLimit; // Pre-calculamos el cuadrado

        this.customMessagesEnabled = config.getBoolean("custom-messages-enabled", true);
        this.joinMessages = config.getStringList("join-messages");
        this.quitMessages = config.getStringList("quit-messages");

        // 2. Cargar IDs Ilegales
        if (antiIllegalEnabled) {
            loadIllegalIds();
        }

        // 3. Tarea Anti-Elytra (Solo si está activo)
        // Revisa cada 2 segundos (40 ticks) si hay lag para bajar jugadores
        if (antiElytraEnabled) {
            this.getServer().getScheduler().scheduleRepeatingTask(this, this::checkElytraLag, 40);
        }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info(TextFormat.GREEN + "Anarchy Core (tripu1404) activado correctamente.");
    }

    private void loadIllegalIds() {
        illegalIds.add(BlockID.BEDROCK);
        illegalIds.add(BlockID.WATER);
        illegalIds.add(BlockID.STILL_WATER);
        illegalIds.add(BlockID.LAVA);
        illegalIds.add(BlockID.STILL_LAVA);
        illegalIds.add(26); // Bed Block Bug
        illegalIds.add(BlockID.FIRE);
        illegalIds.add(BlockID.MONSTER_SPAWNER);
        illegalIds.add(BlockID.INVISIBLE_BEDROCK);
        illegalIds.add(BlockID.BARRIER);
        illegalIds.add(BlockID.PORTAL);
        illegalIds.add(BlockID.END_PORTAL);
        illegalIds.add(BlockID.END_PORTAL_FRAME);
        illegalIds.add(BlockID.COMMAND_BLOCK);
        illegalIds.add(BlockID.REPEATING_COMMAND_BLOCK);
        illegalIds.add(BlockID.CHAIN_COMMAND_BLOCK);
        illegalIds.add(BlockID.ALLOW);
        illegalIds.add(BlockID.DENY);
        illegalIds.add(BlockID.BORDER_BLOCK);
        illegalIds.add(665); // Soul Fire
        illegalIds.add(-60); // Reinforced Deepslate
    }

    // =========================================================
    //                EVENTOS DE JUGADOR (Join/Quit)
    // =========================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Limpieza de inventario (Anti-32k / Anti-Illegal)
        if (antiIllegalEnabled || anti32kEnabled) {
            cleanInventory(player);
        }

        // Mensaje personalizado
        if (customMessagesEnabled && !joinMessages.isEmpty()) {
            String msg = joinMessages.get(random.nextInt(joinMessages.size()));
            event.setJoinMessage(TextFormat.colorize(msg.replace("{player}", player.getName())));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Limpiar memoria de SafeFall
        safeFallPlayers.remove(event.getPlayer().getName());

        // Mensaje personalizado
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

        // Optimización: Verificar si realmente se movió de bloque o distancia significativa
        boolean sameBlock = event.getFrom().getFloorX() == event.getTo().getFloorX() && 
                            event.getFrom().getFloorY() == event.getTo().getFloorY() && 
                            event.getFrom().getFloorZ() == event.getTo().getFloorZ();

        // 1. Elytra Speed Limit
        if (player.isGliding()) {
            // Usamos distancia al cuadrado para no consumir CPU calculando raíces cuadradas
            if (event.getFrom().distanceSquared(event.getTo()) > elytraSpeedLimitSq) {
                event.setCancelled(true); // Rubberband
                return;
            }
        }

        // 2. Anti-Burrow (Solo si cambió de bloque para ahorrar CPU)
        if (antiBurrowEnabled && !sameBlock) {
            Block block = player.getLevel().getBlock(player.getPosition());
            
            if (block.getId() == BlockID.ENDER_CHEST) {
                AxisAlignedBB playerBB = player.getBoundingBox();
                AxisAlignedBB blockBB = block.getBoundingBox();

                if (blockBB != null) {
                    // Contracción del 10% (0.1 bloques)
                    blockBB.contract(0.1, 0.1, 0.1); 

                    if (blockBB.intersectsWith(playerBB)) {
                        // Daño por sofocación y teleport arriba
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

    // Tarea programada (Scheduler)
    private void checkElytraLag() {
        double currentTps = this.getServer().getTicksPerSecond();
        
        // Si el TPS está bien, no hacemos nada
        if (currentTps >= antiElytraTpsLimit) return;

        // Si hay lag, bajamos a los que vuelan
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

    // Bloquear inicio de vuelo
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
    //                COMBATE & INTERACCIÓN (Dupes + Anti32k)
    // =========================================================

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // Lógica de Safe Fall
        if (event.getEntity() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Player player = (Player) event.getEntity();
            if (safeFallPlayers.contains(player.getName())) {
                event.setCancelled(true); // Anular daño
                safeFallPlayers.remove(player.getName()); // Quitar protección (ya tocó suelo)
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        
        // 1. Frame Dupe
        if (event.getEntity() instanceof EntityItemFrame) {
            if (event.getDamager() instanceof Player) {
                EntityItemFrame frame = (EntityItemFrame) event.getEntity();
                Item itemInFrame = frame.getItem();
                
                if (itemInFrame != null && itemInFrame.getId() != 0) {
                    // Cálculo de probabilidad
                    if ((random.nextInt(100) + 1) <= frameDupeChance) {
                        frame.getLevel().dropItem(frame.getPosition(), itemInFrame.clone());
                    }
                }
            }
        }

        // 2. Anti-32k (Atacante)
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            // Revisar inventario completo. Si se modifica algo, cancelar el golpe.
            if (cleanInventory(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item itemInHand = event.getItem();

        // Anti-32k / Anti-Illegal (Antes de usar el item)
        Item validated = validateItem(itemInHand);
        if (!itemInHand.equals(validated)) {
            player.getInventory().setItemInHand(validated);
            event.setCancelled(true);
            return; 
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

    // =========================================================
    //                INVENTARIO Y LIMPIEZA
    // =========================================================

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!antiIllegalEnabled && !anti32kEnabled) return;
        Item item = event.getItem();
        Item validated = validateItem(item);
        
        // Si el item cambia (se borra o se limpia)
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
            event.setCancelled(true); // Cancelar drop original
            event.getPlayer().getInventory().remove(item); // Borrar item sucio
            
            // Si el item resultante no es aire (era 32k y se limpió), lo soltamos limpio
            if (validated.getId() != 0) {
                 event.getPlayer().dropItem(validated);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!antiIllegalEnabled) return;
        // Si intenta poner un bloque ilegal, se borra de la mano
        if (isItemIllegal(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().getInventory().setItemInHand(Item.get(0));
            event.getPlayer().sendPopup(TextFormat.RED + "¡Bloque Ilegal!");
        }
    }

    // =========================================================
    //                MÉTODOS AUXILIARES
    // =========================================================

    /**
     * Revisa todo el inventario del jugador.
     * @return true si se encontró y arregló algo.
     */
    private boolean cleanInventory(Player player) {
        boolean modified = false;
        PlayerInventory inv = player.getInventory();
        
        // Contenido
        Map<Integer, Item> contents = inv.getContents();
        for (Map.Entry<Integer, Item> entry : contents.entrySet()) {
            Item original = entry.getValue();
            Item validated = validateItem(original);
            if (!original.equals(validated)) {
                inv.setItem(entry.getKey(), validated);
                modified = true;
            }
        }
        
        // Armadura
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

    /**
     * Valida un ítem individualmente.
     */
    private Item validateItem(Item item) {
        if (item == null || item.getId() == 0) return item;

        // Anti-Illegal: Retorna Aire si es bloque prohibido
        if (antiIllegalEnabled && isItemIllegal(item)) {
            return Item.get(0); 
        }

        // Anti-32k: Revisa encantamientos
        if (anti32kEnabled && item.hasEnchantments()) {
            boolean enchantsChanged = false;
            Item clonedItem = item.clone();
            
            for (Enchantment enchantment : clonedItem.getEnchantments()) {
                int maxLevel = enchantment.getMaxLevel();
                if (enchantment.getLevel() > maxLevel) {
                    enchantment.setLevel(maxLevel, true);
                    clonedItem.addEnchantment(enchantment);
                    enchantsChanged = true;
                }
            }
            if (enchantsChanged) return clonedItem;
        }
        return item;
    }

    private boolean isItemIllegal(Item item) {
        if (illegalIds.contains(item.getId())) return true;
        
        // Chequeo de nombre para bloques con IDs variables
        String name = item.getName().toLowerCase();
        return name.contains("reinforced deepslate") || name.contains("soul fire");
    }
}
