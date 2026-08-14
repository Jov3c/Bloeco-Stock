package cn.blockeco.exchange.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;

/** Configuration-backed user messages; defaults keep the first deployment usable. */
public final class Messages {
    private final ConfigurationSection config;
    public Messages(ConfigurationSection config) { this.config = config; }
    private Component message(String key, String fallback) { return Component.text(config == null ? fallback : config.getString(key, fallback)); }
    public Component processing() { return message("processing", "Company registration is processing."); }
    public Component initializing() { return message("initializing", "Blockeco is still initializing; please try again shortly."); }
    public Component noPermission() { return message("no-permission", "You do not have permission."); }
    public Component playersOnly() { return message("players-only", "This command can only be used by a player."); }
    public Component usageCreate() { return message("usage-create", "Usage: /company create <name> <30|50|70>"); }
    public Component duplicateRequest() { return message("duplicate-request", "Your company registration is already processing."); }
    public Component result(String text) { return Component.text(text); }
}
