# Reader's Podcasts — chantier

Plan arrêté le 2026-09-18. Décisions prises avec l'utilisateur :
base = **extension de `readers-audio`** (pas de fork), YouTube = **yt-dlp embarqué**
(youtubedl-android) dans la seule variante privée, **Android et desktop menés en parallèle**.

## 1. Pourquoi pas un fork

Podcast Addict est fermé. Côté libre :

- **AntennaPod** — le plus complet et le mieux maintenu, mais Java + vues XML : tout le UI
  serait à réécrire en Compose pour le look Reader's.
- **Podcini / Podcini.X / Podcini.A** (fork d'AntennaPod par XilinJia) — Kotlin pur, Compose,
  media3 : la bonne base sur le papier. Mais l'auteur a arrêté la variante YouTube en
  janvier 2025 pour raisons juridiques (d'où Podcini.X, sans YouTube), la persistance
  repose sur **Realm**, dont le SDK Kotlin a été abandonné par MongoDB, et l'app est très
  riche (files multiples, notes, notation 5 niveaux, 12 états de lecture) : la mettre au
  dépouillement Reader's reviendrait à en supprimer 80 % puis à maintenir le fork.

`readers-audio` fait déjà 2 193 lignes et contient le lecteur Media3, le service de lecture,
le widget, le thème et le kit Reader's, la transcription whisper.cpp et le résumé llama.cpp.
Il manque le gestionnaire d'abonnements. On l'écrit, en lisant Podcini.X et AntennaPod
comme **référence** pour les parties pénibles (bizarreries des flux, reprise de
téléchargement, timer d'endormissement).

## 2. Ce qu'on réutilise tel quel

| Brique | Source | Remarque |
|---|---|---|
| `PlaybackService` (Media3), `MainActivity`, widget | `readers-audio` | copié dans le nouveau dépôt |
| Kit UI (`ui/Common.kt` : `T`, `TextRow`, `ScreenTitle`, `Page`, `TextMenu`, `TextPrompt`) | `readers-audio` | le look Reader's vient de là |
| `data/Library.kt`, `data/Prefs.kt` | `readers-audio` | patron JSON à étendre (§4) |
| `speech/` (whisper.cpp + llama.cpp, modèles partagés entre apps) | submodule `readers-speech` | garder `ndkVersion` ou les `.so` partent non strippés |
| Traduction gemma3:4b | `readers-translator` | 8 Go de RAM ; constante `6_500_000_000L`, pas `8e9` |
| Flavors `prive` / `publique` + `tools/publish_public.sh` | `clavier-plume` (`build.gradle:310`) | patron éprouvé, repris tel quel |
| Import/export OPML | `readers-feeds/lib/services/import_export_service.dart` | logique à porter en Kotlin (≈1 jour) |
| Patron desktop PyQt5 fichier unique | `readers-notes-desktop/readers_notes.py` (1 447 lignes) | + `packaging/` (.deb, PKGBUILD) |

Nouveau dépôt `~/code/readers-podcasts`, `com.freedomfighter.readerspodcasts`,
amorcé par copie de `readers-audio` — on ne touche pas à l'Audio Player, qui reste l'app
« un fichier audio qu'on m'a donné ».

## 3. Écrans (tout en texte, conventions Reader's)

1. **À écouter** — file de lecture : titre, durée, état. L'action fréquente (lire) à une touche.
2. **Chaînes** — la liste des abonnements ; tap → les épisodes de la chaîne.
3. **Lecture** — titre, position, ±30 s, vitesse, et la bascule *texte* / *traduction*.
4. **Texte** — transcription au fil de l'écoute, surlignage **par segment**
   (l'appariement phrase à phrase ne tient qu'à 73–76 %, banc Translator) ; tap sur un
   segment = saut de l'audio ; export `.txt` dans `Documents/Transcriptions`.
5. **Réglages** — thème/police/taille/alignement (déjà là), langue, modèle whisper,
   téléchargement auto, wifi seulement, nettoyage, import/export OPML + JSON.

Ajouter un abonnement : collage d'URL, ou import OPML — le champ s'ouvre **sélectionné**
(première touche = remplacement), selon la règle des flux de création Reader's.

## 4. Données

Pas de Room : fichiers JSON, comme `Library.kt` et Reader's Notes.

- `feeds.json` — `{id (sha1 de l'url), url, title, author, kind: rss|youtube, addedAt,
  autoDownload, keepCount, lastFetch}`
- `feeds/<id>.json` — un fichier d'épisodes **par chaîne** (et non un seul gros index) :
  `{id (guid), title, published, durationMs, mediaUrl, localPath, positionMs,
  state: neuf|encours|ecoute, transcriptUri, hasPoints, language}`, plafonné à
  `keepCount` entrées.
- `settings` (SharedPreferences) + export `reglages.json`.

**Formats d'échange téléphone ↔ bureau** (il n'y a pas de serveur, les fichiers *sont* la
synchronisation) :

- `abonnements.opml` — OPML 2.0, compatible avec n'importe quel autre lecteur ;
- `reglages.json` — réglages + états de lecture (`{feedId, episodeId, positionMs, state}`),
  fusion par « le plus récent gagne » sur `lastPlayed`.

## 5. YouTube — variante privée seulement

Un abonnement à une chaîne YouTube est **déjà un flux RSS** :
`https://www.youtube.com/feeds/videos.xml?channel_id=…` — donc côté abonnement, c'est un
flux de plus (`kind: youtube`). Seule l'extraction audio diffère : **youtubedl-android**
(yt-dlp + Python embarqués), en `priveImplementation` uniquement, pour que l'APK public
n'en contienne pas une ligne. Résolution d'une URL de chaîne (`@handle`) → `channel_id`
au moment de l'ajout.

Points de vigilance :

- l'APK privé grossit nettement (Python + yt-dlp) : on publie déjà des APK arm64 séparés,
  et l'APK privé ne va pas sur F-Droid — il part dans `~/kDrive/apk/` avec un lien
  Tailscale annoncé par le bot (limite de 50 Mo côté bot) ;
- yt-dlp casse quand YouTube change : prévoir la mise à jour de yt-dlp à l'exécution que
  la bibliothèque sait faire, sinon chaque panne impose une nouvelle version de l'app ;
- **licence** : youtubedl-android est en GPLv3 alors que `readers-audio` est en MIT.
  Tant que la variante privée n'est pas distribuée, rien ne change ; si elle l'était un
  jour, toute l'app basculerait en GPLv3. La variante publique reste MIT et propre.

## 6. Desktop (en parallèle)

`~/code/readers-podcasts-desktop/readers_podcasts.py`, PyQt5 fichier unique, patron de
Reader's Notes desktop : `feedparser` + `QMediaPlayer`, mêmes `abonnements.opml` et
`reglages.json`, transcription déléguée au poste (WhisperX + GPU) plutôt qu'à whisper.cpp.
yt-dlp y est trivial (binaire du système), donc pas de scission public/privé côté bureau.
Livraison : `.deb` + PKGBUILD + GitHub Actions PyInstaller (.exe et deux .dmg par tag),
comme les trois autres desktops — pièges connus : `tzdata` et le nom de fuseau de Qt sous
Windows.

Mener les deux de front a un intérêt précis : figer tôt `abonnements.opml` et
`reglages.json`, qui sont le seul lien entre les deux.

## 7. Découpage

| Version | Contenu | Ordre de grandeur |
|---|---|---|
| 0.1 | ✅ **faite le 2026-09-18** — abonnements RSS, actualisation, téléchargements, lecture avec reprise, OPML + JSON, Android **et** desktop | le gros morceau |
| 0.2 | YouTube dans la variante privée (flux de chaîne + extraction audio), nettoyage auto | moyen |
| 0.3 | Transcription (submodule `speech`), export `.txt`, points principaux | petit — presque tout est écrit |
| 0.4 | Traduction gemma3:4b, affichage synchronisé texte/traduction, tap-pour-sauter | moyen |
| 0.5 | Widget, six langues, F-Droid + site, paquets desktop | petit |

## 8. Risques identifiés

- **Durée de transcription** : une heure de podcast sur téléphone, c'est long. « Transcrire »
  reste une action explicite, jamais automatique après téléchargement (règle de l'Audio Player).
- **Mémoire** : la traduction demande 8 Go réels ; un téléphone de 8 Go en déclare ~7,4.
- **Robustesse des flux** : c'est là qu'AntennaPod a dix ans d'avance — lire son code avant
  d'écrire le parseur plutôt qu'après.
- **Taille du chantier** : trois fronts (Android, extraction YouTube, desktop) plus i18n,
  widgets, F-Droid et le site. D'où le découpage ci-dessus.


## 9. Ce que la 0.1 a établi (2026-09-18)

- Android : dépôt `~/code/readers-podcasts`, amorcé depuis `readers-audio` ; flavors `prive` et
  `publique` en place, `BuildConfig.YOUTUBE` à `false` des deux côtés jusqu'à la 0.2.
- Bureau : `~/code/readers-podcasts-desktop/readers_podcasts.py`, un fichier PyQt5.
- Vérifié sur l'émulateur : abonnement par partage de lien, listes, téléchargement, lecture d'un
  fichier local **et** d'un flux non téléchargé, fin d'épisode (marqué écouté, fichier effacé),
  actualisation qui préserve les positions, refus motivé d'une adresse YouTube.
- Vérifié au bureau : abonnement, téléchargement, lecture jusqu'au bout avec le même résultat.
- Les deux côtés lisent les fichiers de l'autre (`InteropTest` + `tools/check-interop.py`).
- Trois corrections que seule la vue à l'écran a fait apparaître : `0 min` pour un épisode de
  douze secondes, l'absence du nom de la chaîne dans une liste qui en mélange plusieurs, et les
  entités HTML (`&agrave;`) lues telles quelles dans les descriptions.
- Un idiome repris de Reader's Tasks pour les réglages : une ligne « étiquette : valeur »,
  parce que trois « actif » empilés ne se lisent pas.

**Reste à faire avant de livrer la 0.1 à un téléphone :** le widget n'a pas été reposé sur un
écran d'accueil, l'import OPML et l'import des réglages n'ont été éprouvés que par les tests
(le sélecteur de fichiers Android n'a pas été parcouru à la main), et les paquets desktop
(.deb, PKGBUILD, GitHub Actions) restent prévus pour la 0.5.


## 10. La 0.1.1 (2026-09-18, après le premier essai)

Ce que le premier import de 140 flux a montré, et ce qui a été demandé dans la foulée :

- **« à écouter » supprimée.** En défaut, elle ouvrait sur un écran vide juste après un import :
  rien n'est encore téléchargé, donc la liste était vide alors que l'app venait d'avaler
  cent quarante chaînes. Les présentations sont maintenant **chaînes** (défaut), **épisodes** et
  **favoris**, et un réglage dit laquelle s'ouvre.
- **Favoris** : une étoile, gardée à la main ; c'est la seule liste que l'app ne remplit ni ne
  vide toute seule. Elle voyage dans `reglages.json`, même sur un épisode jamais commencé.
- **Chaînes par ordre du dernier épisode paru** — l'ordre alphabétique ne disait rien.
- **Tirer pour actualiser**, avec une ligne de texte plutôt qu'une roue qui tourne (Material
  n'entre pas dans une app sans icônes), et **↻** et **+** à gauche du ⋯.
- Le **+** propose ce que contient le presse-papier quand c'est une adresse, ouvert sélectionné.
- **« Ajouter à Reader's Podcasts »** dans la feuille de partage : un activity-alias, pour que la
  ligne dise ce qui va se passer et non le nom de l'app.
- **Recherche** dans le ⋯, sur les chaînes et les épisodes déjà là — rien n'est demandé à
  l'annuaire de qui que ce soit, ce qui la rend utilisable hors connexion.
