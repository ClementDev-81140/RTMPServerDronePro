# 🔍 Audit complet — RTMP Server Drone PRO

**Date** : 29/09/2026
**Périmètre** : 16 fichiers Java (~3 200 lignes), layouts, manifeste, configuration Gradle
**Méthode** : revue ligne à ligne + analyse statique (références de ressources, cohérence des appels
inter-classes, équilibrage syntaxique, recherche de motifs à risque : threads, sockets, Room,
MediaCodec/MediaMuxer, cycle de vie Android).

> ⚠️ **Important** : le bac à sable ne dispose ni de JDK ni de SDK Android, donc **aucun test
> d'exécution n'a pu être fait**. Les bugs ci-dessous sont issus d'une analyse statique. La
> checklist de test manuel en fin de document permet de valider chaque correction en vol.

---

## 1. Résumé

| Sévérité | Trouvés | Corrigés dans ce commit |
|---|---|---|
| 🔴 Critique | 2 | 2 |
| 🟠 Élevée | 2 | 2 |
| 🟡 Moyenne | 11 | 11 |
| 🔵 Faible | 8 | 6 |
| ⚪ Limites connues (non bloquantes) | 14 | — |

Toutes les anomalies bloquantes ou susceptibles de casser une session de vol réelle ont été
corrigées. Les points restants sont documentés en section 4 (limites connues / choix de conception).

---

## 2. Bugs corrigés

### 🔴 Critique

**B-01 — Une connexion parasite coupait l'enregistrement et tous les relais**
`RTMPServer.handleClient()` appelait `callback.onStreamStopped()` dans son `finally`, donc pour
**toute** connexion fermée, même un client qui se connecte sans publier (sonde réseau, lecteur,
scan de port). Conséquences : arrêt de l'enregistrement en cours, fermeture de toutes les
destinations, badge repassé en « OFFLINE » en plein vol.
→ Un seul « éditeur » est désormais suivi (`publishingClient`) : seule sa déconnexion termine la
session, et ses sockets sont fermés à l'arrêt du serveur.

**B-02 — Une destination lente gelait tout le relais**
`TelegramStreamer.sendData()` écrivait **directement dans la socket depuis le thread de lecture
RTMP** (méthode `synchronized` + `out.flush()` bloquant). Une seule plateforme au débit insuffisant
(ou un réseau mobile saturé) bloquait donc les autres destinations, l'enregistrement local et
l'aperçu.
→ Chaque destination possède maintenant sa propre file (300 paquets) et son thread d'écriture
`rtmp-sender` ; le thread RTMP ne fait plus qu'empiler (≈ microsecondes). En cas de saturation,
les trames de silence sont sacrifiées en premier, puis la plus ancienne trame vidéo (compteur
`getDroppedPackets()`), jamais la plus récente.

### 🟠 Élevée

**B-03 — Port occupé = écran bloqué sur « WAITING FOR DRONE » indéfiniment**
Si `RTMPServer.start()` échouait (port 1935 déjà pris par une autre app, IP déjà liée), l'exception
était seulement journalisée : l'état `isStreaming` restait vrai et l'utilisateur attendait un drone
qui ne pourrait jamais se connecter.
→ L'échec arrête proprement la session, remonte un message d'erreur et réinitialise l'écran.

**B-04 — Un paquet RTMP malformé tuait la session drone**
`case 1:` (Set Chunk Size) lisait `completeBody[0..3]` sans contrôle de longueur
(`ArrayIndexOutOfBoundsException` → session terminée), et une taille de chunk arbitraire (0 ou
> 0xFFFFFF) était acceptée, ce qui pouvait provoquer des allocations géantes ou une boucle de
lecture infinie.
→ Contrôle de longueur + validation de la plage `1 … 0xFFFFFF`.

### 🟡 Moyenne

