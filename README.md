# AudioCast

Application Android qui diffuse **le son de votre téléphone** (musique, podcasts, jeux, vidéos…) vers :

- **les enceintes Google / Chromecast en Wi-Fi** : Nest Audio, Nest Mini, Chromecast Audio, téléviseurs avec Chromecast, groupes d'enceintes Google Home ;
- **les lecteurs DLNA/UPnP en Wi-Fi** : Sonos, Freebox Player, TV connectées, amplis et enceintes réseau (Denon/HEOS, Yamaha MusicCast, Bose…) ;
- **les enceintes Bluetooth** : grâce au routage audio natif d'Android, avec un sélecteur de sortie intégré ;
- **n'importe quel autre lecteur du réseau** (VLC, Kodi, navigateur web d'un PC ou d'une TV…) grâce à une adresse de flux HTTP.

## Fonctionnement

```
 Applis du téléphone ──► Capture audio Android 10+ ──► Encodage AAC ──► Serveur HTTP local (port 8765)
 (Spotify, YouTube…)     (AudioPlaybackCapture)        (MediaCodec)          │
                                                                              ├──► Enceinte Google Cast (Wi-Fi)
                                                                              ├──► Lecteur DLNA/UPnP (Wi-Fi)
                                                                              └──► VLC / navigateur / Kodi…
 Bluetooth : Android envoie le son directement à l'enceinte appairée.
```

1. `AudioCaptureService` capture le son joué par les autres applications via l'API
   `AudioPlaybackCaptureConfiguration` (autorisation de « capture d'écran » demandée au démarrage).
2. `AacEncoder` encode le son en AAC-LC 48 kHz stéréo 192 kb/s (trames ADTS).
3. `StreamServer` diffuse le flux sur `http://<ip-du-téléphone>:8765/stream.aac` (AAC),
   `/stream.wav` (PCM/WAV) et `/stream.l16` (LPCM, format obligatoire de la norme DLNA).
4. `MainActivity` envoie cette adresse à l'enceinte choisie :
   - Google Cast : SDK Google Cast, récepteur multimédia par défaut (sans inscription à la console Cast) ;
   - DLNA/UPnP (`Dlna.kt`) : découverte SSDP des `MediaRenderer`, puis commandes SOAP
     `SetAVTransportURI` + `Play`, puis vérification (`GetTransportInfo` + connexion au flux).
     Les formats annoncés par l'appareil (`GetProtocolInfo`) sont essayés en premier.

## Utilisation

1. Téléphone et enceinte sur le **même réseau Wi-Fi**.
2. Ouvrez AudioCast et touchez l'**icône Cast** pour choisir une enceinte (ou un groupe). La capture démarre toute seule.
   Vous pouvez aussi toucher « Démarrer la diffusion » puis choisir l'enceinte.
3. Acceptez la demande de capture, puis lancez votre musique dans n'importe quelle application.
4. Pour un appareil DLNA : touchez « Rechercher les appareils », puis l'appareil voulu.
5. Pour le Bluetooth : appairez l'enceinte dans les réglages Android, ou touchez « Choisir la sortie audio ».
6. Pour un autre appareil : ouvrez l'adresse affichée dans VLC (« Ouvrir un flux réseau ») ou dans un navigateur.

## Limites à connaître

- **Android 10 minimum** (API de capture audio).
- Les applications peuvent **interdire la capture** de leur son (Netflix, certaines applis protégées, applis
  très anciennes). Dans ce cas l'enceinte reste silencieuse pour ces applis.
- Le Wi-Fi ajoute **quelques secondes de latence** (mise en mémoire tampon par l'enceinte) :
  parfait pour la musique, pas adapté à la vidéo avec synchronisation labiale.
- Le téléphone continue de jouer le son localement : baissez son volume ou utilisez un casque.
  Sur certains appareils, couper complètement le volume média coupe aussi la capture.
- DLNA : chaque fabricant interprète la norme à sa façon. L'application essaie automatiquement
  plusieurs formats (LPCM/L16, WAV, AAC) jusqu'à ce que le lecteur lise vraiment le flux ; un appui
  long sur un appareil permet d'imposer un format. Le **journal** en bas de l'écran montre ce que
  chaque appareil demande au téléphone. Le PCM (L16/WAV) consomme environ 1,5 Mb/s sur le Wi-Fi.
- Décalage : Google Cast garde quelques secondes en mémoire tampon. Le bouton **Resynchroniser**
  relance la lecture pour repartir du direct, et le téléphone ne garde jamais plus de ~2 s
  d'avance pour une enceinte en retard.
- Le son capturé n'est pas envoyé en Bluetooth par l'application : Android le fait nativement.

## Télécharger l'APK

**Dernière version :** https://github.com/Yggdrasil82/Application-de-cast/releases/latest/download/AudioCast.apk

Chaque push compile l'APK avec GitHub Actions et le publie dans une
[Release](https://github.com/Yggdrasil82/Application-de-cast/releases). Les versions successives
sont signées avec la même clé (`app/debug.keystore`, clé de développement) : une nouvelle version
s'installe par-dessus l'ancienne.

## Compilation

En local, avec Android Studio ou le SDK Android installé :

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Pistes d'amélioration

- Réglage de la qualité (débit) et de la latence.
- Envoi simultané vers plusieurs enceintes Cast individuelles (les groupes Google Home le permettent déjà).
