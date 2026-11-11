package org.Cry_Legende.sHSlots;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/**
 * Textes de messages.yml : sections messages et gui, annonce (announcement.message et announcement.hover) et textes
 * du mode réseau à la racine. Une clé absente du fichier prend le texte du jar.
 */
final class Messages {

    private final YamlConfiguration yaml;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
    }

    /**
     * Crée messages.yml s'il n'existe pas, et le complète quand il n'a pas encore les sections messages et gui (fichier
     * du mode réseau seul) : les textes de ces sections, et announcement.message et announcement.hover, sont repris de
     * config.yml quand il les contient, sinon ceux du jar servent.
     */
    static void prepare(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        boolean created = !file.exists();
        if (created) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!created && yaml.contains("gui")) {
            return;
        }
        if (migrate(plugin.getConfig(), yaml)) {
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Écriture de messages.yml impossible : " + e.getMessage());
            }
        }
    }

    /** Copie dans {@code target} les textes de l'ancien config.yml ; vrai si quelque chose a été repris. */
    static boolean migrate(ConfigurationSection legacy, YamlConfiguration target) {
        boolean changed = false;
        for (String section : new String[] {"messages", "gui"}) {
            ConfigurationSection source = legacy.getConfigurationSection(section);
            if (source == null) {
                continue;
            }
            for (String key : source.getKeys(true)) {
                if (!source.isConfigurationSection(key)) {
                    target.set(section + "." + key, source.get(key));
                    changed = true;
                }
            }
        }
        for (String key : new String[] {"announcement.message", "announcement.hover"}) {
            if (legacy.contains(key, true)) {
                target.set(key, legacy.get(key));
                changed = true;
            }
        }
        return changed;
    }

    /** Lit messages.yml ; lève l'erreur YAML telle quelle pour que l'appelant garde l'ancien état. */
    static Messages load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "messages.yml"));
        yaml.setDefaults(jarDefaults(plugin));
        return new Messages(yaml);
    }

    /** Textes du jar seuls, quand messages.yml est illisible au démarrage. */
    static Messages defaults(JavaPlugin plugin) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setDefaults(jarDefaults(plugin));
        return new Messages(yaml);
    }

    private static YamlConfiguration jarDefaults(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("messages.yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /** Texte du chemin, ou null s'il n'existe ni dans le fichier ni dans le jar. */
    String string(String path) {
        return yaml.getString(path);
    }

    String string(String path, String def) {
        return yaml.getString(path, def);
    }

    List<String> list(String path) {
        return yaml.getStringList(path);
    }
}