**B-05 — Aucune reconnexion des destinations**
Une coupure réseau (ou une erreur d'écriture) laissait la chaîne en ERROR jusqu'à un redémarrage
manuel ; en outre, une erreur d'écriture en cours de flux **n'était jamais signalée** au service.
→ Retry automatique avec backoff 5 s / 10 s / 20 s / 30 s (6 tentatives par destination), annulé à
l'arrêt du serveur ou à la déconnexion du drone, et remis à zéro après une connexion réussie.
L'erreur d'écriture remonte maintenant au service via un `errorListener` (l'ancienne socket est
fermée proprement, la destination passe en ERROR et repart au tour suivant).

**B-06 — Le décodeur d'aperçu ralentissait le relais**
`dequeueInputBuffer(10000)` attendait jusqu'à 10 ms **par NALU**, sur le thread qui alimente aussi
les destinations et l'enregistreur : à 30 fps multi-NALU, cela pouvait dépasser le budget d'une
frame et faire perdre des frames à tout le monde.
→ Attente à 0 ms : si le codec est occupé, l'image est ignorée (comptée) — l'aperçu est le
consommateur le **moins** prioritaire de la chaîne.

**B-07 — Timestamps étendus RTMP non gérés (flux > 4 h 39)**
Le champ timestamp RTMP est limité à 0xFFFFFF ms. Au-delà, un timestamp étendu de 4 octets est
émis par le drone : il était interprété comme des données du paquet (corruption du flux) côté
serveur, et les timestamps sortants étaient tronqués côté destinations.
→ Lecture des 4 octets étendus pour les en-têtes fmt 0/1/2/3, et écriture correcte (avec l'octet
0xFFFFFF + extension, y compris dans les chunks de continuation) à l'envoi.

**B-08 — Métadonnées vidéo fausses (1280×720 en dur)**
`sendMetaData()` annonçait toujours du 720p : Telegram/YouTube/Restream recevaient une résolution
erronée si le drone émet en 1080p ou 4K (mauvais réglage de transcode côté plateforme).
→ La taille réelle est extraite du SPS (parseur déjà présent) et transmise aux streamers.

**B-09 — Modifier une destination en pleine session n'avait aucun effet**
L'édition était bien enregistrée en base, mais le service gardait sa copie (ancienne URL/clé)
jusqu'au prochain START.
→ `StreamService.refreshChannel()` : la destination est retirée puis reconnectée avec les nouveaux
paramètres, immédiatement après l'édition.

**B-10 — Vérification TLS désactivée (`TrustManager` acceptant tout)**
N'importe quel certificat était accepté en `rtmps://` : une attaque de type « homme du milieu »
pouvait lire le flux et la clé de stream.
→ Nouvelle option **Configuration → Security → Verify TLS certificates** (activée par défaut). La
désactivation reste possible pour un serveur à certificat auto-signé (avertissement dans les logs).

**B-11 — IP affichée potentiellement fausse**
`NetworkUtils` renvoyait la première IPv4 trouvée : sur un téléphone en partage de connexion, cela
pouvait être l'adresse **cellulaire**, que le drone ne peut pas joindre — l'utilisateur configurait
alors DJI Fly avec une mauvaise URL.
→ Priorisation des interfaces locales (`wlan*`, `ap*`, `swlan*`, `softap*`, `eth*`, `rndis*`, `p2p*`),
adresses privées uniquement, accès Wi-Fi protégé contre le refus de permission Android 12+, et
`getLocalIpv4Addresses()` pour lister tous les candidats.

**B-12 — Enregistrement activé en cours de session : fichier vide**
Le drone n'émet son SPS/PPS (en-tête H.264) qu'au début d'une publication. Activer l'enregistrement
en plein vol créait donc un enregistreur qui attendait un en-tête… qui ne revenait jamais.
→ L'enregistreur est amorcé avec les en-têtes vidéo/audio mis en cache par le service.

**B-13 — `proguard-rules.pro` jamais appliqué**
`build.gradle` ne déclarait que la configuration par défaut ; les règles de keep du projet
(`-keep class com.rtmp.drone.**`) n'étaient donc pas utilisées dans la build **release** (minification
+ réduction de ressources activées).
→ Fichier ajouté à `proguardFiles`.

