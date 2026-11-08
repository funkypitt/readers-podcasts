# Politique de confidentialité — Reader's Podcasts

*Dernière mise à jour : 8 novembre 2026*

Reader's Podcasts (`com.freedomfighter.readerspodcasts`) est publiée par Pierre Gallaz. Elle lit les podcasts auxquels vous êtes abonné, télécharge leurs épisodes, et peut mettre un épisode par écrit et le traduire sur le téléphone. Elle est gratuite, sans publicité, sans compte et sans pistage.

## Données collectées par l'application

Aucune. L'application ne collecte, ne conserve ni ne transmet aucune donnée personnelle à l'éditeur ou à qui que ce soit, et ne contient aucune bibliothèque d'analyse d'audience, de publicité ou de rapport de plantage.

## Utilisation du réseau

Le réseau ne sert qu'à ce que vous demandez :

- lire les flux auxquels vous êtes abonné et télécharger leurs épisodes (les requêtes vont au serveur de chaque podcast) ;
- chercher un podcast par son nom quand vous en tapez un dans l'écran **+** (les requêtes vont à la recherche iTunes d'Apple et à fyyd.de, avec les mots tapés et rien d'autre) ;
- télécharger les modèles de reconnaissance vocale et de traduction, une fois, à la première transcription ou traduction demandée (depuis huggingface.co) ; les modèles sont ensuite partagés avec les autres applications Reader's du téléphone ;
- envoyer une transcription dans votre propre bibliothèque Reader's Books, seulement si vous avez saisi l'adresse WebDAV et les identifiants de cette bibliothèque dans les réglages (les requêtes ne vont qu'à ce serveur).

Chaque requête part d'un appui de votre part ou de l'actualisation que vous avez activée. Aucun identifiant, compte ou information sur l'appareil n'est envoyé. L'application actualise vos flux à l'ouverture au plus une fois par heure, et seulement si vous laissez ce réglage actif.

## Données conservées sur le téléphone

Vos abonnements, votre position dans chaque épisode, vos réglages, les épisodes téléchargés, les transcriptions et les modèles restent dans l'espace de l'application sur le téléphone. Les identifiants WebDAV de votre bibliothèque, si vous les saisissez, sont conservés dans l'espace privé de l'application. Tout est supprimé avec la désinstallation. Vos exports OPML et réglages sont écrits à l'endroit que vous choisissez ; il vous appartient de les garder ou de les effacer. La sauvegarde Android peut copier les abonnements, positions et réglages (pas les fichiers audio ni les modèles) vers le compte Google du téléphone ; cela dépend des réglages de sauvegarde du téléphone, pas de l'application.

## Permissions

L'application ne demande que ce qu'il lui faut : l'accès à Internet et à l'état de la connexion (pour ne télécharger qu'en Wi-Fi si vous le souhaitez), la lecture audio par un service au premier plan, le téléchargement et la transcription par un service au premier plan, le maintien du processeur éveillé pendant une transcription, et l'affichage d'une notification avec les commandes de lecture et la progression. Elle n'a aucun accès au micro, à l'appareil photo, aux contacts ni à la position.

## Enfants

L'application ne s'adresse pas aux enfants et ne collecte de données auprès de personne.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Code source : https://github.com/funkypitt/readers-podcasts
