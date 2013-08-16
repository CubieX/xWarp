package de.xzise.xwarp.timer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import de.xzise.MinecraftUtil;
import de.xzise.xwarp.PluginProperties;
import de.xzise.xwarp.Warp;
import de.xzise.xwarp.XWarp;
import de.xzise.xwarp.warpable.Warpable;
import de.xzise.xwarp.warpable.WarpablePlayer;
import de.xzise.xwarp.wrappers.permission.Groups;

public class WarmUp
{
   private Map<CommandSender, Integer> players = new HashMap<CommandSender, Integer>();
   private final Plugin plugin;
   private final PluginProperties properties;
   private final CoolDown down;

   private final int MAX_LEASH_DISTANCE = 12; // real max distance are 10 blocks, but to be sure...
   private ArrayList<Integer> leashedMobs = new ArrayList<Integer>();

   public WarmUp(Plugin plugin, PluginProperties properties, CoolDown down)
   {
      this.plugin = plugin;
      this.properties = properties;
      this.down = down;
   }

   public void addPlayer(CommandSender warper, Warpable warped, Warp warp)
   {
      int warmup = getWarmupTime(warp, warper);
      if (warmup > 0) {
         if (this.properties.isWarmupNotify()) {
            warper.sendMessage(ChatColor.AQUA + "You will have to warm up for " + warmup + " secs");
         }
         int taskIndex = this.plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new WarmTask(warper, warped, warp, this), warmup * 20);
         this.players.put(warper, taskIndex);
      } else {
         this.sendPlayer(warper, warped, warp);
      }
   }

   public boolean cancelWarmUp(CommandSender warper)
   {
      // TODO: Only remove, if warp itself?
      if (this.players.containsKey(warper)) {
         this.plugin.getServer().getScheduler().cancelTask(this.players.get(warper));
         this.players.remove(warper);
         return true;
      } else {
         return false;
      }
   }

   public static int getWarmupTime(Warp warp, CommandSender warper) {
      int time = warp.getWarmUp();
      if (time < 0) {
         return XWarp.permissions.getInteger(warper, Groups.TIMERS_WARMUP_GROUP.get(warp.getVisibility()));
      } else {
         return time;
      }
   }

   public boolean playerHasWarmed(CommandSender warper) {
      return this.players.containsKey(warper);
   }

   private void sendPlayer(CommandSender warper, Warpable warped, Warp warp)
   {
      boolean playerIsOnTamedSaddledMount = false;
      Player player = null;
      Horse mount = null;

      if(warped instanceof WarpablePlayer)
      {
         player = Bukkit.getServer().getPlayer(warped.getName());

         if(null != player &&
               player.isOnline() &&
               player.isInsideVehicle() &&
               (player.getVehicle().getType() == EntityType.HORSE)) // player sits on a mount (like a horse)
         {
            mount = (Horse) player.getVehicle();

            if(mount.isTamed() &&
                  (null != mount.getInventory().getSaddle())) // player may only warp with a tamed mount with a saddle
            {
               playerIsOnTamedSaddledMount = true;               
            }
            else
            {
               player.sendMessage("Du kannst nur auf einem gezaehmten und besatteltem Reittier warpen!");
            }
         }
      }

      if((null != player) &&
            playerIsOnTamedSaddledMount) // handle warping of mounted player with his mount
      {
         if(leashedMobsPresent(player))
         {
            // block warping because it's not allowed when sitting on a horse while holding leashed mobs
            player.sendMessage(ChatColor.GOLD + "Du kannst nicht zusammen mit angeleinten Tieren warpen,\nwenn du auf einem Pferd sitzt!");
         }
         else
         {
            // unmount player
            boolean resUnmount = player.leaveVehicle();
            // teleport horse and re-mount player (teleporting him in the proccess)           
            boolean resTele = mount.teleport(warp.getLocation().toLocation());
            boolean resSetPassenger = mount.setPassenger(player); // will teleport the player to the horses back

            if(resUnmount && resTele && resSetPassenger)
            {
               sendWelcomeMessage(warper, warped, warp);
               if(XWarp.DEBUG){warper.sendMessage("DEBUG: Warped mounted player and his mount.");}
            }
            else
            {
               warper.sendMessage(ChatColor.RED + "Unable to warp.");
               if(XWarp.DEBUG){warper.sendMessage("DEBUG: Unable to warp mounted player and his mount.");}
            }
         }
      }
      else // handle player warp only (and leashed mobs, if present)
      {
         if(null != player)
         {
            LivingEntity livEnt = null;
            leashedMobs.clear();

            // check if there are leashed mobs present around the player
            for(Entity ent : player.getNearbyEntities(2*MAX_LEASH_DISTANCE, 2*MAX_LEASH_DISTANCE, 2*MAX_LEASH_DISTANCE))
            {
               if(ent instanceof LivingEntity)
               {
                  livEnt = (LivingEntity)ent;                           

                  if(livEnt.isLeashed())
                  {                              
                     if(livEnt.getLeashHolder().equals(player)) // issuing player has this mob leashed
                     {                                 
                        leashedMobs.add(livEnt.getEntityId());  
                     }
                  }
               }
            }

            if(!leashedMobs.isEmpty())
            { // there are leashed mobs present
               boolean allowTeleport = true;

               /*if(null != wgInst) // check all leashed mobs with WorldGuard if they may be teleported by this player
               {
                  for(Entity wEnt : player.getWorld().getEntities())
                  {
                     if(leashedMobs.contains(wEnt.getEntityId()))
                     {
                        if(!wgInst.canBuild(player, wEnt.getLocation())) // only mobs within areas where the issuing player can build may be teleported 
                        {
                           allowTeleport = false;
                           break; // teleport all or nothing!
                        }
                     }
                  }
               }*/

               if(player.isOp() ||
                     player.hasPermission("xwarp.warp.admin.to.all") ||
                     allowTeleport)
               {
                  if (warped.teleport(warp.getLocation().toLocation(), TeleportCause.COMMAND))
                  {
                     // if player was warped successfully, warp all leashed mobs after him
                     for(Entity wEnt : player.getWorld().getEntities())
                     {
                        if(leashedMobs.contains(wEnt.getEntityId()))
                        {
                           wEnt.teleport(warp.getLocation().toLocation());
                        }
                     }

                     sendWelcomeMessage(warper, warped, warp);
                     if(XWarp.DEBUG){player.sendMessage(ChatColor.AQUA + "Du wurdest zusammen mit " + ChatColor.WHITE + leashedMobs.size() + ChatColor.AQUA + " Tieren gewarped!");}
                  }
                  else
                  {
                     warper.sendMessage(ChatColor.RED + "Unable to warp.");
                  }
               }
               else
               {
                  player.sendMessage(ChatColor.GOLD + "Du kannst keine Tiere aus geschuetzten Bereichen herauswarpen!");
               }
            }
            else
            { // no leashed mobs so warp player only
               if (warped.teleport(warp.getLocation().toLocation(), TeleportCause.COMMAND))
               {
                  sendWelcomeMessage(warper, warped, warp);
               }
               else
               {
                  warper.sendMessage(ChatColor.RED + "Unable to warp.");
               }
            }
         }
      }
   }

   private boolean leashedMobsPresent(Player player)
   {
      // check if there are leashed mobs present around the player (means: he holds a leash with mob attached)
      boolean res = false;
      LivingEntity livEnt = null;

      for(Entity ent : player.getNearbyEntities(2*MAX_LEASH_DISTANCE, 2*MAX_LEASH_DISTANCE, 2*MAX_LEASH_DISTANCE))
      {
         if(ent instanceof LivingEntity)
         {
            livEnt = (LivingEntity)ent;                           

            if(livEnt.isLeashed())
            {
               if(livEnt.getLeashHolder().equals(player)) // issuing player has at least one mob leashed
               {                                 
                  res = true;
                  break;
               }
            }
         }
      }

      return res;
   }

   private void sendWelcomeMessage(CommandSender warper, Warpable warped, Warp warp)
   {
      String rawMsg = warp.getRawWelcomeMessage();
      if (rawMsg == null)
      {
         rawMsg = this.properties.getDefaultMessage().replace("{NAME}", warp.getName());
      }

      if (MinecraftUtil.isSet(rawMsg))
      {
         warped.sendMessage(ChatColor.AQUA + rawMsg);
      }
      this.down.addPlayer(warp, warper);
      this.players.remove(warper);

      if (!warped.equals(warper))
      {
         warper.sendMessage("Sucessfully warped '" + ChatColor.GREEN + MinecraftUtil.getName(warped) + ChatColor.WHITE + "'");
      }
   }

   private static class WarmTask implements Runnable {
      private CommandSender player;
      private Warpable warped;
      private Warp warp;
      private WarmUp warmUp;

      public WarmTask(CommandSender player, Warpable warped, Warp warp, WarmUp warmUp) {
         this.player = player;
         this.warped = warped;
         this.warp = warp;
         this.warmUp = warmUp;
      }

      public void run() {
         this.warmUp.sendPlayer(player, warped, warp);
      }
   }

}
