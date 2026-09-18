# Reader's Podcasts

Un lecteur de podcasts qui ne montre que du texte, dans la ligne des autres apps Reader's.
Les abonnements, les téléchargements, la lecture avec reprise — et rien d'autre à l'écran que
des lignes qu'on peut toucher.

État : **0.1.0**, le socle. Voir `CHANTIER.md` pour le plan complet et la suite (YouTube en 0.2,
transcription en 0.3, traduction en 0.4).

## Ce que fait la 0.1

- s'abonner à un flux : coller son adresse, ou partager un lien vers l'app depuis un navigateur ;
- actualiser (à l'ouverture, au plus une fois par heure, ou à la demande) ;
- télécharger un épisode, l'écouter, le reprendre où on l'a laissé ; un épisode écouté jusqu'au
  bout est marqué et son fichier effacé, si c'est le réglage ;
- trois listes : **à écouter** (ce qui est sur le téléphone ou commencé), **nouveautés** (tout ce
  qui n'a pas été écouté) et une chaîne à la fois ; le titre porte un ▾ et mène aux chaînes ;
- import/export **OPML** des abonnements et **JSON** des réglages, avec la position de chaque
  épisode — les deux fichiers que l'app partage avec le jumeau desktop ;
- six langues (en, fr, de, es, pt, ru), thème clair/sombre, trois polices, trois tailles.

## Les deux variantes

`prive` et `publique`, sur le patron de Clavier Plume. La variante privée est celle qui
recevra l'abonnement aux chaînes YouTube (0.2, avec yt-dlp embarqué) ; la publique n'en
contiendra pas une ligne. En 0.1 `BuildConfig.YOUTUBE` est `false` des deux côtés, et une
adresse YouTube est refusée en toutes lettres plutôt que récupérée vide.

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
| `net/Youtube.kt` | reconnaître une adresse YouTube (l'extraction arrive en 0.2) |

Les identifiants sont calculés (`sha1(url)` pour une chaîne, `sha1(feedId|guid)` pour un
épisode), donc le bureau trouve le même identifiant pour le même épisode : c'est ce qui permet
à `reglages.json` de porter une position d'un appareil à l'autre sans serveur.

## Tests

`app/src/test` tourne sur un JVM ordinaire, avec kXML à la place de l'analyseur d'Android.
`InteropTest` lit les fichiers écrits par l'app desktop (`app/src/test/resources`) et laisse les
siens dans `app/build/interop/`, que `tools/check-interop.py` du dépôt desktop relit. Une dérive
d'un côté ou de l'autre échoue là plutôt que sur le téléphone de quelqu'un.
