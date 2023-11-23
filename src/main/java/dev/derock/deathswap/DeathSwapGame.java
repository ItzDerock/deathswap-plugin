package dev.derock.deathswap;

import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.apache.commons.io.FileUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.lib.PaperLib;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

public class DeathSwapGame implements Listener {
    private static DeathSwapGame instance = null;
    static DeathSwapGame getInstance() {
        if (instance == null) {
            instance = new DeathSwapGame();
        }
        return instance;
    }

    public static final int PREGEN_SIZE = 20;
    public static final String WORLD_NAME = "deathswap";
    public static final String WORLD_NAME_NETHER = "deathswap_nether";
    public static final int SWAP_PROTECTION_TIME = 2; // seconds
    public static final int SWAP_PROTECTION_TIME_NETHER = 10; // seconds
    public final DeathSwap plugin = DeathSwap.getPlugin(DeathSwap.class);

    private boolean gameRunning = false;
    private final HashSet<Player> participants = new HashSet<>();
    private final ArrayList<String> alivePlayers = new ArrayList<>();

    private boolean isTeleporting = false;

    private BukkitTask task = null;
    private final MultiverseCore core = (MultiverseCore) Bukkit.getServer().getPluginManager().getPlugin("Multiverse-Core");;

    public DeathSwapGame() {
    }

    /**
     * Broadcasts a message onscreen to all players in the game
     */
    public void broadcastMessage(String title, String subtitle) {
        title = ChatColor.translateAlternateColorCodes('&', title);
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle);