**B-14 — Liste des destinations entièrement redessinée chaque seconde**
`notifyDataSetChanged()` toutes les secondes : animations de switch interrompues, appuis parfois
avalés pendant le rafraîchissement.
→ Comparaison ligne par ligne avec la dernière valeur affichée, seules les lignes réellement
changées sont redessinées.

### 🔵 Faible

| ID | Problème | Correction |
|---|---|---|
| B-15 | Octets aléatoires du handshake écrits dans une copie jetée (`Arrays.copyOfRange`) → S1 rempli de zéros | `new Random().nextBytes(s1)` |
| B-16 | Sockets clients RTMP non fermés à l'arrêt du serveur (jusqu'à 15 s de thread résiduel) | Fermeture de tous les clients actifs dans `stop()` |
| B-17 | Aperçu plein écran fermé : le décodeur rendait sur une surface morte en boucle d'erreurs | Détachement de la surface + arrêt du codec, ré-initialisation à la ré-attache |
| B-18 | NALU plus grande que le buffer d'entrée du `MediaCodec` → exception | Frame abandonnée proprement, codec préservé |
| B-19 | Ping RTMP (`User Control PingRequest`) du drone ignoré → risque de coupure par certains encodeurs | Réponse `PingResponse` (event 7) renvoyée au drone |
| B-20 | `videoSize` / `sessionStartMs` non réinitialisés à l'arrêt | Remis à zéro dans `release()` |
| B-21 | Fuite de socket si `connect()` d'une destination échouait (objet streamer jamais fermé) | `stop()` appelé dans le `catch` |
| B-22 | NPE bruyante dans `feedMediaCodec` après arrêt du codec | Sortie immédiate si le codec n'est plus configuré |
| B-23 | Course possible sur les connexions : le retry automatique pouvait ouvrir une **deuxième** session vers la même destination (double publication), et une connexion établie pendant une suppression/édition pouvait être publiée alors qu'elle était obsolète | Verrou `connectingChannels` (une connexion en vol par destination) + numéro de génération par chaîne : toute connexion devenue obsolète est fermée immédiatement |

---

## 3. Points vérifiés et jugés sains

- **Cycle de vie du service** : passage en avant-plan avant tout travail long, `onStartCommand`
  `START_NOT_STICKY`, notification mise à jour (et non recréée) sans clignotement, `stopSelf()`
  compatible avec l'activité encore liée.
- **Enregistrement** : file bornée + thread dédié, timestamps strictement croissants par piste,
  finalisation du MP4 au retour de la session, suppression des fichiers vides, bascule de segment
  si la résolution change.
- **Base Room** : schéma inchangé par rapport à la version 1 (aucune migration requise), champs
  d'exécution en `@Ignore` jamais persistés, copie défensive (`databaseCopy()`) entre UI et service.
- **Cohérence** : 0 référence de ressource manquante, 0 appel de méthode inexistant, aucun fichier
  déséquilibré (passes automatiques relancées après chaque modification).
- **Session drone** : un seul éditeur actif, redécoupage RTMP (chunk size dynamique) conforme,
  ACK envoyés sur la fenêtre demandée, handshake C0/C1/C2 complet.
- **Absence de fuite de contexte** : tous les composants utilisent `getApplicationContext()` ou
  `this` dans un service.

---

## 4. Limites connues (non corrigées, à connaître)

