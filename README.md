<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHSlots

*Emplacements d'affichage temporaires (pseudo et message) attribués aux joueurs, avec placeholders et modération.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Emplacements d'affichage temporaires (pseudo et message) attribués aux joueurs, avec placeholders et modération.

Utilisé sur : **Opale**.

Avec `SHNetwork`, les slots sont communs à tous les serveurs Opale (voir « Mode réseau »). Sans lui, le plugin se comporte comme sur un serveur seul : `slots_data.json`, `users.yml` et la section `blacklist` de `config.yml`.

## Dépendances

- Obligatoires : `PlaceholderAPI`
- Optionnelles : `SHLogs`, `SHNetwork`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shslots set <slot> <joueur> <temps>` | — | Attribue un slot à un joueur pour une durée (exemple : `1h30m`). | `shslots.admin.set` |
| `/shslots remove <joueur>` | — | Retire le slot d'un joueur. | `shslots.admin.remove` |
| `/shslots blacklist <add\|remove> <joueur>` | — | Ajoute un joueur à la blacklist des slots, ou l'en retire. | `shslots.admin.blacklist` |
| `/shslots blacklist list` | — | Liste les joueurs blacklistés avec la date de leur sanction. | `shslots.admin.blacklist` |
| `/shslots menu` | `/shslots admin` | Ouvre le menu d'administration (joueur seulement). | `shslots.admin.menu` |
| `/shslots list` | — | Liste les slots occupés avec leur joueur, leur temps restant et leur message. | `shslots.admin.list` |
| `/shslots import` | — | Avec SHNetwork : reprend les anciennes données locales dans la base du réseau. | `shslots.admin.import` |
| `/shslots reload` | — | Recharge `config.yml` et `messages.yml` ; si un fichier est illisible, l'ancienne configuration reste active. Ne touche ni aux slots, ni à `users.yml`, ni à la blacklist. La section `reseau` demande un redémarrage. | `shslots.admin.reload` |

Toutes les sous-commandes marchent depuis la console, sauf `menu`. Les boutons du menu d'administration testent aussi les permissions : libérer un slot demande `shslots.admin.remove`, la blacklist demande `shslots.admin.blacklist`.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shslots.admin` | Toutes les sous-commandes de `/shslots`. | `op` |
| `shslots.admin.set` `.remove` `.blacklist` `.menu` `.import` `.list` `.reload` | Une sous-commande. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHSlots/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

`config.yml` règle le nombre de slots, l'annonce (intervalle, son, commande du clic), la récupération des skins et le mode réseau ; `messages.yml` contient tous les textes (commande, menus d'administration, texte et survol de l'annonce, mode réseau). Les tables du mode réseau sont décrites dans <a href="sql/schema.sql">sql/schema.sql</a>.

Chaque message saisi par un joueur pour son slot est écrit dans le fichier `messages` du dossier `SHSlots` de SHLogs (`plugins/SHLogs/`). Sans SHLogs, les lignes passent dans la console. Le dernier message de chaque joueur, affiché dans le menu d'administration, reste dans `users.yml`.

## Mode réseau (SHNetwork)

Quand `SHNetwork` est installé et que `reseau.actif` vaut `true` (valeur par défaut), tous les serveurs partagent les mêmes slots. Sans SHNetwork, ou avec `reseau.actif: false`, rien ne change.

- **Données.** Les slots sont dans la table `slots_active`, les derniers messages et la blacklist dans `slots_users` (base `bo_net`). `slots_data.json`, `users.yml` et la section `blacklist` de `config.yml` ne sont plus ni lus ni écrits. Les utilisateurs sont gardés par pseudo en minuscules : les anciennes données ne portent que des pseudos en minuscules, or l'UUID hors ligne dépend de la casse exacte du pseudo, donc une clé UUID ne pourrait pas être reconstruite sans risque.
- **Copie en mémoire.** Chaque serveur garde en mémoire les slots, la blacklist et les derniers messages (placeholders, menus et vérifications ne touchent jamais la base). Après chaque écriture, le serveur publie `changed` sur le canal `reseau.canal` et tous les serveurs relisent la base.
- **Attribution.** `/shslots set` retrouve le joueur sur ce serveur (un début de pseudo est accepté), sinon parmi les joueurs connectés au réseau. La base tranche « slot occupé » et « le joueur a déjà un slot » (clé du slot et pseudo unique), donc deux administrateurs sur deux serveurs ne peuvent pas donner le même slot ni deux slots au même joueur. Une durée expirée libère le slot, comme avant.
- **Saisie du message.** Le message `await` du bus invite le joueur à écrire son message dans le chat, sur le serveur où il se trouve, et chaque serveur attend sa réponse pendant 2 minutes. La saisie est écrite en base seulement si le slot appartient toujours au joueur ; elle disparaît du chat comme avant.
- **Blacklist.** `/shslots blacklist` et le menu d'administration écrivent dans `slots_users`. Un pseudo n'a pas besoin d'être connecté. Dans le menu, la liste des joueurs en ligne ne montre que ceux du serveur de l'administrateur ; la commande, elle, accepte tout le réseau (complétion comprise).
- **Annonce.** Un seul serveur, le meneur (tâche `shslots` de SHNetwork), compte l'intervalle et choisit le slot suivant ; il publie `announce` et tous les serveurs envoient le texte et le son à leurs joueurs. Si le meneur tombe, un autre reprend et attend la fin de l'intervalle de la dernière annonce avant d'annoncer, sans repartir du premier slot. Les horloges des serveurs doivent être synchronisées (même machine ou NTP) : la fin d'un slot est une heure absolue en base, le temps d'un serveur arrêté compte donc.
- **Base injoignable.** Au démarrage, le plugin réessaie toutes les 10 secondes ; en attendant, les placeholders répondent `none`. Une commande ou une saisie qui ne peut pas être enregistrée répond « La base des slots ne répond pas ».

`/shslots import` reprend dans la base `slots_data.json` (slots non expirés, avec leur temps restant), `users.yml` et la blacklist de `config.yml`. À lancer une fois, sur un serveur, avant le premier slot en mode réseau ; une ligne déjà en base n'est pas modifiée, donc la commande peut être relancée.

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée `slots_active`, `slots_users` et l'utilisateur `shslots`). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHSlots/config.yml`, section `reseau` : `url`, `utilisateur` et `mot-de-passe`. La même base et le même `config.yml` sur tous les serveurs.
3. Poser le jar sur chaque serveur, avec SHNetwork. Le jar charge HikariCP et le pilote MariaDB par `libraries:` (le serveur ne les embarque pas) ; sans SHNetwork, ils ne sont pas utilisés.
4. `/shslots import` sur un serveur.

Au démarrage, la console indique `SHNetwork détecté : slots et blacklist en base partagée`. `/shnetwork info` donne le serveur meneur de `shslots`.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
