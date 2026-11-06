# GYMER per Android (app nativa)

1. Carica nel repo GitHub, mantenendo le cartelle: `index.html`, `sw.js`, `.vercelignore`, `.gitignore`, `.github/workflows/android.yml` e tutta la cartella `android-app/`.
2. Su GitHub: scheda **Actions** > "Build app Android" > attendi il segno verde (5-10 minuti la prima volta). Se non parte da sola: **Run workflow**.
3. Scarica `GYMER.apk` da **Releases > latest** (sul telefono, dal browser) e installalo (consenti "Installa app sconosciute" al browser).
4. Apri GYMER, importa il backup JSON (Profilo > Backup dati), concedi le notifiche e, da Profilo, il permesso "Mostra sopra altre app".

Ogni modifica a `index.html` rigenera l'APK da sola. La chiave `debug.keystore` e' fissa: gli aggiornamenti si installano sopra la versione precedente senza perdere i dati.
