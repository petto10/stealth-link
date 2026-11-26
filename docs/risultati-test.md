# Risultati dei test · Stealth Link 0.3.0-alpha

Verifica locale con JDK 17, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, Android SDK API 36 e Build Tools 35.0.0.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

## Test automatici

Sono passati 70 test di `core` e 47 test dell’app. I 47 test dell’app sono stati eseguiti anche sulla variante release. Build debug e release riuscite. Android Lint ha registrato zero errori, con un avviso sulla versione del plugin Android Gradle nella variante debug.

| Area | Verifiche |
|---|---|
| Codec e simulatore | Compatibilità a 12 byte con MTU 23, budget da 23 a 517, UTF‑8, grafemi e risposte con più attributi. Vettori estesi da 254 e 255 byte in 14 frammenti, letture ripetute con limiti diversi, scadenza a 120 secondi per i campioni e riserva UID dopo espulsione. |
| Laboratorio | Tutte le 168 combinazioni di lunghezza, tipo di testo e ritardo. Marcatori finali, pagine numerate ricomposte nel simulatore, cronologia limitata e diagnostica per prova priva del testo. |
| Formato Social | Limiti in byte, confini di parola, grafemi, pagine numerate e rappresentazione del troncamento. La policy è verificata anche su 200 casi Unicode generati. La prova integrata verifica ordine delle pagine alla ricezione e soppressione dei duplicati per lo stesso aggiornamento di gruppo. |
| Trasporto BLE | MTU indipendenti, ritardo per risposta normale e sintetica, reset delle risposte parziali, timer invalidati senza effetti tardivi, attese progressive separate e conteggi mantenuti tra le riconnessioni. |
| Impostazioni e profili | Richieste di lettura ammesse, controllo di prefisso, lunghezza e intervalli delle letture facoltative. Decoder standard per batteria e revisioni. Migrazione dei profili sul telefono, rinomina, selezione persistente e importazione atomica. |

I risultati verificano le parti applicative e il flusso con un ricevitore simulato. Tempi di riconnessione, persistenza delle sottoscrizioni, consumi, comportamento durante il gioco su PC e disponibilità reale delle letture opzionali richiedono telefono e controller.

## Distribuzione

L’APK di distribuzione ha package `dev.stealthcompanion`, `versionName` `0.3.0-alpha`, `versionCode` 4, API minima 24 e target API 36. Il manifest esclude il permesso Internet. Il workflow verifica firma, impronta del certificato e allineamento prima della pubblicazione. L’APK firmato e il file dei digest SHA‑256 sono allegati alla pre-release. [Pre-release pubblica](https://github.com/petto10/stealth-link/releases/tag/v0.3.0-alpha) · [Processo di firma](firma-release.md)

## Prove fisiche

Nel riscontro riferito sulla `0.2.1-alpha`, lo Stealth Ultra ha mostrato integralmente 32 byte, ha visualizzato più righe e ha permesso lo scorrimento verticale nel dettaglio. I campioni ASCII da 128 e 255 byte si fermavano a circa 120 caratteri e i campioni multilinea dopo alcune righe. La diagnostica riferita riportava 6 consegne completate, 43 frammenti e zero interruzioni. Le foto e la diagnostica di questa tornata non erano disponibili nel workspace. La conferma sulla `0.3.0-alpha` riguarda soprattutto le pagine di notifiche reali, il recupero con lo stesso bonding e la lettura dei valori opzionali. [Guida per telefono e controller](prove-telefono.md)
