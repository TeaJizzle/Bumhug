package com.untamedears.humbug;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import com.untamedears.humbug.Humbug;
import com.untamedears.humbug.annotations.BahHumbug;
import com.untamedears.humbug.annotations.BahHumbugs;
import com.untamedears.humbug.annotations.ConfigOption;

public class Config {
  private static Config global_instance_ = null;

  // ================================================
  // Configuration defaults
  private static final boolean debug_log_ = false;
  private static final int projectile_slow_ticks_ = 100;
 
  private static FileConfiguration config_ = null;

  public static Config initialize(Humbug plugin) {
    if (global_instance_ == null) {
      plugin.reloadConfig();
      config_ = plugin.getConfig();
      config_.options().copyDefaults(true);
      global_instance_ = new Config(plugin);
      global_instance_.load();
    }
    return global_instance_;
  }

  public static ConfigurationSection getStorage() {
    return config_;
  }

  private Humbug plugin_ = null;
  private Set<Integer> remove_item_drops_ = null;

  public Config(Humbug plugin) {
    plugin_ = plugin;
    scanAnnotations();
  }

  private Map<String, ConfigOption> dynamicOptions_ = new TreeMap<String, ConfigOption>();

  private void addToConfig(BahHumbug bug) {
    if (dynamicOptions_.containsKey(bug.opt())) {
      Humbug.info("Duplicate configuration option detected: " + bug.opt());
      return;
    }
    dynamicOptions_.put(bug.opt(), new ConfigOption(bug));
  }

  private void scanAnnotations() {
    try {
      for (Method method : Humbug.class.getMethods()) {
        BahHumbug bug = method.getAnnotation(BahHumbug.class);
        if (bug != null) {
          addToConfig(bug);
          continue;
        }
        BahHumbugs bugs = method.getAnnotation(BahHumbugs.class);
        if (bugs != null) {
          for (BahHumbug drone : bugs.value()) {
            addToConfig(drone);
          }
          continue;
        }
      }
    } catch(Exception ex) {
      Humbug.info(ex.toString());
    }
  }

  public void load() {
    // Setting specific initialization
    loadRemoveItemDrops();
  }

  public void reload() {
    plugin_.reloadConfig();
  }

  public void save() {
    plugin_.saveConfig();
  }

  public ConfigOption get(String optionName) {
    return dynamicOptions_.get(optionName);
  }

  public boolean set(String optionName, String value) {
    ConfigOption opt = dynamicOptions_.get(optionName);
    if (opt != null) {
      opt.setString(value);
      return true;
    }
    return false;
  }

  public boolean getDebug() {
    return config_.getBoolean("debug", debug_log_);
  }

  public void setDebug(boolean value) {
    config_.set("debug", value);
  }


  

  public void setLootMultiplier(String entity_type, int value){
    config_.set("loot_multiplier." + entity_type.toLowerCase(), value);
  }



  public void setQuartzGravelPercentage(int value) {
    if (value < 0) {
      value = 0;
      Humbug.warning("quartz_gravel_percentage adjusted to 0");
    } else if (value > 100) {
      value = 100;
      Humbug.warning("quartz_gravel_percentage adjusted to 100");
    }
    config_.set("quartz_gravel_percentage", value);
  }


  
  public void setCobbleFromLavaScanRadius(int value) {
    if (value < 0) {
      value = 0;
      Humbug.warning("cobble_from_lava_scan_radius adjusted to 0");
    } else if (value > 20) {  // 8000 blocks to scan at 20
      value = 20;
      Humbug.warning("cobble_from_lava_scan_radius adjusted to 20");
    }
    config_.set("cobble_from_lava_scan_radius", value);
  }


  public int getProjectileSlowTicks() {
    int ticks = config_.getInt("projectile_slow_ticks", projectile_slow_ticks_);
    if (ticks <= 0 || ticks > 600) {
      ticks = 100;
    }
    return ticks;
  }
  

  private void loadRemoveItemDrops() {
    if (!config_.isSet("remove_mob_drops")) {
      remove_item_drops_ = new HashSet<Integer>(4);
      return;
    }
    remove_item_drops_ = new HashSet<Integer>();
    if (!config_.isList("remove_mob_drops")) {
      Integer val = config_.getInt("remove_mob_drops");
      if (val == null) {
        config_.set("remove_mob_drops", new LinkedList<Integer>());
        Humbug.info("remove_mob_drops was invalid, reset");
        return;
      }
      remove_item_drops_.add(val);
      List<Integer> list = new LinkedList<Integer>();
      list.add(val);
      config_.set("remove_mob_drops", val);
      Humbug.info("remove_mob_drops was not an Integer list, converted");
      return;
    }
    remove_item_drops_.addAll(config_.getIntegerList("remove_mob_drops"));
  }

  public boolean doRemoveItemDrops() {
    return !remove_item_drops_.isEmpty();
  }

  public Set<Integer> getRemoveItemDrops() {
    return Collections.unmodifiableSet(remove_item_drops_);
  }

  public void addRemoveItemDrop(int item_id) {
    if (item_id < 0) {
      return;
    }
    remove_item_drops_.add(item_id);
    List<Integer> list;
    if (!config_.isSet("remove_mob_drops")) {
      list = new LinkedList<Integer>();
    } else {
      list = config_.getIntegerList("remove_mob_drops");
    }
    list.add(item_id);
    config_.set("remove_mob_drops", list);
  }

  public void removeRemoveItemDrop(int item_id) {
    if (item_id < 0) {
      return;
    }
    if (!remove_item_drops_.remove(item_id)) {
      return;
    }
    List<Integer> list = config_.getIntegerList("remove_mob_drops");
    list.remove((Object)item_id);
    config_.set("remove_mob_drops", list);
  }

  public void setRemoveItemDrops(Set<Integer> item_ids) {
    remove_item_drops_ = new HashSet<Integer>();
    remove_item_drops_.addAll(item_ids);
    List<Integer> list = new LinkedList<Integer>();
    list.addAll(item_ids);
    config_.set("remove_mob_drops", list);
  }

  @SuppressWarnings("deprecation")
public String toDisplayRemoveItemDrops() {
    StringBuilder sb = new StringBuilder();
    for (Integer item_id : remove_item_drops_) {
      Material mat = Material.getMaterial(item_id);
      if (mat == null) {
        sb.append(item_id);
      } else {
        sb.append(mat.toString());
      }
      sb.append(",");
    }
    return sb.toString();
  }
  
  public void tag_on_join(boolean value){
	  config_.set("tag_on_join", value);
  }
}
