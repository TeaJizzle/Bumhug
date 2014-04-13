package com.untamedears.humbug;


import org.bukkit.Bukkit;

import net.minecraft.server.v1_7_R2.EntityHuman;
import net.minecraft.server.v1_7_R2.ItemEnderPearl;
import net.minecraft.server.v1_7_R2.ItemStack;
import net.minecraft.server.v1_7_R2.World;

import com.untamedears.humbug.Config;
import com.untamedears.humbug.CustomNMSEntityEnderPearl;
import com.gimmicknetwork.gimmickapi.GimmickAPI;

public class CustomNMSItemEnderPearl extends ItemEnderPearl {
  private Config cfg_;
  private final String THIS_PVP_MODE = "civcraft";
  
  public CustomNMSItemEnderPearl(Config cfg) {
    super();
    cfg_ = cfg;
  }

  public ItemStack a(
      ItemStack itemstack,
      World world,
      EntityHuman entityhuman) {
    if (entityhuman.abilities.canInstantlyBuild) {
      return itemstack;
    } else if (entityhuman.vehicle != null) {
      return itemstack;
    } else {
      --itemstack.count;
      world.makeSound(
          entityhuman,
          "random.bow",
          0.5F,
          0.4F / (g.nextFloat() * 0.4F + 0.8F));
      if (!world.isStatic) {
    	if (Bukkit.getPlayer(entityhuman.getName())!=null && Bukkit.getPluginManager().isPluginEnabled("GimmickAPI")) { //make sure this is a player and GimmickAPI is loaded
          if (GimmickAPI.getPvpModeForPlayer(Bukkit.getPlayer(entityhuman.getName())).equals(THIS_PVP_MODE)) { //check player's pvp mode
            double gravity = cfg_.get("ender_pearl_gravity").getDouble();
            world.addEntity(new CustomNMSEntityEnderPearl(world, entityhuman, gravity));
          } else {
            double gravity = 0.03D; //default gravity
            world.addEntity(new CustomNMSEntityEnderPearl(world, entityhuman, gravity));
          }
        } else {
          double gravity = cfg_.get("ender_pearl_gravity").getDouble();
          world.addEntity(new CustomNMSEntityEnderPearl(world, entityhuman, gravity));
        }
      }
      return itemstack;
    }
  }
}
