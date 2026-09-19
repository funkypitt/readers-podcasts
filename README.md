![Reader's Podcasts](docs/banner.png)

# Reader's Podcasts

Un lecteur de podcasts qui ne montre que du texte, dans la ligne des autres apps Reader's.
Les abonnements, les téléchargements, la lecture avec reprise — et rien d'autre à l'écran que
des lignes qu'on peut toucher.

**1.0.0** — sur [F-Droid](https://funkypitt.github.io/fdroid-repo/repo) et dans les
[releases](https://github.com/funkypitt/readers-podcasts/releases). Le jumeau de bureau (Linux,
.deb et PKGBUILD) est dans [readers-podcasts-desktop](https://github.com/funkypitt/readers-podcasts-desktop).
L'histoire du chantier, mesures et fausses pistes comprises, est dans `CHANTIER.md`.

## Ce qu'elle fait

**S'abonner, en un geste.** **+** propose l'adresse qui se trouve dans le presse-papier ;
« Ajouter à Reader's Podcasts » est dans la feuille de partage de toute app ; l'OPML de cent
quarante abonnements s'importe d'un fichier.

**Quatre listes et une chaîne à la fois** : **chaînes** (celle qui a publié en dernier en tête),
**épisodes**, **favoris**, **téléchargés**. Le titre porte un ▾ qui ouvre le choix ; dans une
chaîne, ← ramène aux chaînes et ↻ n'actualise qu'elle. Tirer la liste vers le bas actualise. La
liste retrouve sa place quand on y revient.

**Ouvrir n'est pas écouter.** Un appui sur un épisode l'ouvre : son titre en entier, sa date, puis
**ce dont il parle** — les notes, leurs **liens qu'on suit d'un appui et qu'on garde d'un appui
long**, leurs **chapitres** quand elles en donnent la liste. C'est ▶ qui décide d'écouter. La
vitesse et le texte sont à droite de l'horloge, l'étoile des favoris dans la barre.

**Mettre par écrit, sur le téléphone**, avec whisper : on dit la langue parlée (rien n'est deviné,
la chaîne propose celle de la dernière fois), on choisit entre l'ordinaire — recommandée — et la
soignée, quatre fois plus lente. Le texte garde ses horodatages et **se lit pendant qu'on
écoute** : la ligne prononcée est inversée, un appui sur une ligne y envoie le son, et l'écran de
lecture a ses propres commandes. Il s'exporte en `.txt`.

**Traduire, sur le téléphone aussi**, avec Gemma 3 4B — par blocs d'une quarantaine de secondes,
parce que l'appariement phrase à phrase ne tient que trois fois sur quatre ; chaque étape dit
combien de temps il reste.

**Rien ne disparaît dans votre dos.** « Effacer une fois écouté » est inactif par défaut, et même
actif il épargne les favoris, ce qui a été mis par écrit et les vidéos.

Recherche hors connexion sur ce qui est déjà là ; import/export **OPML** et **JSON** (les réglages
avec la position de chaque épisode — c'est toute la synchronisation avec le bureau) ; six langues
(en, fr, de, es, pt, ru), clair ou sombre, trois polices, trois tailles.

## Les deux variantes

`prive` et `publique`, sur le patron de Clavier Plume.

La **privée** s'abonne aux chaînes YouTube (`@nom`, `/channel/…`, une liste de lecture), et
« charger plus d'épisodes » remonte au-delà des quinze que porte le flux. Un abonnement à une chaîne n'a rien de particulier —
YouTube publie `feeds/videos.xml?channel_id=…`, un flux Atom ordinaire ; ce qui diffère est le
média, une page de visionnage et non un fichier, et c'est yt-dlp qui en tire la piste audio.
L'app garde yt-dlp à jour toute seule, une fois par semaine, parce que YouTube change et que
yt-dlp suit en quelques jours. Elle porte le même identifiant de paquet que la publique et un
`versionCode` bien plus haut : elle s'installe donc par-dessus, garde les abonnements et les
positions, et F-Droid ne la remplace pas.

La **publique** n'en contient pas une ligne : ni yt-dlp, ni le Python qu'il fait tourner. Le
code d'extraction vit dans `src/prive`, avec un substitut de même forme dans `src/publique`,
si bien que rien ailleurs dans l'app n'a à savoir dans quelle variante il tourne. Une adresse
YouTube y est refusée en toutes lettres plutôt que récupérée vide.

**Licence.** youtubedl-android est en GPLv3 alors que le reste est en MIT : c'est pourquoi la
variante privée n'est pas distribuée. La publique reste MIT et propre.

    ./gradlew assemblePriveDebug        # l'APK de travail
    ./gradlew assemblePubliqueRelease   # celui qui se publie
    ./gradlew :app:testPriveDebugUnitTest

## Comment c'est fait

Kotlin, Jetpack Compose, Media3 — la même charpente que Reader's Audio Player, dont le kit UI
(`ui/Common.kt`, `ui/Theme.kt`), le service de lecture et le widget viennent directement.

| | |
|---|---|
| `data/Store.kt` | tout en JSON : `feeds.json`, puis un `feeds/<id>.json` par chaîne |
| `data/FeedParser.kt` | RSS 2.0, Atom et Media RSS en un passage, entités HTML comprises |
| `data/Opml.kt`, `data/Backup.kt` | les deux fichiers d'échange |
| `net/Refresher.kt` | ajouter un flux, actualiser |
| `net/DownloadService.kt` | les téléchargements, en avant-plan, reprenables |
| `net/Youtube.kt` | reconnaître une adresse YouTube ; l'extraction est dans `src/prive` |
| `TranscribeService.kt`, `transcribe/` | whisper et la traduction, en avant-plan sous wake lock |
| `data/Transcript.kt` | les lignes et leurs temps, un fichier par épisode et par langue |
| `ui/TextScreen.kt` | la lecture du texte au fil du son |

Les identifiants sont calculés (`sha1(url)` pour une chaîne, `sha1(feedId|guid)` pour un
épisode), donc le bureau trouve le même identifiant pour le même épisode : c'est ce qui permet
à `reglages.json` de porter une position d'un appareil à l'autre sans serveur.

## Tests

`app/src/test` tourne sur un JVM ordinaire, avec kXML à la place de l'analyseur d'Android.
`InteropTest` lit les fichiers écrits par l'app desktop (`app/src/test/resources`) et laisse les
siens dans `app/build/interop/`, que `tools/check-interop.py` du dépôt desktop relit. Une dérive
d'un côté ou de l'autre échoue là plutôt que sur le téléphone de quelqu'un.

## Captures d'écran

<img src="docs/screenshot-1.png" width="30%"> <img src="docs/screenshot-2.png" width="30%">