        for (Player player : participants) {
            player.sendTitle(title, subtitle, 10, 70, 20);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1);
        }
    }

    public void broadcastTooltip(String message) {
        message = ChatColor.translateAlternateColorCodes('&', message);

        for (Player player : participants) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1);
        }
    }


    /**
     * Unloads the DeathSwap world
     */
    public void unloadWorld(World world) {
        if (world == null) return;

        if (!Bukkit.getServer().unloadWorld(world, false)) {
            Bukkit.getLogger().warning("Could not unload world " + WORLD_NAME);
            throw new RuntimeException("Could not unload world " + WORLD_NAME);
        }
    }

    /**
     * Deletes the DeathSwap world
     */
    private void deleteWorld(World world) {
        if (world == null) return;

        try {
            FileUtils.deleteDirectory(world.getWorldFolder());
        } catch (IOException e) {
            broadcastMessage("&4DeathSwap", "Failed to reset world.");
            Bukkit.getLogger().warning("Could not delete world folder for world " + WORLD_NAME);
            throw new RuntimeException(e);
        }
    }

    /**
     * Generates a new world for DeathSwap
     */
    private void generateWorld() {
        int seed = (int) (Math.random() * 1000000);

        assert core != null;
        MVWorldManager worldManager = core.getMVWorldManager();

        if (!worldManager.isMVWorld(WORLD_NAME)) {
            worldManager.addWorld(
                    WORLD_NAME,
                    World.Environment.NORMAL,
                    null,
                    WorldType.NORMAL,
                    true,
                    null
            );
        } else {
            worldManager.regenWorld(WORLD_NAME, true, true, Integer.toString(seed));
        }

        if (!worldManager.isMVWorld(WORLD_NAME_NETHER)) {
            worldManager.addWorld(
                    WORLD_NAME_NETHER,
                    World.Environment.NETHER,
                    null,
                    WorldType.NORMAL,
                    true,
                    null
            );
        } else {
            worldManager.regenWorld(WORLD_NAME_NETHER, true, true, Integer.toString(seed));
        }
    }

    /**
     * Loads the world
     */
    public void pregenerateWorld(World world) {
        // pregen
        Bukkit.getLogger().info("Pregenerating world " + WORLD_NAME);

        Location spawn = world.getSpawnLocation();
        int spawnX = spawn.getBlockX();
        int spawnZ = spawn.getBlockZ();

        // fill an array of completeablefutures with the chunks
        ArrayList<CompletableFuture<Chunk>> futures = new ArrayList<>();

        for (int x = spawnX - PREGEN_SIZE; x < spawnX + PREGEN_SIZE; x += 16) {
            for (int z = spawnZ - PREGEN_SIZE; z < spawnZ + PREGEN_SIZE; z += 16) {
                futures.add(PaperLib.getChunkAtAsync(world, x, z));
            }
        }

        // wait for all the chunks to load
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Bukkit.getLogger().info("Done with world preparation");
    }

    /**
     * Swaps players
     */
    private void swapPlayers() {
        if (!gameRunning)
            return;

        // count down from 10 seconds on the action bar
        for (int i = 10; i > 0; i--) {
            if (i > 5)
                broadcastTooltip("&4Swapping in " + i + " seconds");
            else
                broadcastMessage("&4DeathSwap", "&lSwapping in " + i + " seconds");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        isTeleporting = true;

        // swap players
        Player[] players = participants.stream().filter(player -> alivePlayers.contains(player.getName())).toArray(Player[]::new);
        Bukkit.getLogger().info("Swapping " + players.length + " players");

        // randomize the order
        for (int i = 0; i < players.length; i++) {
            int swapIndex = (int) (Math.random() * players.length);
            Player temp = players[i];
            players[i] = players[swapIndex];
            players[swapIndex] = temp;
        }

        // if there are players in different worlds, increase the swap protection time
        int swapProtectionTime;
        String worldName = players[0].getWorld().getName();
        swapProtectionTime = Arrays.stream(players).anyMatch(player -> !player.getWorld().getName().equals(worldName)) ? SWAP_PROTECTION_TIME_NETHER : SWAP_PROTECTION_TIME;

        // swap players
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int i = 0; i < players.length; i++) {
                Player player = players[i];
                Player swapPlayer = players[(i + 1) % players.length];

                Bukkit.getLogger().info("Teleporting " + player.getName() + " to " + swapPlayer.getName() + " at " + swapPlayer.getLocation());

                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * swapProtectionTime, 100));
                player.setAllowFlight(true);
                player.setFlying(true);
                PaperLib.teleportAsync(player, swapPlayer.getLocation());
                player.sendMessage(ChatColor.RED + "You have been swapped!");
            }
        });

        // set isTeleporting to false in 2 seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            isTeleporting = false;
            for (Player player : participants) {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.setInvulnerable(false);
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        }, 20 * swapProtectionTime);
    }

    private void startGame() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            Bukkit.getLogger().warning("Could not load world " + WORLD_NAME);
            throw new RuntimeException("Could not load world " + WORLD_NAME);
        }

        // teleport players to the new world
        for (Player player : participants) {
            player.teleport(world.getSpawnLocation());

            // give them blindness and slowness for 5 seconds
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 100));
        }

        // start the game
        broadcastMessage("&4DeathSwap", "&lThe game has started!&r Good luck!");

        // schedule the repeating task, every 5 minutes
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::swapPlayers, 0, 20 * 60 * 5);
    }

    public void initialize() {
        // ensure all players are in the default world
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }

        // stop task if it is running
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

        // reset players
        participants.clear();
        participants.addAll(Bukkit.getOnlinePlayers());
        alivePlayers.clear();
        alivePlayers.addAll(participants.stream().map(Player::getName).toList());

        broadcastMessage("&4DeathSwap", "&lThe game will start soon!&r Preparing the world...");
        gameRunning = true;

        // generate the world in separate thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            generateWorld();
            Bukkit.getScheduler().runTaskLater(plugin, this::startGame, 20 * 5);
        });
    }

    public boolean isRunning() {
        return gameRunning;
    }

    @EventHandler()
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!alivePlayers.contains(event.getEntity().getName()))
            return;

        Player player = event.getEntity();
        Location deathLocation = player.getLocation();

        // rebroadcast the death message
        String message = event.getDeathMessage();
        if (message != null) {
            plugin.getServer().broadcastMessage(message);
            event.setDeathMessage(null);
        }

        // broadcast plugin message
        plugin.getServer().broadcastMessage(
                ChatColor.translateAlternateColorCodes('&', "&4&lDEATH!&r " + player.getName() + " has died!")
        );

        // set the player to spectator mode
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.spigot().respawn();
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(deathLocation);
        }, 10L);


        // remove the player from the alive players list
        alivePlayers.remove(player.getName());

        // check if there is only one player left
        if (alivePlayers.size() == 1) {
            Player winner = Bukkit.getPlayer(alivePlayers.get(0));
            plugin.getServer().broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&2&lWINNER!&r " + winner.getName() + " has won the game!"));
            gameRunning = false;

            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
        }
    }

    @EventHandler()
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!alivePlayers.contains(event.getPlayer().getName()))
            return;

        if (isTeleporting) {
            event.setCancelled(true);
        }
    }
}
