package com.tripu1404.anarchycore;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityEndCrystal;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntityDamageEvent.DamageCause;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.item.Item; // <--- ESTA ERA LA IMPORTACIÓN FALTANTE
import cn.nukkit.plugin.Plugin;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class DeathListener implements Listener {
    
    private final Plugin plugin;
    private final List<Config> deathConfigs = new ArrayList<>();
    private final Map<String, Entity> lastDamager = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public DeathListener(Plugin plugin) {
        this.plugin = plugin;
        // Cargar los 4 archivos de configuración
        for (int i = 1; i <= 4; i++) {
            String fileName = "deathmsg_" + i + ".yml";
            plugin.saveResource(fileName); 
            deathConfigs.add(new Config(plugin.getDataFolder() + "/" + fileName, Config.YAML));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent ev = (EntityDamageByEntityEvent) event;
            if (ev.getEntity() instanceof Player) {
                lastDamager.put(ev.getEntity().getName(), ev.getDamager());
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String victimName = victim.getName();
        
        // Seleccionar aleatoriamente una de las 4 configs
        Config selectedConfig = deathConfigs.get(random.nextInt(deathConfigs.size()));

        EntityDamageEvent lastCause = victim.getLastDamageCause();
        DamageCause cause = (lastCause != null) ? lastCause.getCause() : null;

        Entity damager = null;
        if (lastCause instanceof EntityDamageByEntityEvent) {
            damager = ((EntityDamageByEntityEvent) lastCause).getDamager();
        }
        if (damager == null) damager = lastDamager.get(victimName);

        String attackerName = (damager != null) ? damager.getName() : null;
        String weaponName = "Hand";

        if (damager instanceof Player) {
            Item i = ((Player) damager).getInventory().getItemInHand();
            if (i != null && i.getId() != 0) weaponName = i.getName();
        }

        String message = selectedConfig.getString("CUSTOM", "<Player> died");

        if (cause != null) {
            switch (cause) {
                case FALL:
                    message = (damager instanceof Player && !damager.equals(victim)) 
                        ? selectedConfig.getString("FALL_BY_PLAYER", selectedConfig.getString("FALL"))
                        : selectedConfig.getString("FALL");
                    break;
                case FIRE:
                case FIRE_TICK:
                case LAVA:
                    message = (damager instanceof Player && !damager.equals(victim))
                        ? selectedConfig.getString("FIRE_TICK_BY_PLAYER", selectedConfig.getString("FIRE"))
                        : selectedConfig.getString("FIRE");
                    break;
                case DROWNING:
                    message = (damager instanceof Player && !damager.equals(victim))
                        ? selectedConfig.getString("DROWNING_BY_PLAYER", selectedConfig.getString("DROWNING"))
                        : selectedConfig.getString("DROWNING");
                    break;
                case BLOCK_EXPLOSION:
                    message = (damager instanceof Player)
                        ? selectedConfig.getString("BLOCK_EXPLOSION_BY_PLAYER", selectedConfig.getString("BLOCK_EXPLOSION"))
                        : selectedConfig.getString("BLOCK_EXPLOSION");
                    break;
                case ENTITY_EXPLOSION:
                    boolean isCrystal = (damager instanceof EntityEndCrystal) || 
                                        (damager != null && damager.getName().toLowerCase().contains("crystal"));
                    if (isCrystal) {
                        Entity prev = lastDamager.get(victimName);
                        if (prev instanceof Player) attackerName = prev.getName();
                        message = selectedConfig.getString("ENTITY_EXPLOSION_ENDER_CRYSTAL", selectedConfig.getString("ENTITY_EXPLOSION"));
                    } else {
                        message = selectedConfig.getString("ENTITY_EXPLOSION");
                    }
                    break;
                case ENTITY_ATTACK:
                    message = (damager instanceof Player) ? selectedConfig.getString("KILL_BY_WEAPON") : selectedConfig.getString("MOB_ATTACK");
                    break;
                case PROJECTILE:
                    message = selectedConfig.getString("PROJECTILE");
                    break;
                case VOID:
                    message = selectedConfig.getString("VOID");
                    break;
                case SUICIDE:
                    message = selectedConfig.getString("SUICIDE");
                    break;
                default:
                    if (selectedConfig.exists(cause.name())) message = selectedConfig.getString(cause.name());
                    break;
            }
        }
        
        if (message == null) message = "<Player> died";
        
        message = message.replace("<Player>", victimName)
                         .replace("<Attacker>", (attackerName != null ? attackerName : "Unknown"))
                         .replace("<WeaponName>", weaponName);
        
        event.setDeathMessage(TextFormat.colorize(message));
        lastDamager.remove(victimName);
    }
}
