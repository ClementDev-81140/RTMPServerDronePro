# 🚁 RTMP Server Drone PRO

Application Android haute performance transformant le smartphone en **serveur RTMP local (Port 1935)** pour recevoir le flux vidéo direct d'un drone (**DJI Fly**, Autel, Parrot...) et le relayer en temps réel vers plusieurs canaux distants (**Telegram RTMPS**, YouTube, Restream, Facebook) avec une latence ultra-faible (300-500 ms).

---

## ✨ Fonctionnalités Principales

- **Serveur RTMP Inbound Universel (Port 1935)** : Compatible avec les contraintes réseau strictes de DJI Fly (négociation dynamique de chunk size 4096, accusés de réception ACK, commandes AMF0).
- **Relais Multi-Destinations & Failover** : Diffusion simultanée vers jusqu'à 3 plateformes actives avec basculement automatique.
- **Support Telegram RTMPS (Port 443 / SSL)** : Intégration TLS avec SNI et alignement d'horloge synchrone avec l'encodeur matériel du drone.
- **Décodage Matériel Local (MediaCodec H.264)** : Conversion à la volée AVCC ➔ Annex-B et rendu direct sur SurfaceView sans latence.
- **Mode Plein Écran Immersif (⛶)** : Basculement automatique en mode paysage immersif.
- **Persistance Locale (Room DB)** : Gestion complète des destinations (Ajout, Édition, Duplication, Priorité ⭐).
- **Design Sombre Moderne** : Interface épurée (#0E0E10) avec indicateurs de statut visuels en temps réel.

---

## 🛠️ Stack Technique

- **Langages** : Java 11, C++17 (NDK/CMake)
- **Min SDK** : 24 (Android 7.0) | **Target SDK** : 34 (Android 14)
- **Architecture** : Android Foreground Service, MediaCodec, Room Database, Java NIO Non-blocking Sockets.

---

## 🚀 Utilisation

1. Démarrez l'application sur votre smartphone Android.
2. Connectez le drone au point d'accès du téléphone ou sur le même réseau Wi-Fi.
3. Dans DJI Fly, réglez la diffusion en direct sur : `rtmp://[IP_DU_SMARTPHONE]:1935/live`.
4. Configurez vos destinations (ex: URL Telegram `rtmps://...` + Clé de stream).
5. Activez vos chaînes et appuyez sur **START STREAMING**.
