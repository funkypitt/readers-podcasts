# Privacy policy — Reader's Podcasts

*Last updated: 8 November 2026*

Reader's Podcasts (`com.freedomfighter.readerspodcasts`) is published by Pierre Gallaz. It plays the podcasts you subscribe to, downloads their episodes, and can write an episode down and translate it on the phone. It is free, has no ads, no account and no tracking.

## Data the app collects

None. The app does not collect, store or transmit any personal data to the publisher or to anyone else, and it contains no analytics, advertising or crash-reporting library.

## Network use

The network is used only for what you ask:

- reading the feeds you subscribe to and downloading their episodes (requests go to each podcast's own server);
- searching a podcast by name when you type one in the **+** screen (requests go to Apple's iTunes Search and to fyyd.de, with the words you typed and nothing else);
- downloading the speech and translation models, once, when you first ask for a transcription or a translation (from huggingface.co); the models are then shared with the other Reader's apps on the phone;
- sending a transcript to your own Reader's Books library, only if you entered that library's WebDAV address and credentials in the settings (requests go to that server only).

Each request is started by your tap or by the refresh you enabled. No identifier, account or device information is sent. The app opens with a refresh of your feeds at most once an hour, and only if you leave that setting on.

## Data kept on the phone

Your subscriptions, your position in each episode, your settings, the downloaded episodes, the transcripts and the models stay in the app's storage on the phone. The WebDAV credentials for your library, if you enter them, are kept in the app's private storage. Everything is deleted when the app is uninstalled. Your OPML and settings exports are written where you choose; they are yours to keep or delete. Android's own backup may copy the subscriptions, positions and settings (not the audio files nor the models) to the Google account configured on the phone; that is controlled by the phone's backup settings, not by the app.

## Permissions

The app asks only for what its job needs: internet access and the state of the connection (to download over Wi-Fi only if you want), playing audio through a foreground service, downloading and transcribing through a foreground service, keeping the processor awake while it transcribes, and showing a notification with playback controls and progress. It has no access to your microphone, camera, contacts or location.

## Children

The app is not directed at children and collects no data from anyone.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Source code: https://github.com/funkypitt/readers-podcasts
