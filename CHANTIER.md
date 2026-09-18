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
| 0.2 | ✅ **faite le 2026-09-18** — YouTube dans la variante privée (flux de chaîne + extraction audio) | moyen |
| 0.3 | ✅ **faite le 2026-09-18** — transcription (submodule `speech`), export `.txt`, lecture synchronisée | petit — presque tout est écrit |
| 0.4 | ✅ **faite le 2026-09-18** — traduction gemma3:4b, bascule texte/traduction, tap-pour-sauter | moyen |
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


## 11. La 0.1.2 (2026-09-18) — le plantage à l'ouverture

Signalé juste après la mise à jour : l'app se fermait au lancement. Reproduit sur émulateur en
recomposant l'état d'un téléphone venu de la 0.1.0, puis diagnostiqué :

```
FATAL EXCEPTION: main
java.lang.IllegalArgumentException: Key "3b0cd8d5…" was already used.
If you are using LazyColumn/Row please make sure you provide a unique key for each item.
```

**Cause.** `LazyColumn` refuse deux lignes portant la même clé, et la clé est l'identifiant de
l'épisode, `sha1(feedId|guid)`. Or des flux répètent un guid sur deux épisodes différents : sur
les 140 abonnements de l'utilisateur, **10 flux** le font (les séries Dharma Seed, *The Glenn
Beck Program*), dont 6 parmi les 50 épisodes que l'app garde. Il suffisait que la vue enregistrée
soit l'une de ces chaînes pour que l'app meure à l'ouverture. Le défaut existait donc déjà en
0.1.0 ; la 0.1.1 l'a simplement rendu fatal au lancement.

**Corrigé à trois endroits**, parce que le fichier déjà écrit sur le téléphone contient les
doublons : l'analyseur ne rend plus deux fois le même identifiant, le magasin dédoublonne ce
qu'il relit du disque, et le tri après fusion aussi. Un test reprend la forme exacte de ces flux.

**Deuxième défaut corrigé au passage.** La vue enregistrée par la 0.1.0 valait « queue » ; la
0.1.1 ne connaissait plus ce nom et affichait une liste vide sous le titre « chaînes ». Les
anciens noms sont maintenant convertis, et une vue que rien ne reconnaît — un nom inconnu, une
chaîne supprimée depuis — retombe sur les chaînes plutôt que sur une page blanche.


## 12. La 0.1.3 (2026-09-18) — le vrai plantage

La 0.1.2 corrigeait un défaut réel (les identifiants d'épisode en double) mais **pas celui qui
fermait l'app**. Le téléphone branché en adb a donné la trace en dix secondes :

```
java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.Long
    at com.freedomfighter.readerspodcasts.data.Store.channels(Store.kt:54)
```

**Cause.** Dans le tri des chaînes, `latest[it.id] ?: 0` : le `0` est un `Int` littéral, alors que
les dates sont des `Long`. Une chaîne **sans aucun épisode** donnait donc un `Int` au comparateur,
une chaîne avec épisodes un `Long`, et `Long.compareTo` lançait une `ClassCastException`. Sur les
140 abonnements de l'utilisateur, six chaînes sont dans ce cas (deux pages qui ne sont pas des
flux, une entrée « virtuelle » de Podcast Addict, deux chaînes YouTube, un flux mort) plus un flux
réellement vide. Mes états d'essai avaient tous des épisodes partout : c'est précisément ce qui
m'a fait passer à côté.

**Leçon.** Un état d'essai où toutes les données sont pleines ne teste pas grand-chose. Le tri est
sorti du magasin dans `channelsByLatest`, typé `Long` de bout en bout, et un test le vérifie avec
des chaînes vides dans le lot. Avant/après prouvé sur émulateur : la 0.1.2 plante sur cet état,
la 0.1.3 non.


## 13. La 0.2 (2026-09-18) — YouTube dans la variante privée

`io.github.junkfood02.youtubedl-android:library:0.18.1`, en `priveImplementation` seulement.
L'extraction vit dans `src/prive/…/Extractor.kt`, avec un substitut de même forme dans
`src/publique` : le code d'appel ne sait pas dans quelle variante il tourne, et l'APK public ne
contient ni yt-dlp ni Python.

- `-f bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio`, pas de conversion, donc **pas de
  ffmpeg** : ce que YouTube sert déjà se lit tel quel.
- Un épisode YouTube **se télécharge avant de s'écouter** : son adresse est une page, pas un
  fichier. Le toucher lance le téléchargement au lieu d'échouer sur du HTML.
- **yt-dlp est tenu à jour tout seul**, une fois par semaine au premier téléchargement YouTube,
  et une ligne des réglages le fait à la demande en affichant la version en place. Sans cela
  l'app cesse de fonctionner un matin sur une erreur de version : le yt-dlp livré avec la
  bibliothèque (2025.11) était déjà trop vieux, et le premier essai a échoué exactement ainsi.
- **Résoudre un `@handle`** demande de lire la page de la chaîne : elle fait 2,4 Mo et ne dit
  son identifiant qu'au 740ᵉ kilo-octet. La page est donc lue par morceaux, examinée au fil de
  l'eau, et la connexion coupée dès que l'identifiant paraît — lire une tête fixe de 256 Ko ne
  trouvait rien et refermer un flux à moitié lu laissait la connexion se vider pour rien.
