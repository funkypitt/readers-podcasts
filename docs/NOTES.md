# Reader's Podcasts — notes

Matière de référence sortie du README : les deux variantes, la charpente, les tests.
L'histoire du chantier, mesures et fausses pistes comprises, est dans `CHANTIER.md`.

**Traduire** se fait par blocs d'une quarantaine de secondes, parce que l'appariement phrase à
phrase ne tient que trois fois sur quatre ; chaque étape dit combien de temps il reste.

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