| ID | Point | Risque / recommandation |
|---|---|---|
| L-01 | `allowMainThreadQueries()` sur Room | Micro-saccades possibles si la liste grossit beaucoup ; acceptable ici (quelques dizaines d'entrées) |
| L-02 | Build **release** non signée (pas de `signingConfig`) | Utiliser *Build → Generate Signed APK* ; la build debug fonctionne telle quelle |
| L-03 | Android 14/15 : type de service `dataSync` limité à 6 h/jour (Android 15) | Une session très longue en arrière-plan peut être coupée par le système ; passer à `specialUse` si nécessaire |
| L-04 | Le client RTMP ne répond pas aux pings du serveur distant (drainage seulement) | Volontaire : injecter un paquet mal formé corromprait le flux ; couvert par le retry automatique (B-05) |
| L-05 | Une copie `byte[]` par frame pour l'enregistreur et le décodeur | Pression GC modérée (~4 Mbps ≈ 16 Ko/frame) ; à optimiser en cas de 4K |
| L-06 | Changement de format audio AAC en plein vol | Un nouveau segment MP4 est ouvert (comportement voulu), la piste audio précédente s'arrête |
| L-07 | MP4 non lisible pendant l'enregistrement (atome `moov` écrit à la fin) | Normal avec `MediaMuxer` ; le fichier est finalisé à la déconnexion du drone |
| L-08 | `AppDatabase` version 1 sans migration | Incrémenter la version + migration si le schéma change |
| L-09 | `NativeMultiplexer` + `app/src/main/jni` : code mort | Ni référencé ni compilé (pas de `externalNativeBuild`) ; à supprimer ou à réactiver proprement |
| L-10 | Fichiers `AndroidManifest.xml.bak` et `RTMPServer.java.backup` | À supprimer (ils ne sont pas compilés mais polluent le dépôt) |
| L-11 | `usesCleartextTraffic="true"` | Inutile pour du RTMP (sockets bruts, hors politique cleartext) mais inoffensif |
| L-12 | `allowBackup="true"` | Les clés de stream peuvent être incluses dans une sauvegarde Android ; ajouter `dataExtractionRules` pour les exclure |
| L-13 | Limite de 3 destinations appliquée côté UI uniquement | Un appel direct au service pourrait la dépasser (non exposé actuellement) |
| L-14 | Aucun test d'exécution dans le sandbox | Voir la checklist ci-dessous avant le premier vol |

---

## 5. Checklist de test manuel

**Enregistrement**
1. Activer *Local Recording*, démarrer le serveur, lancer le flux drone → indicateur `● REC 00:xx`.
2. Arrêter le flux drone (ou le serveur) → toast « Recording saved … » et fichier MP4 lisible
   (galerie si *Save to public Movies folder*, sinon dossier de l'app indiqué dans la Configuration).
3. Activer l'enregistrement **pendant** une session déjà en cours → le fichier doit démarrer
   immédiatement (B-12).
4. Vérifier la vidéo **et** l'audio (ou absence d'audio si le drone n'en envoie pas).

**Économie d'énergie**
5. Activer *Battery Saver* en plein vol → l'aperçu s'arrête, le relais continue, l'écran ne chauffe
   plus autant ; désactiver → l'aperçu revient seul.
6. Mesurer la consommation (Battery Historian / stats Android) : le décodeur doit disparaître des
   consommateurs.

**Destinations**
7. 3 destinations actives, couper le Wi-Fi 20 s puis le remettre → les chaînes repassent en LIVE
   sans intervention (B-05).
8. Modifier l'URL d'une destination pendant une session → reconnexion immédiate (B-09).
9. Simuler une destination morte (URL invalide) → ligne en `● ERROR`, tentatives espacées de 5 à 30 s.
10. Vérifier que la liste des destinations **ne disparaît jamais** au démarrage/arrêt du serveur.

**Serveur RTMP**
11. Démarrer une deuxième app qui occupe le port 1935 puis appuyer sur START → message d'erreur
    clair et écran remis à OFF (B-03).
12. Brancher/débrancher un client `ffmpeg -i` qui ne publie pas pendant une session → la
    session drone et l'enregistrement ne doivent pas s'interrompre (B-01).

**UI / divers**
13. Tester tous les interrupteurs en plein vol (aperçu, enregistrement, éco) : aucun tremblement,
    aucun appui perdu (B-14).
14. Configurer le port (ex. 1936) → l'URL affichée pour le drone se met à jour, et un toast prévient
    que le changement s'applique au prochain START.

**Sécurité**
15. Laisser *Verify TLS certificates* actif avec Telegram (certificat valide) → connexion OK.
16. Si une destination refuse la connexion pour cause de certificat, désactiver l'option
    (avertissement dans les logs `adb logcat -s TelegramStreamer`).
