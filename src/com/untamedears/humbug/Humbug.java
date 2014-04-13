package com.untamedears.humbug;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.logging.Logger;

import net.minecraft.server.v1_7_R2.EntityTypes;
import net.minecraft.server.v1_7_R2.Item;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.gimmicknetwork.gimmickapi.GimmickAPI;
import com.untamedears.humbug.CombatTagManager;
import com.untamedears.humbug.Versioned;
import com.untamedears.humbug.annotations.BahHumbug;
import com.untamedears.humbug.annotations.BahHumbugs;
import com.untamedears.humbug.annotations.ConfigOption;
import com.untamedears.humbug.annotations.OptType;
import com.untamedears.humbug.Config;
import com.untamedears.humbug.CustomNMSItemEnderPearl;

public class Humbug extends JavaPlugin implements Listener {
  public static void severe(String message) {
    log_.severe("[Humbug] " + message);
  }

  public static void warning(String message) {
    log_.warning("[Humbug] " + message);
  }

  public static void info(String message) {
    log_.info("[Humbug] " + message);
  }

  public static void debug(String message) {
    if (config_.getDebug()) {
      log_.info("[Humbug] " + message);
    }
  }

  public static Humbug getPlugin() {
    return global_instance_;
  }

  private static final Logger log_ = Logger.getLogger("Humbug");
  private static Humbug global_instance_ = null;
  private static Config config_ = null;
  private static int max_golden_apple_stack_ = 1;

  static {
    max_golden_apple_stack_ = Material.GOLDEN_APPLE.getMaxStackSize();
    if (max_golden_apple_stack_ > 64) {
      max_golden_apple_stack_ = 64;
    }
  }

  private Random prng_ = new Random();
  private CombatTagManager combatTag_ = new CombatTagManager();

  public Humbug() {}
  
  // ================================================
  // GIMMICKS INTEGRATION
  // Uses GimmickAPI to check player's pvp modes before applying changes to
  //  pvp related events.
  
  private final String THIS_PVP_MODE = "civcraft"; // name of this pvp mode used by Gimmicks
  //check if the player is in civcraft pvp mode
  public Boolean isPlayerInThisPvpMode (Player player) {
	//check if Gimmicks is running
	if (!Bukkit.getPluginManager().isPluginEnabled("GimmickAPI")) {
		return true; //default to enabling this plugin's pvp stuff
	}
	String pvpMode = GimmickAPI.getPvpModeForPlayer(player);
	if (pvpMode != null) {
	    if (pvpMode.equals(THIS_PVP_MODE)) {
	    	return true;
	    } else {
	    	return false;
	    }
	} else {
		log_.warning("[HUMBUG-GIMMICK] "+player.getName().toString()+"'s pvpMode is null.");
		return false;
	}
  }
    
  
  // ================================================
  // Reduce registered PlayerInteractEvent count. onPlayerInteractAll handles
  //  cancelled events.

  @EventHandler(priority = EventPriority.LOWEST) // ignoreCancelled=false
  public void onPlayerInteractAll(PlayerInteractEvent event) {
    onPlayerEatGoldenApple(event);
    throttlePearlTeleport(event);
  }

  // ================================================
  // Fixes Teleporting through walls and doors
  // ** and **
  // Ender Pearl Teleportation disabling
  // ** and **
  // Ender pearl cooldown timer

  private class PearlTeleportInfo {
    public long last_teleport;
    public long last_notification;
  }
  private Map<String, PearlTeleportInfo> pearl_teleport_info_
      = new TreeMap<String, PearlTeleportInfo>();
  private final static int PEARL_THROTTLE_WINDOW = 10000;  // 10 sec
  private final static int PEARL_NOTIFICATION_WINDOW = 1000;  // 1 sec

