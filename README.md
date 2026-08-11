# Diario 2026 — Alcool & Movimento (app Android)

App Android **offline** che replica il calendario web: per ogni giorno registri se hai
bevuto alcool e se hai corso / camminato / riposato. I dati stanno **solo sul telefono**
(file JSON privato dell'app). Nessuna sincronizzazione cloud.

## Funzioni
- Calendario di tutti i 12 mesi del 2026 (si apre su Luglio).
- Tocca un giorno → scegli *Alcool: Sì/No* e *Attività: Corsa/Camminata/Riposo*.
- Icone solo dove servono: 🍺 alcool sì, 🏃 corsa, 🚶 camminata.
  Alcool "no" e "riposo" non hanno icona (il riposo ha un pallino grigio per indicare che è registrato).
- 5 contatori in tempo reale: Alcool sì, Alcool no, Corse, Camminate, Riposi.
- **Backup** (menu in alto): esporta tutti i dati in un file `diario-2026-backup.json`
  (lo salvi dove vuoi: Download, Drive, ecc.).
- **Ripristina**: carica quel file su un altro telefono per riavere tutti i dati.
- Tema chiaro/scuro automatico (segue il sistema).
- Dati iniziali già inseriti: dal 28 luglio all'11 agosto 2026.

## Come costruire l'APK

> ⚠️ **Nota per questo PC**: le build Gradle/Java da riga di comando falliscono qui
> (CrowdStrike + Zscaler bloccano il self-pipe loopback della JVM). Vedi le alternative sotto.

### Opzione A — Android Studio (consigliata)
1. `File > Open` e seleziona la cartella `C:\Users\drossetti\Diario2026`.
2. Attendi il *Gradle sync* (scarica plugin e dipendenze). Android Studio genera da solo
   il `gradle-wrapper.jar` mancante.
3. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`, oppure collega il telefono
   (debug USB) e premi ▶ **Run**.
   - Se il sync fallisce sempre per il loopback: chiedi a IT di whitelistare il process tree
     di Android Studio / `java.exe`, oppure usa l'opzione B.

### Opzione B — CI in cloud (aggira il blocco locale)
Metti il progetto su un repo Git e usa una GitHub Action:
```yaml
name: build
on: [push, workflow_dispatch]
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: gradle wrapper --gradle-version 8.9   # genera il wrapper
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with: { name: apk, path: app/build/outputs/apk/debug/app-debug.apk }
```
Scarichi l'`app-debug.apk` dall'artefatto e lo installi.

### Installare l'APK sul telefono
`adb` funziona su questo PC (è nativo, non JVM):
```bash
adb install -r app-debug.apk
```

## Note tecniche
- Kotlin 2.0 + Jetpack Compose (Material 3), `minSdk 26`, `targetSdk 34`.
- Persistenza: `filesDir/diario.json` via `kotlinx.serialization`.
- Backup/ripristino via Storage Access Framework (nessun permesso di storage richiesto).
- Package: `it.davide.diario`.
