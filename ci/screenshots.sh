#!/usr/bin/env bash
# Prend des captures d'écran de l'application dans l'émulateur (appelé par la CI).
set -euxo pipefail

OUT=docs/captures
mkdir -p "$OUT"
PKG=fr.cast.audio

adb install -r AudioCast-*.apk

# L'émulateur du CI est lent juste après le démarrage : on masque les fenêtres
# « X ne répond pas » et on lui laisse le temps de se stabiliser.
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0 || true
sleep 45
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

# Barre d'état propre (heure fixe, batterie pleine, pas de notifications).
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4

shot() {
  sleep "$2"
  # Referme une éventuelle fenêtre système restée ouverte.
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
}

launch() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@"
}

# 1. Écran d'accueil réel (aucune enceinte dans l'émulateur).
adb shell cmd uimode night no
launch
shot accueil 8

# 2. À l'arrêt, avec des enceintes (données de démonstration).
launch --ez demo true --ez demo_idle true
shot arret 4

# 2. Diffusion en cours (données de démonstration).
launch --ez demo true
shot diffusion 4

# 3. Même écran en thème sombre.
adb shell cmd uimode night yes
launch --ez demo true
shot diffusion-sombre 4
adb shell cmd uimode night no

ls -la "$OUT"
