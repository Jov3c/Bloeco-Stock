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
    public Component registrationFailed() { return message("registration-failed", "Registration failed; administrator recovery may be required."); }
    public Component registrationSuccess() { return message("registration-success", "Company registration completed."); }
    public Component insufficientFunds() { return message("insufficient-funds", "Insufficient funds."); }
    public Component duplicateName() { return message("duplicate-name", "A company with that name already exists."); }
    public Component refunded() { return message("refunded", "Registration failed and your payment was refunded."); }
    public Component recoveryRequired() { return message("recovery-required", "Registration requires administrator recovery."); }
    public Component lookupFailed() { return message("lookup-failed", "Company lookup failed."); }
    public Component companyNotFound() { return message("company-not-found", "Company not found."); }
    public Component companyInfo(String name, Object state) { return message("company-info", "%name% — %state%").replaceText(b -> b.matchLiteral("%name%").replacement(name)).replaceText(b -> b.matchLiteral("%state%").replacement(String.valueOf(state))); }
    public Component recoveryLookupFailed() { return message("recovery-lookup-failed", "Recovery lookup failed."); }
    public Component noRecoveryRecords() { return message("no-recovery-records", "No recovery records."); }
    public Component recoveryRecord(Object id,Object player,Object amount,Object state,Object time,Object error) { return message("recovery-record", "%id% player=%player% amount=%amount% state=%state% time=%time% error=%error%").replaceText(b->b.matchLiteral("%id%").replacement(String.valueOf(id))).replaceText(b->b.matchLiteral("%player%").replacement(String.valueOf(player))).replaceText(b->b.matchLiteral("%amount%").replacement(String.valueOf(amount))).replaceText(b->b.matchLiteral("%state%").replacement(String.valueOf(state))).replaceText(b->b.matchLiteral("%time%").replacement(String.valueOf(time))).replaceText(b->b.matchLiteral("%error%").replacement(String.valueOf(error))); }
    public Component result(String text) { return Component.text(text); }
}
