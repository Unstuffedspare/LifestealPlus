package dev.lifestealplus.admin;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.core.HeartService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Notes which address each account logs in from, as a salted hash: enough to tell two
 * accounts share one, never enough to recover the IP. The salt is made once per server
 * and kept in the database.
 */
public final class AltTracker implements Listener {

    private static final String SALT = "alts.salt";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final Logger log;
    private final String salt;

    public AltTracker(LifestealPlugin plugin, HeartService hearts) throws Exception {
        this.plugin = plugin;
        this.hearts = hearts;
        this.log = plugin.getLogger();
        // Startup only: waiting a moment here is fine, and logins need the salt from the first one.
        String saved = hearts.state(SALT).get(10, TimeUnit.SECONDS).orElse(null);
        if (saved == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            saved = HexFormat.of().formatHex(bytes);
            hearts.putState(SALT, saved).get(10, TimeUnit.SECONDS);
        }
        this.salt = saved;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!plugin.settings().extras().altDetection()) return;
        hearts.recordLogin(event.getUniqueId(), hash(event.getAddress().getHostAddress())).exceptionally(error -> {
            log.log(Level.WARNING, "Couldn't record a login for " + event.getName(), error);
            return null;
        });
    }

    private String hash(String address) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + address).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