  // EventHandler registered in onPlayerInteractAll
  @BahHumbug(opt="ender_pearl_teleportation_throttled", def="true")
  public void throttlePearlTeleport(PlayerInteractEvent event) {
	
    // GIMMICKS
    // return if not in this game mode
    if (!isPlayerInThisPvpMode(event.getPlayer())) {
      return;
    }
	  
    if (!config_.get("ender_pearl_teleportation_throttled").getBool()) {
      return;
    }
    if (event.getItem() == null || !event.getItem().getType().equals(Material.ENDER_PEARL)) {
      return;
    }
    final Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    final Block clickedBlock = event.getClickedBlock();
    BlockState clickedState = null;
    Material clickedMaterial = null;
    if (clickedBlock != null) {
      clickedState = clickedBlock.getState();
      clickedMaterial = clickedState.getType();
    }
    if (clickedState != null && (
          clickedState instanceof InventoryHolder
          || clickedMaterial.equals(Material.ANVIL)
          || clickedMaterial.equals(Material.ENCHANTMENT_TABLE)
          || clickedMaterial.equals(Material.ENDER_CHEST)
          || clickedMaterial.equals(Material.WORKBENCH))) {
      // Prevent Combat Tag/Pearl cooldown on inventory access
      return;
    }
    final long current_time = System.currentTimeMillis();
    final Player player = event.getPlayer();
    final String player_name = player.getName();
    PearlTeleportInfo teleport_info = pearl_teleport_info_.get(player_name);
    long time_diff = 0;
    if (teleport_info == null) {
      // New pearl thrown outside of throttle window
      teleport_info = new PearlTeleportInfo();
      teleport_info.last_teleport = current_time;
      teleport_info.last_notification =
          current_time - (PEARL_NOTIFICATION_WINDOW + 100);  // Force notify
      combatTag_.tagPlayer(player);
    } else {
      time_diff = current_time - teleport_info.last_teleport;
      if (PEARL_THROTTLE_WINDOW > time_diff) {
        // Pearl throw throttled
        event.setCancelled(true);
      } else {
        // New pearl thrown outside of throttle window
        combatTag_.tagPlayer(player);
        teleport_info.last_teleport = current_time;
        teleport_info.last_notification =
            current_time - (PEARL_NOTIFICATION_WINDOW + 100);  // Force notify
        time_diff = 0;
      }
    }
    final long notify_diff = current_time - teleport_info.last_notification;
    if (notify_diff > PEARL_NOTIFICATION_WINDOW) {
      teleport_info.last_notification = current_time;
      Integer tagCooldown = combatTag_.remainingSeconds(player);
      if (tagCooldown != null) {
        player.sendMessage(String.format(
            "Pearl in %d seconds. Combat Tag in %d seconds.",
            (PEARL_THROTTLE_WINDOW - time_diff + 500) / 1000,
            tagCooldown));
      } else {
        player.sendMessage(String.format(
            "Pearl Teleport Cooldown: %d seconds",
            (PEARL_THROTTLE_WINDOW - time_diff + 500) / 1000));
      }
    }
    pearl_teleport_info_.put(player_name, teleport_info);
    return;
  }

  @BahHumbugs({
    @BahHumbug(opt="ender_pearl_teleportation", def="true"),
    @BahHumbug(opt="fix_teleport_glitch", def="true")
  })
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
	  
    // GIMMICKS
    // return if not in this game mode
    if (!isPlayerInThisPvpMode(event.getPlayer())) {
      return;
    }
    
    TeleportCause cause = event.getCause();
    if (cause != TeleportCause.ENDER_PEARL) {
      return;
    } else if (!config_.get("ender_pearl_teleportation").getBool()) {
      event.setCancelled(true);
      return;
    }
    if (!config_.get("fix_teleport_glitch").getBool()) {
      return;
    }
    Location to = event.getTo();
    World world = to.getWorld();

    // From and To are feet positions.  Check and make sure we can teleport to a location with air
    // above the To location.
    Block toBlock = world.getBlockAt(to);
    Block aboveBlock = world.getBlockAt(to.getBlockX(), to.getBlockY()+1, to.getBlockZ());
    Block belowBlock = world.getBlockAt(to.getBlockX(), to.getBlockY()-1, to.getBlockZ());
    boolean lowerBlockBypass = false;
    double height = 0.0;
    switch( toBlock.getType() ) {
    case CHEST: // Probably never will get hit directly
    case ENDER_CHEST: // Probably never will get hit directly
      height = 0.875;
      break;
    case STEP:
      lowerBlockBypass = true;
      height = 0.5;
      break;
    case WATER_LILY:
      height = 0.016;
      break;
    case ENCHANTMENT_TABLE:
      lowerBlockBypass = true;
      height = 0.75;
      break;
    case BED:
    case BED_BLOCK:
      // This one is tricky, since even with a height offset of 2.5, it still glitches.
      //lowerBlockBypass = true;
      //height = 0.563;
      // Disabling teleporting on top of beds for now by leaving lowerBlockBypass false.
      break;
    case FLOWER_POT:
    case FLOWER_POT_ITEM:
      height = 0.375;
      break;
    case SKULL: // Probably never will get hit directly
      height = 0.5;
      break;
    default:
      break;
    }
    // Check if the below block is difficult
    // This is added because if you face downward directly on a gate, it will
    // teleport your feet INTO the gate, thus bypassing the gate until you leave that block.
    switch( belowBlock.getType() ) {
    case FENCE:
    case FENCE_GATE:
    case NETHER_FENCE:
    case COBBLE_WALL:
      height = 0.5;
      break;
    default:
      break;
    }

