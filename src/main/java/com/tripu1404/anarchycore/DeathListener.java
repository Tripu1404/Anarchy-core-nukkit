package com.tripu1404.anarchycore;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityEndCrystal;
import cn.nukkit.entity.item.EntityPrimedTNT;
import cn.nukkit.entity.mob.EntityCreeper;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class DeathListener implements Listener {

    private final AnarchyCore plugin;
    private Config messagesConfig;
    private final Random random = new Random();
    
    // Almacena quién golpeó a quién por última vez (para muertes indirectas)
    private final Map<String, String> lastAttacker = new HashMap<>();
    private final Map<String, Long> lastAttackTime = new HashMap<>();

    public DeathListener(AnarchyCore plugin) {
        this.plugin = plugin;
        // Cargamos el archivo death_messages.yml
        plugin.saveResource("death_messages.yml");
        this.messagesConfig = new Config(plugin.getDataFolder() + "/death_messages.yml", Config.YAML);
    }

    // Rastreador de daño para PvP y Cristales
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent ev = (EntityDamageByEntityEvent) event;
            if (ev.getEntity() instanceof Player && ev.getDamager() instanceof Player) {
                Player victim = (Player) ev.getEntity();
                Player attacker = (Player) ev.getDamager();
                
                lastAttacker.put(victim.getName(), attacker.getName());
                lastAttackTime.put(victim.getName(), System.currentTimeMillis());
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        EntityDamageEvent cause = player.getLastDamageCause();
        String msgKey = "unknown";
        String killerName = "";
        String itemName = "";

        // Detectar causa de muerte
        if (cause != null) {
            if (cause instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent ev = (EntityDamageByEntityEvent) cause;
                Entity damager = ev.getDamager();

                // 1. Muerte por JUGADOR (PvP Directo)
                if (damager instanceof Player) {
                    msgKey = "pvp";
                    killerName = damager.getName();
                    Item item = ((Player) damager).getInventory().getItemInHand();
                    itemName = (item != null && item.getId() != 0) ? item.getName() : "sus manos";
                } 
                // 2. Muerte por CRISTAL (End Crystal)
                else if (damager instanceof EntityEndCrystal) {
                    msgKey = "crystal";
                    // Intentamos ver si alguien golpeó al jugador recientemente
                    if (isValidAttacker(player.getName())) {
                        killerName = lastAttacker.get(player.getName());
                    } else {
                        // Si nadie lo golpeó, el asesino es el propio cristal
                        killerName = "End Crystal";
                        msgKey = "explosion"; // Fallback a explosión genérica
                    }
                }
                // 3. Muerte por TNT
                else if (damager instanceof EntityPrimedTNT) {
                    msgKey = "explosion";
                    killerName = "TNT";
                }
                // 4. Muerte por CREEPER
                else if (damager instanceof EntityCreeper) {
                    msgKey = "explosion";
                    killerName = "Creeper";
                }
                // 5. Muerte por OTROS MOBS
                else {
                    msgKey = "pvp"; // Usamos mensaje genérico de asesinato
                    killerName = damager.getName();
                    itemName = "fuerza bruta";
                }
            } 
            // Causas ambientales
            else {
                switch (cause.getCause()) {
                    case FALL:
                        if (isValidAttacker(player.getName())) {
                            msgKey = "fall_pvp";
                            killerName = lastAttacker.get(player.getName());
                        } else {
                            msgKey = "fall";
                        }
                        break;
                    case LAVA:
                    case FIRE:
                    case FIRE_TICK:
                        msgKey = "fire";
                        break;
                    case ENTITY_EXPLOSION: // A veces TNT/Creeper cae aquí sin damager directo
                    case BLOCK_EXPLOSION:
                        msgKey = "explosion";
                        killerName = "Explosión";
                        break;
                    case VOID:
                        if (isValidAttacker(player.getName())) {
                            msgKey = "fall_pvp"; // Empujado al vacío
                            killerName = lastAttacker.get(player.getName());
                        } else {
                            msgKey = "fall";
                        }
                        break;
                }
            }
        }

        // Obtener lista de mensajes y elegir uno aleatorio
        List<String> messages = messagesConfig.getStringList(msgKey);
        if (messages == null || messages.isEmpty()) {
            messages = messagesConfig.getStringList("unknown");
        }

        String randomMsg = messages.get(random.nextInt(messages.size()));

        // Reemplazar variables
        randomMsg = randomMsg.replace("{player}", player.getName())
                             .replace("{killer}", killerName)
                             .replace("{item}", itemName);

        // Establecer mensaje final con colores
        event.setDeathMessage(TextFormat.colorize(randomMsg));
        
        // Limpiar caché de atacante
        lastAttacker.remove(player.getName());
    }

    // Verifica si el atacante es válido (si el golpe fue hace menos de 15 segundos)
    private boolean isValidAttacker(String playerName) {
        if (!lastAttacker.containsKey(playerName)) return false;
        long timeDiff = System.currentTimeMillis() - lastAttackTime.getOrDefault(playerName, 0L);
        return timeDiff < 15000; // 15 segundos de memoria
    }
}
