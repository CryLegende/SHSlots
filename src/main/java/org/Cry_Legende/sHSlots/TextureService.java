package org.Cry_Legende.sHSlots;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class TextureService {

    private final SHSlots plugin;
    private final File texturesFile;
    private FileConfiguration texturesConfig;
    private final ConcurrentHashMap<UUID, String> premiumTextureCache = new ConcurrentHashMap<>();

    private final File uuidMappingsFile;
    private FileConfiguration uuidMappingsConfig;
    private final ConcurrentHashMap<UUID, Optional<UUID>> offlineToPremiumCache = new ConcurrentHashMap<>();

    private final String NO_SKIN_MARKER = "NO_SKIN_DATA";
    private final boolean serverOfflineMode;
    private final int connectTimeout;
    private final int readTimeout;

    public TextureService(SHSlots plugin) {
        this.plugin = plugin;
        File cacheDir = new File(plugin.getDataFolder(), "cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        this.texturesFile = new File(cacheDir, "premium_textures.yml");
        this.uuidMappingsFile = new File(cacheDir, "offline_uuid_to_premium.yml");

        // Détection automatique, mais on force le comportement "Offline" pour les têtes si besoin
        this.serverOfflineMode = !Bukkit.getServer().getOnlineMode();

        this.connectTimeout = plugin.getConfig().getInt("texture-service.fetch-timeout.connect", 5000);
        this.readTimeout = plugin.getConfig().getInt("texture-service.fetch-timeout.read", 7000);

        loadCaches();
    }

    public void loadCaches() {
        premiumTextureCache.clear();
        if (!texturesFile.exists()) {
            try {
                texturesFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        texturesConfig = YamlConfiguration.loadConfiguration(texturesFile);
        for (String key : texturesConfig.getKeys(false)) {
            try {
                premiumTextureCache.put(UUID.fromString(key), texturesConfig.getString(key));
            } catch (Exception ignored) {
            }
        }

        // On charge toujours le mapping offline -> premium, utile même en online mode pour les têtes stockées
        offlineToPremiumCache.clear();
        if (!uuidMappingsFile.exists()) {
            try {
                uuidMappingsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        uuidMappingsConfig = YamlConfiguration.loadConfiguration(uuidMappingsFile);
        for (String key : uuidMappingsConfig.getKeys(false)) {
            try {
                String val = uuidMappingsConfig.getString(key);
                if (NO_SKIN_MARKER.equals(val)) offlineToPremiumCache.put(UUID.fromString(key), Optional.empty());
                else offlineToPremiumCache.put(UUID.fromString(key), Optional.of(UUID.fromString(val)));
            } catch (Exception ignored) {
            }
        }
    }

    public void saveCaches() {
        if (texturesConfig != null) {
            premiumTextureCache.forEach((k, v) -> texturesConfig.set(k.toString(), v));
            try {
                texturesConfig.save(texturesFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (uuidMappingsConfig != null) {
            offlineToPremiumCache.forEach((k, v) -> uuidMappingsConfig.set(k.toString(), v.map(UUID::toString).orElse(NO_SKIN_MARKER)));
            try {
                uuidMappingsConfig.save(uuidMappingsFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void getPlayerHead(OfflinePlayer targetPlayer, Consumer<ItemStack> callback) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta == null) {
            callback.accept(head);
            return;
        }

        UUID primaryUUID = targetPlayer.getUniqueId();
        String playerName = targetPlayer.getName();

        // CORRECTION MAJEURE : On ne fait plus confiance à targetPlayer.isOnline() pour les skins
        // car sur un serveur crack/bungee, l'UUID est faux et le skin ne charge pas.
        // On passe TOUJOURS par le système de résolution de texture.

        Runnable fetchTask = () -> {
            // Logique de récupération intelligente
            if (offlineToPremiumCache.containsKey(primaryUUID)) {
                // Cas 1 : On connaît déjà ce joueur (cache)
                Optional<UUID> cached = offlineToPremiumCache.get(primaryUUID);
                if (cached.isPresent()) {
                    processTextureFetch(cached.get(), meta, head, targetPlayer, callback);
                } else {
                    fallback(meta, head, targetPlayer, callback);
                }
            } else {
                // Cas 2 : Joueur inconnu, on demande à Mojang son vrai UUID via son Pseudo
                if (playerName == null) {
                    fallback(meta, head, targetPlayer, callback);
                    return;
                }

                getPremiumUUIDFromName(playerName).thenAccept(optUUID -> {
                    if (optUUID.isPresent()) {
                        // On a trouvé le vrai UUID Mojang
                        offlineToPremiumCache.put(primaryUUID, optUUID);
                        processTextureFetch(optUUID.get(), meta, head, targetPlayer, callback);
                    } else {
                        // Ce joueur n'existe pas chez Mojang (Crack pur sans équivalent premium)
                        offlineToPremiumCache.put(primaryUUID, Optional.empty());
                        fallback(meta, head, targetPlayer, callback);
                    }
                });
            }
        };

        Bukkit.getScheduler().runTaskAsynchronously(plugin, fetchTask);
    }

    private void processTextureFetch(UUID premiumUUID, SkullMeta meta, ItemStack head, OfflinePlayer target, Consumer<ItemStack> callback) {
        String cachedTexture = premiumTextureCache.get(premiumUUID);

        if (cachedTexture != null) {
            // La texture est en cache (Base64)
            if (!NO_SKIN_MARKER.equals(cachedTexture)) {
                applyTexture(meta, cachedTexture, premiumUUID);
            } else {
                meta.setOwningPlayer(target);
            }
            complete(head, meta, callback);
            return;
        }

        // La texture n'est pas en cache, on la télécharge
        fetchTextureFromMojang(premiumUUID).thenAccept(result -> {
            if (result.success && result.texture != null) {
                premiumTextureCache.put(premiumUUID, result.texture);
                applyTexture(meta, result.texture, premiumUUID);
            } else {
                premiumTextureCache.put(premiumUUID, NO_SKIN_MARKER);
                meta.setOwningPlayer(target);
            }
            complete(head, meta, callback);
        });
    }

    private void fallback(SkullMeta meta, ItemStack head, OfflinePlayer target, Consumer<ItemStack> callback) {
        // Méthode de secours classique
        meta.setOwningPlayer(target);
        complete(head, meta, callback);
    }

    private void complete(ItemStack head, SkullMeta meta, Consumer<ItemStack> callback) {
        // Retour sur le thread principal pour éviter les erreurs Bukkit API
        Bukkit.getScheduler().runTask(plugin, () -> {
            head.setItemMeta(meta);
            callback.accept(head);
        });
    }

    private void applyTexture(SkullMeta meta, String base64Texture, UUID uuid) {
        if (base64Texture == null || base64Texture.isEmpty()) return;
        try {
            // Utilisation de l'API moderne PlayerProfile pour éviter les WARN console
            PlayerProfile profile = Bukkit.createPlayerProfile(uuid);
            PlayerTextures textures = profile.getTextures();

            // Décodage Base64 pour obtenir l'URL (nécessaire pour l'API Spigot)
            String decoded = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
            JsonObject json = new JsonParser().parse(decoded).getAsJsonObject();
            String urlStr = json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();

            textures.setSkin(new URL(urlStr));
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception ignored) {
            // Si ça échoue, tant pis, pas de texture
        }
    }

    private CompletableFuture<Optional<UUID>> getPremiumUUIDFromName(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + name).openConnection();
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(readTimeout);
                if (conn.getResponseCode() == 200) {
                    JsonObject json = new JsonParser().parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                    String id = json.get("id").getAsString();
                    // Ajout des tirets à l'UUID
                    return Optional.of(UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")));
                }
            } catch (Exception ignored) {
            }
            return Optional.empty();
        });
    }

    private CompletableFuture<TextureFetchResult> fetchTextureFromMojang(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false").openConnection();
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(readTimeout);
                if (conn.getResponseCode() == 200) {
                    JsonObject json = new JsonParser().parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                    if (json.has("properties")) {
                        String texture = json.getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
                        return new TextureFetchResult(texture, true);
                    }
                }
            } catch (Exception ignored) {
            }
            return new TextureFetchResult(null, false);
        });
    }

    private static class TextureFetchResult {
        String texture;
        boolean success;

        public TextureFetchResult(String t, boolean s) {
            texture = t;
            success = s;
        }
    }
}