    boolean upperBlockBypass = false;
    if( height >= 0.5 ) {
      Block aboveHeadBlock = world.getBlockAt(aboveBlock.getX(), aboveBlock.getY()+1, aboveBlock.getZ());
      if( false == aboveHeadBlock.getType().isSolid() ) {
        height = 0.5;
      } else {
        upperBlockBypass = true; // Cancel this event.  What's happening is the user is going to get stuck due to the height.
      }
    }

    // Normalize teleport to the center of the block.  Feet ON the ground, plz.
    // Leave Yaw and Pitch alone
    to.setX(Math.floor(to.getX()) + 0.5000);
    to.setY(Math.floor(to.getY()) + height);
    to.setZ(Math.floor(to.getZ()) + 0.5000);

    if(aboveBlock.getType().isSolid() ||
       (toBlock.getType().isSolid() && !lowerBlockBypass) ||
       upperBlockBypass ) {
      // One last check because I care about Top Nether.  (someone build me a shrine up there)
      boolean bypass = false;
      if ((world.getEnvironment() == Environment.NETHER) &&
          (to.getBlockY() > 124) && (to.getBlockY() < 129)) {
        bypass = true;
      }
      if (!bypass) {
        event.setCancelled(true);
      }
    }
  }


  // ================================================
  // Enchanted Golden Apple

  public boolean isEnchantedGoldenApple(ItemStack item) {
    // Golden Apples are GOLDEN_APPLE with 0 durability
    // Enchanted Golden Apples are GOLDEN_APPLE with 1 durability
    if (item == null) {
      return false;
    }
    if (item.getDurability() != 1) {
      return false;
    }
    Material material = item.getType();
    return material.equals(Material.GOLDEN_APPLE);
  }

  public void replaceEnchantedGoldenApple(
      String player_name, ItemStack item, int inventory_max_stack_size) {
    if (!isEnchantedGoldenApple(item)) {
      return;
    }
    int stack_size = max_golden_apple_stack_;
    if (inventory_max_stack_size < max_golden_apple_stack_) {
      stack_size = inventory_max_stack_size;
    }
    info(String.format(
          "Replaced %d Enchanted with %d Normal Golden Apples for %s",
          item.getAmount(), stack_size, player_name));
    item.setDurability((short)0);
    item.setAmount(stack_size);
  }

  @BahHumbug(opt="ench_gold_app_craftable")
  public void removeRecipies() {
    if (config_.get("ench_gold_app_craftable").getBool()) {
      return;
    }
    Iterator<Recipe> it = getServer().recipeIterator();
    while (it.hasNext()) {
      Recipe recipe = it.next();
      ItemStack resulting_item = recipe.getResult();
      if ( // !ench_gold_app_craftable_ &&
          isEnchantedGoldenApple(resulting_item)) {
        it.remove();
        info("Enchanted Golden Apple Recipe disabled");
      }
    }
  }

  // EventHandler registered in onPlayerInteractAll
  @BahHumbug(opt="ench_gold_app_edible")
  public void onPlayerEatGoldenApple(PlayerInteractEvent event) {
	  
    // GIMMICKS
    // return if not in this game mode
    if (!isPlayerInThisPvpMode(event.getPlayer())) {
      return;
    }
	    
    // The event when eating is cancelled before even LOWEST fires when the
    //  player clicks on AIR.
    if (config_.get("ench_gold_app_edible").getBool()) {
      return;
    }
    Player player = event.getPlayer();
    Inventory inventory = player.getInventory();
    ItemStack item = event.getItem();
    replaceEnchantedGoldenApple(
        player.getName(), item, inventory.getMaxStackSize());
  }


  // ================================================
  // Counteract 1.4.6 protection enchant nerf

  @BahHumbug(opt="scale_protection_enchant", def="true")
  @EventHandler(priority = EventPriority.LOWEST) // ignoreCancelled=false
  public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent event) {

    // GIMMICKS
    // return if not in this game mode
	if (event.getDamager() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getDamager())) {
        return;
      }
	}
	    
    if (!config_.get("scale_protection_enchant").getBool()) {
        return;
    }
    double damage = event.getDamage();
    if (damage <= 0.0000001D) {
      return;
    }
    DamageCause cause = event.getCause();
    if (!cause.equals(DamageCause.ENTITY_ATTACK) &&
            !cause.equals(DamageCause.PROJECTILE)) {
        return;
    }
    Entity entity = event.getEntity();
    if (!(entity instanceof Player)) {
      return;
    }
    Player defender = (Player)entity;
    PlayerInventory inventory = defender.getInventory();
    int enchant_level = 0;
    for (ItemStack armor : inventory.getArmorContents()) {
      enchant_level += armor.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL);
    }
    int damage_adjustment = 0;
    if (enchant_level >= 3 && enchant_level <= 6) {
      // 0 to 2
      damage_adjustment = prng_.nextInt(3);
    } else if (enchant_level >= 7 && enchant_level <= 10) {
      // 0 to 3
      damage_adjustment = prng_.nextInt(4);
    } else if (enchant_level >= 11 && enchant_level <= 14) {
      // 1 to 4
      damage_adjustment = prng_.nextInt(4) + 1;
    } else if (enchant_level >= 15) {
      // 2 to 4
      damage_adjustment = prng_.nextInt(3) + 2;
    }
    damage = Math.max(damage - (double)damage_adjustment, 0.0D);
    event.setDamage(damage);
  }

  @BahHumbug(opt="player_max_health", type=OptType.Int, def="20")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled=true)
  public void onPlayerJoinEvent(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    player.setMaxHealth((double)config_.get("player_max_health").getInt());
  }


  //=================================================
  // Changes Strength Potions, strength_multiplier 3 is roughly Pre-1.6 Level

  @BahHumbugs ({
    @BahHumbug(opt="nerf_strength", def="true"),
    @BahHumbug(opt="strength_multiplier", type=OptType.Int, def="3")
  })
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPlayerDamage(EntityDamageByEntityEvent event) {
	 
    // GIMMICKS
    // return if not in this game mode
	if (event.getDamager() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getDamager())) {
        return;
      }
	}
	  
    if (!config_.get("nerf_strength").getBool()) {
      return;
    }
    if (!(event.getDamager() instanceof Player)) {
      return;
    }
    Player player = (Player)event.getDamager();
    final int strengthMultiplier = config_.get("strength_multiplier").getInt();
    if (player.hasPotionEffect(PotionEffectType.INCREASE_DAMAGE)) {
      for (PotionEffect effect : player.getActivePotionEffects()) {
        if (effect.getType().equals(PotionEffectType.INCREASE_DAMAGE)) {
          final int potionLevel = effect.getAmplifier() + 1;
          final double unbuffedDamage = event.getDamage() / (1.3 * potionLevel + 1);
          final double newDamage = unbuffedDamage + (potionLevel * strengthMultiplier);
          event.setDamage(newDamage);
          break;
        }
      }
    }
  }

  //=================================================
  // Buffs health splash to pre-1.6 levels

  @BahHumbug(opt="buff_health_pots", def="true")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPotionSplash(PotionSplashEvent event) {
	    
    if (!config_.get("buff_health_pots").getBool()) {
      return;
    }
    for (PotionEffect effect : event.getEntity().getEffects()) {
      if (!(effect.getType().getName().equalsIgnoreCase("heal"))) { // Splash potion of poison
        return;
      }
    }
    for (LivingEntity entity : event.getAffectedEntities()) {
      if (entity instanceof Player) {
    	if (isPlayerInThisPvpMode((Player) entity)) { // Gimmicks - Checks if entity is in this pvp mode
        if(((Damageable)entity).getHealth() > 0d) {
            final double newHealth = Math.min(
              ((Damageable)entity).getHealth() + 4.0D,
              ((Damageable)entity).getMaxHealth());
            entity.setHealth(newHealth);
          }
    	}
      }
    }
  }

  //=================================================
  // Bow shots cause slow debuff

  @BahHumbugs ({
    @BahHumbug(opt="projectile_slow_chance", type=OptType.Int, def="30"),
    @BahHumbug(opt="projectile_slow_ticks", type=OptType.Int, def="100")
  })
  @EventHandler
  public void onEDBE(EntityDamageByEntityEvent event) {

    // GIMMICKS
    // return if not in this game mode
	if (event.getDamager() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getDamager())) {
        return;
      }
	}

    int rate = config_.get("projectile_slow_chance").getInt();
    if (rate <= 0 || rate > 100) {
      return;
    }
    if (!(event.getEntity() instanceof Player)) {
      return;
    }
    boolean damager_is_player_arrow = false;
    int chance_scaling = 0;
    Entity damager_entity = event.getDamager();
    if (damager_entity != null) {
      // public LivingEntity CraftArrow.getShooter()
      // Playing this game to not have to take a hard dependency on
      //  craftbukkit internals.
      try {
        Class<?> damager_class = damager_entity.getClass();
        if (damager_class.getName().endsWith(".CraftArrow")) {
          Method getShooter = damager_class.getMethod("getShooter");
          Object result = getShooter.invoke(damager_entity);
          if (result instanceof Player) {
            damager_is_player_arrow = true;
            String player_name = ((Player)result).getName();
            if (bow_level_.containsKey(player_name)) {
              chance_scaling = bow_level_.get(player_name);
            }
          }
        }
      } catch(Exception ex) {}
    }
    if (!damager_is_player_arrow) {
      return;
    }
    rate += chance_scaling * 5;
    int percent = prng_.nextInt(100);
    if (percent < rate){
      int ticks = config_.get("projectile_slow_ticks").getInt();
      Player player = (Player)event.getEntity();
      player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, ticks, 1, false));
    }
  }

  // Used to track bow enchantment levels per player
  private Map<String, Integer> bow_level_ = new TreeMap<String, Integer>();

  @EventHandler
  public void onEntityShootBow(EntityShootBowEvent event) {
	  
    // GIMMICKS
    // return if not in this game mode
	if (event.getEntity() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getEntity())) {
        return;
      }
	}
	  
    if (!(event.getEntity() instanceof Player)) {
         return;
    }
    int ench_level = 0;
    ItemStack bow = event.getBow();
    Map<Enchantment, Integer> enchants = bow.getEnchantments();
    for (Enchantment ench : enchants.keySet()) {
      int tmp_ench_level = 0;
      if (ench.equals(Enchantment.KNOCKBACK) || ench.equals(Enchantment.ARROW_KNOCKBACK)) {
        tmp_ench_level = enchants.get(ench) * 2;
      } else if (ench.equals(Enchantment.ARROW_DAMAGE)) {
        tmp_ench_level = enchants.get(ench);
      }
      if (tmp_ench_level > ench_level) {
        ench_level = tmp_ench_level;
      }
    }
    bow_level_.put(
        ((Player)event.getEntity()).getName(),
        ench_level);
  }


  // ================================================
  // Adjust horse speeds

  @BahHumbug(opt="horse_speed", type=OptType.Double, def="0.170000")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onVehicleEnter(VehicleEnterEvent event) {
    // 0.17 is just a tad slower than minecarts
    Vehicle vehicle = event.getVehicle();
    if (!(vehicle instanceof Horse)) {
      return;
    }
    Versioned.setHorseSpeed((Entity)vehicle, config_.get("horse_speed").getDouble());
  }

  // ================================================
  // Admins can view player inventories

  @SuppressWarnings("deprecation")
  public void onInvseeCommand(Player admin, String playerName) {
    final Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) {
      admin.sendMessage("Player not found");
      return;
    }
    final Inventory pl_inv = player.getInventory();
    final Inventory inv = Bukkit.createInventory(
        admin, 36, "Player inventory: " + playerName);
    for (int slot = 0; slot < 36; slot++) {
      final ItemStack it = pl_inv.getItem(slot);
      inv.setItem(slot, it);
    }
    admin.openInventory(inv);
    admin.updateInventory();
  }

  // ================================================
  // Fix boats

  @BahHumbug(opt="prevent_self_boat_break", def="true")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPreventLandBoats(VehicleDestroyEvent event) {
    // GIMMICKS
    // return if not in this game mode
	if (event.getVehicle().getPassenger() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getVehicle().getPassenger())) {
        return;
      }
	}
	
    if (!config_.get("prevent_land_boats").getBool()) {
      return;
    }
    final Vehicle vehicle = event.getVehicle();
    if (vehicle == null || !(vehicle instanceof Boat)) {
      return;
    }
    final Entity passenger = vehicle.getPassenger();
    if (passenger == null || !(passenger instanceof Player)) {
      return;
    }
    final Entity attacker = event.getAttacker();
    if (attacker == null) {
      return;
    }
    if (!attacker.getUniqueId().equals(passenger.getUniqueId())) {
      return;
    }
    final Player player = (Player)passenger;
    Humbug.info(String.format(
        "Player '%s' kicked for self damaging boat at %s",
        player.getName(), vehicle.getLocation().toString()));
    vehicle.eject();
    vehicle.getWorld().dropItem(vehicle.getLocation(), new ItemStack(Material.BOAT));
    vehicle.remove();
    ((Player)passenger).kickPlayer("Nope");
  }

  @BahHumbug(opt="prevent_land_boats", def="true")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPreventLandBoats(VehicleMoveEvent event) {
    if (!config_.get("prevent_land_boats").getBool()) {
      return;
    }
    final Vehicle vehicle = event.getVehicle();
    if (vehicle == null || !(vehicle instanceof Boat)) {
      return;
    }
    final Entity passenger = vehicle.getPassenger();
    if (passenger == null || !(passenger instanceof Player)) {
      return;
    }
    final Location to = event.getTo();
    final Material boatOn = to.getBlock().getRelative(BlockFace.DOWN).getType();
    if (boatOn.equals(Material.STATIONARY_WATER) || boatOn.equals(Material.WATER)) {
        return;
    }
    Humbug.info(String.format(
        "Player '%s' removed from land-boat at %s",
        ((Player)passenger).getName(), to.toString()));
    vehicle.eject();
    vehicle.getWorld().dropItem(vehicle.getLocation(), new ItemStack(Material.BOAT));
    vehicle.remove();
  }

  // ================================================
  // Fix minecarts

  public boolean checkForTeleportSpace(Location loc) {
    final Block block = loc.getBlock();
    final Material mat = block.getType();
    if (mat.isSolid()) {
      return false;
    }
    final Block above = block.getRelative(BlockFace.UP);
    if (above.getType().isSolid()) {
      return false;
    }
    return true;
  }

  public boolean tryToTeleport(Player player, Location location, String reason) {
    Location loc = location.clone();
    loc.setX(Math.floor(loc.getX()) + 0.500000D);
    loc.setY(Math.floor(loc.getY()) + 0.02D);
    loc.setZ(Math.floor(loc.getZ()) + 0.500000D);
    final Location baseLoc = loc.clone();
    final World world = baseLoc.getWorld();
    // Check if teleportation here is viable
    boolean performTeleport = checkForTeleportSpace(loc);
    if (!performTeleport) {
      loc.setY(loc.getY() + 1.000000D);
      performTeleport = checkForTeleportSpace(loc);
    }
    if (performTeleport) {
      player.setVelocity(new Vector());
      player.teleport(loc);
      Humbug.info(String.format(
          "Player '%s' %s: Teleported to %s",
          player.getName(), reason, loc.toString()));
      return true;
    }
    loc = baseLoc.clone();
    // Create a sliding window of block types and track how many of those
    //  are solid. Keep fetching the block below the current block to move down.
    int air_count = 0;
    LinkedList<Material> air_window = new LinkedList<Material>();
    loc.setY((float)world.getMaxHeight() - 2);
    Block block = world.getBlockAt(loc);
    for (int i = 0; i < 4; ++i) {
      Material block_mat = block.getType();
      if (!block_mat.isSolid()) {
        ++air_count;
      }
      air_window.addLast(block_mat);
      block = block.getRelative(BlockFace.DOWN);
    }
    // Now that the window is prepared, scan down the Y-axis.
    while (block.getY() >= 1) {
      Material block_mat = block.getType();
      if (block_mat.isSolid()) {
        if (air_count == 4) {
          player.setVelocity(new Vector());
          loc = block.getLocation();
          loc.setX(Math.floor(loc.getX()) + 0.500000D);
          loc.setY(loc.getY() + 1.02D);
          loc.setZ(Math.floor(loc.getZ()) + 0.500000D);
          player.teleport(loc);
          Humbug.info(String.format(
              "Player '%s' %s: Teleported to %s",
              player.getName(), reason, loc.toString()));
          return true;
        }
      } else { // !block_mat.isSolid()
        ++air_count;
      }
      air_window.addLast(block_mat);
      if (!air_window.removeFirst().isSolid()) {
        --air_count;
      }
      block = block.getRelative(BlockFace.DOWN);
    }
    return false;
  }

  @BahHumbug(opt="prevent_ender_pearl_save", def="true")
  @EventHandler
  public void enderPearlSave(EnderPearlUnloadEvent event) {
    if(!config_.get("prevent_ender_pearl_save").getBool())
      return;
    event.setCancelled(true);
  }

  @BahHumbug(opt="fix_vehicle_logout_bug", def="true")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled=true)
  public void onDisallowVehicleLogout(PlayerQuitEvent event) {
    if (!config_.get("fix_vehicle_logout_bug").getBool()) {
      return;
    }
    Player player = event.getPlayer();
    Entity vehicle = player.getVehicle();
    if (vehicle == null || !(vehicle instanceof Minecart)) {
      return;
    }
    Location vehicleLoc = vehicle.getLocation();
    // Vehicle data has been cached, now safe to kick the player out
    player.leaveVehicle();
    if (!tryToTeleport(player, vehicleLoc, "logged out")) {
      player.setHealth(0.000000D);
      Humbug.info(String.format(
          "Player '%s' logged out in vehicle: Killed", player.getName()));
    }
  }

  @BahHumbug(opt="fix_minecart_reenter_bug", def="true")
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onFixMinecartReenterBug(VehicleExitEvent event) {
    if (!config_.get("fix_minecart_reenter_bug").getBool()) {
      return;
    }
    final Vehicle vehicle = event.getVehicle();
    if (vehicle == null || !(vehicle instanceof Minecart)) {
      return;
    }
    final Entity passengerEntity = event.getExited();
    if (passengerEntity == null || !(passengerEntity instanceof Player)) {
      return;
    }
    // Must delay the teleport 2 ticks or else the player's mis-managed
    //  movement still occurs. With 1 tick it could still occur.
    final Player player = (Player)passengerEntity;
    final Location vehicleLoc = vehicle.getLocation();
    Bukkit.getScheduler().runTaskLater(this, new Runnable() {
      @Override
      public void run() {
        if (!tryToTeleport(player, vehicleLoc, "exiting vehicle")) {
          player.setHealth(0.000000D);
          Humbug.info(String.format(
              "Player '%s' exiting vehicle: Killed", player.getName()));
        }
      }
    }, 2L);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onFixMinecartReenterBug(VehicleDestroyEvent event) {
    if (!config_.get("fix_minecart_reenter_bug").getBool()) {
      return;
    }
    final Vehicle vehicle = event.getVehicle();
    if (vehicle == null || !(vehicle instanceof Minecart)) {
      return;
    }
    final Entity passengerEntity = vehicle.getPassenger();
    if (passengerEntity == null || !(passengerEntity instanceof Player)) {
      return;
    }
    // Must delay the teleport 2 ticks or else the player's mis-managed
    //  movement still occurs. With 1 tick it could still occur.
    final Player player = (Player)passengerEntity;
    final Location vehicleLoc = vehicle.getLocation();
    Bukkit.getScheduler().runTaskLater(this, new Runnable() {
      @Override
      public void run() {
        if (!tryToTeleport(player, vehicleLoc, "in destroyed vehicle")) {
          player.setHealth(0.000000D);
          Humbug.info(String.format(
              "Player '%s' in destroyed vehicle: Killed", player.getName()));
        }
      }
    }, 2L);
  }

  // ================================================
  // Adjust ender pearl gravity

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @BahHumbug(opt="ender_pearl_gravity", type=OptType.Double, def="0.060000")
  public void hookEnderPearls() {
	  	  
    Item.REGISTRY.a(256 + 112, "enderPearl", new CustomNMSItemEnderPearl(config_));
    try {
      // They thought they could stop us by preventing us from registering an
      // item. We'll show them
      Field fieldStringToClass = EntityTypes.class.getDeclaredField("c");
      Field fieldClassToString = EntityTypes.class.getDeclaredField("d");
      fieldStringToClass.setAccessible(true);
      fieldClassToString.setAccessible(true);
      
      Field fieldClassToId = EntityTypes.class.getDeclaredField("f");
      Field fieldStringToId = EntityTypes.class.getDeclaredField("g");
      fieldClassToId.setAccessible(true);
      fieldStringToId.setAccessible(true);
      
      Map mapStringToClass = (Map)fieldStringToClass.get(null);
      Map mapClassToString = (Map)fieldClassToString.get(null);
      
      Map mapClassToId = (Map)fieldClassToId.get(null);
      Map mapStringToId = (Map)fieldStringToId.get(null);
      
      mapStringToClass.put("ThrownEnderpearl",CustomNMSEntityEnderPearl.class);
      mapStringToId.put("ThrownEnderpearl", Integer.valueOf(14));
      
      mapClassToString.put(CustomNMSEntityEnderPearl.class, "ThrownEnderpearl");
      mapClassToId.put(CustomNMSEntityEnderPearl.class, Integer.valueOf(14));
      
      fieldStringToClass.set(null, mapStringToClass);
      fieldClassToString.set(null, mapClassToString);
      
      fieldClassToId.set(null, mapClassToId);
      fieldStringToId.set(null, mapStringToId);
    } catch (Exception e) {
      Humbug.severe("Exception while overriding MC's ender pearl class");
      e.printStackTrace();
    }
  }

  // ================================================
  // Hunger Changes
  @BahHumbug(opt="hunger_slowdown", type=OptType.Double, def="0.0")
  @EventHandler
  public void onFoodLevelChange(FoodLevelChangeEvent event) {
	  
    // GIMMICKS
    // return if not in this game mode
	if (event.getEntity() instanceof Player) {
      if (!isPlayerInThisPvpMode((Player)event.getEntity())) {
        return;
      }
	}
	  
      final Player player = (Player) event.getEntity();
      final double mod = config_.get("hunger_slowdown").getDouble();
      final double saturation = Math.min(
          player.getSaturation() + mod,
          20.0D + (mod * 2.0D));
      player.setSaturation((float)saturation);
  }
//=================================================
  //Remove Book Copying
  @BahHumbug(opt="copy_book_enable", def= "false")
  public void removeBooks() {
    if (config_.get("copy_book_enable").getBool()) {
      return;
    }
    Iterator<Recipe> it = getServer().recipeIterator();
    while (it.hasNext()) {
      Recipe recipe = it.next();
      ItemStack resulting_item = recipe.getResult();
      if ( // !copy_book_enable_ &&
          isWrittenBook(resulting_item)) {
        it.remove();
        info("Copying Books disabled");
      }
    }
  }
  
  public boolean isWrittenBook(ItemStack item) {
	    if (item == null) {
	      return false;
	    }
	    Material material = item.getType();
	    return material.equals(Material.WRITTEN_BOOK);
	  }
  // ================================================
  // General

  public void onLoad()
  {
    loadConfiguration();
    hookEnderPearls();
    info("Loaded");
  }

  public void onEnable() {
    registerEvents();
    registerCommands();
    removeRecipies();
    removeBooks();
    global_instance_ = this;
    info("Enabled");
  }

  public boolean isInitiaized() {
    return global_instance_ != null;
  }

  public boolean toBool(String value) {
    if (value.equals("1") || value.equalsIgnoreCase("true")) {
      return true;
    }
    return false;
  }

  public int toInt(String value, int default_value) {
    try {
      return Integer.parseInt(value);
    } catch(Exception e) {
      return default_value;
    }
  }

  public double toDouble(String value, double default_value) {
    try {
      return Double.parseDouble(value);
    } catch(Exception e) {
      return default_value;
    }
  }

  @SuppressWarnings("deprecation")
public int toMaterialId(String value, int default_value) {
    try {
      return Integer.parseInt(value);
    } catch(Exception e) {
      Material mat = Material.matchMaterial(value);
      if (mat != null) {
        return mat.getId();
      }
    }
    return default_value;
  }

  public boolean onCommand(
      CommandSender sender,
      Command command,
      String label,
      String[] args) {
    if (sender instanceof Player && command.getName().equals("invsee")) {
      if (args.length < 1) {
        sender.sendMessage("Provide a name");
        return false;
      }
      onInvseeCommand((Player)sender, args[0]);
      return true;
    }
    if (!(sender instanceof ConsoleCommandSender) ||
        !command.getName().equals("humbug") ||
        args.length < 1) {
      return false;
    }
    String option = args[0];
    String value = null;
    String subvalue = null;
    boolean set = false;
    boolean subvalue_set = false;
    String msg = "";
    if (args.length > 1) {
      value = args[1];
      set = true;
    }
    if (args.length > 2) {
      subvalue = args[2];
      subvalue_set = true;
    }
    ConfigOption opt = config_.get(option);
    if (opt != null) {
      if (set) {
        opt.set(value);
      }
      msg = String.format("%s = %s", option, opt.getString());
    } else if (option.equals("debug")) {
      if (set) {
        config_.setDebug(toBool(value));
      }
      msg = String.format("debug = %s", config_.getDebug());
    } else if (option.equals("remove_mob_drops")) {
      if (set && subvalue_set) {
        if (value.equals("add")) {
          config_.addRemoveItemDrop(toMaterialId(subvalue, -1));
        } else if (value.equals("del")) {
          config_.removeRemoveItemDrop(toMaterialId(subvalue, -1));
        }
      }
      msg = String.format("remove_mob_drops = %s", config_.toDisplayRemoveItemDrops());
    } else if (option.equals("save")) {
      config_.save();
      msg = "Configuration saved";
    } else if (option.equals("reload")) {
      config_.reload();
      msg = "Configuration loaded";
    } else {
      msg = String.format("Unknown option %s", option);
    }
    sender.sendMessage(msg);
    return true;
  }

  public void registerCommands() {
    ConsoleCommandSender console = getServer().getConsoleSender();
    console.addAttachment(this, "humbug.console", true);
  }

  private void registerEvents() {
    getServer().getPluginManager().registerEvents(this, this);
  }

  private void loadConfiguration() {
    config_ = Config.initialize(this);
  }
}
