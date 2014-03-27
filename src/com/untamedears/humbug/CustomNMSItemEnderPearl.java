package com.untamedears.humbug;


import org.bukkit.entity.Player;

import net.minecraft.server.v1_7_R1.EntityHuman;
import net.minecraft.server.v1_7_R1.ItemEnderPearl;
import net.minecraft.server.v1_7_R1.ItemStack;
import net.minecraft.server.v1_7_R1.World;

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
    	if (entityhuman instanceof Player) {
          if (GimmickAPI.getPvpModeForPlayer((Player)entityhuman).equals(THIS_PVP_MODE)) {
            double gravity = cfg_.get("ender_pearl_gravity").getDouble();
            world.addEntity(new CustomNMSEntityEnderPearl(world, entityhuman, gravity));
          } else {
            double gravity = 0.03D;
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