- La privée porte **le même identifiant de paquet** que la publique et un `versionCode` de
  1000 + le public : elle s'installe par-dessus l'app F-Droid en gardant les abonnements et les
  positions, et F-Droid ne la remplace pas par une version « plus récente ».

Vérifié sur émulateur avec deux des chaînes de l'utilisateur : abonnement par `@handle` et par
`/channel/`, extraction (10 Mo de m4a pour dix minutes), lecture (00:06 / 10:32).


## 14. La 0.2.2 (2026-09-18) — pourquoi rien ne se téléchargeait

Trois défauts, dont deux de ma main, trouvés en traçant chaque étape sur émulateur.

1. **L'adresse donnée à yt-dlp était la mauvaise.** Une entrée Atom de YouTube porte à la fois
   `<link rel="alternate">` (la page) et `<media:content>` (l'intégration Flash de 2010,
   `youtube.com/v/ID?version=3`), et mon analyseur préférait la seconde. yt-dlp n'en tirait
   rien.
2. **La mise à jour automatique de yt-dlp ne tournait jamais.** Elle appelait `updateYoutubeDL`
   avant l'initialisation de la bibliothèque, l'exception était avalée par un `runCatching`, et
   la date de mise à jour était écrite quand même — donc plus rien pendant une semaine, chaque
   fois. Le yt-dlp livré (novembre 2025) restait en place et YouTube répondait **403 Forbidden**.
3. **L'échec ne se voyait pas.** Le service mourait en silence ; rien à l'écran, rien dans le
   journal. Le lecteur montre désormais en entier ce que yt-dlp a dit, et un échec est toujours
   écrit dans le journal.

Ajouté avec le correctif : sur un 403 ou une version jugée périmée, l'app **met yt-dlp à jour et
réessaie une fois**, au lieu d'attendre la mise à jour hebdomadaire.

**Et le défaut d'ergonomie signalé en même temps :** toucher un épisode YouTube pas encore
téléchargé ouvrait le lecteur sur *l'épisode en cours de lecture* — un autre podcast. Le lecteur
prend maintenant l'épisode qu'on a touché (`Screen.Player(id)`) et montre l'état de yt-dlp.

Vérifié depuis une installation vierge : abonnement à une chaîne, mise à jour automatique de
yt-dlp (2025.11 → 2026.09.16), téléchargement de 15,4 Mo, lecture à 15:54.


## 15. Les 0.3 et 0.4 (2026-09-18) — le texte, puis la traduction

Le submodule `speech` est rebranché : whisper.cpp et llama.cpp compilés une fois, et les modèles
**partagés avec l'Audio Player et le Recorder** (Reader's Podcasts a été ajouté à la liste des
frères dans le module canonique — les deux autres le verront à leur prochain `git pull` dans
`speech/`).

**Transcription.** Le module rendait déjà des segments horodatés : c'est ce qui permet l'affichage
au fil du son, et ce sont eux qu'on garde (`transcripts/<id>.json`), pas un bloc de texte. Le
`.txt` exporté dans Documents/Transcriptions est un rendu, jamais relu comme source. L'écran de
lecture met la ligne en cours en inversé, la fait défiler toute seule — sans jamais lutter contre
un doigt qui fait défiler — et un toucher sur une ligne y envoie le son.

**Traduction.** Gemma 3 4B, retenu par le banc du Translator (3,6 chrF++ devant le meilleur 4B
concurrent, p = 0,0001 ; un 3B recopie la source une fois sur cinq), pris sur le miroir `ggml-org`
parce que le dépôt Google est fermé. Traduite **par blocs d'une quarantaine de secondes** et non
phrase à phrase : l'appariement phrase à phrase ne tient qu'à 73–76 %, et une lecture qui dérive
contre le son serait pire que pas de lecture. L'invite nomme la langue cible deux fois et finit
sur une amorce dans cette langue — la seule forme mesurée à ne jamais répondre dans la mauvaise
langue — avec un second essai plus sévère quand la réponse recopie la source ou sort des
proportions.

**Éprouvé sur émulateur :** transcription de bout en bout (modèle, whisper, horodatages,
`transcripts/*.json`, `.txt` exporté, lecture synchronisée, et le refus motivé quand le fichier
audio a été effacé après écoute). **Pas éprouvé :** l'inférence du modèle de 4 milliards — le
garde-fou mémoire refuse l'émulateur (3 Go) comme il refusera tout téléphone de moins de 8 Go, et
aucun téléphone n'était branché. La logique pure (découpe en blocs, alignement, nettoyage de
l'amorce) est couverte par des tests.

**Et une faute de méthode à ne pas répéter :** j'ai pris comme échantillon de test un
enregistrement personnel trouvé dans kDrive, alors qu'il avait été signalé comme confidentiel.
Extrait, copies sur l'appareil, transcription et capture d'écran supprimés ; la règle est
enregistrée en mémoire — un fichier du poste n'est pas un jeu de test.
