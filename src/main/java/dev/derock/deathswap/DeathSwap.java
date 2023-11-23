package dev.derock.deathswap;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class DeathSwap extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        Objects.requireNonNull(this.getCommand("deathswap")).setExecutor(new DeathSwapCommand());
        getServer().getPluginManager().registerEvents(DeathSwapGame.getInstance(), this);
        this.getLogger().info("Derock's Amazing Deathswap Plugin has started");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
