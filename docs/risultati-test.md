# Risultati dei test · Stealth Link 0.3.0-alpha

Verifica locale con JDK 17, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, Android SDK API 36 e Build Tools 35.0.0.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

## Test automatici

Sono passati 77 test di `core` e 49 test dell’app. I 49 test dell’app sono stati eseguiti anche sulla variante release. Build debug e release riuscite. Android Lint ha registrato zero errori, con un avviso sulla versione del plugin Android Gradle nella variante debug.

| Area | Verifiche |
|---|---|
| Codec e simulatore | Compatibilità a 12 byte con MTU 23, budget da 23 a 517, UTF‑8, grafemi e risposte con più attributi. Vettori estesi da 254 e 255 byte in 14 frammenti, letture ripetute con limiti diversi, scadenza a 120 secondi per i campioni e riserva UID dopo espulsione. |
| Laboratorio | Tutte le 168 combinazioni di lunghezza, tipo di testo e ritardo. I campioni ASCII e multilinea coincidono tranne la sostituzione di spazi con ritorni a capo. Marcatori finali, cronologia limitata e diagnostica per prova priva del testo. |
| Formato Social | Limite iniziale di 110 byte, confini di parola, grafemi, titoli numerati con mittente, righe compatte e rappresentazione del troncamento. La policy è verificata anche su 200 casi Unicode generati. La prova integrata verifica ordine delle pagine e soppressione dei duplicati per un aggiornamento di gruppo. |
| Consegna a pagine | UID distinti e ordine con un ricevitore simulato che mostra per prima la voce più recente. Alla prima consegna incerta si ferma la sequenza e non si ripete una parte già inviata. |
| Trasporto BLE | MTU indipendenti, ritardo per risposta normale e sintetica, reset delle risposte parziali, timer invalidati senza effetti tardivi, attese progressive separate e conteggi mantenuti tra le riconnessioni. |
| Impostazioni e profili | Richieste di lettura ammesse, controllo di prefisso, lunghezza e intervalli delle letture facoltative. Decoder standard per batteria e revisioni. Migrazione dei profili sul telefono, rinomina, selezione persistente e importazione atomica. |

I risultati verificano le parti applicative e il flusso con un ricevitore simulato. Tempi di riconnessione, persistenza delle sottoscrizioni, consumi, comportamento durante il gioco su PC e disponibilità reale delle letture opzionali richiedono telefono e controller.

## Distribuzione

L’APK di distribuzione ha package `dev.stealthcompanion`, `versionName` `0.3.0-alpha`, `versionCode` 4, API minima 24 e target API 36. Il manifest esclude il permesso Internet. Il workflow verifica firma, impronta del certificato e allineamento prima della pubblicazione. L’APK firmato e il file dei digest SHA‑256 sono allegati alla pre-release. [Pre-release pubblica](https://github.com/petto10/stealth-link/releases/tag/v0.3.0-alpha) · [Processo di firma](firma-release.md)

## Prove fisiche

Nel riscontro sulla `0.2.1-alpha`, l’utente ha letto per intero 32 byte e ha osservato testo multilinea e scorrimento verticale nel dettaglio. Le foto del dettaglio mostrano i campioni ASCII fino alle sezioni 17 e 18 circa. I numeri corrispondono a un prefisso di circa 120 caratteri del campione, senza un ID che associ ogni foto a una prova precisa. Il log conferma 6 consegne GATT completate e zero interruzioni, con 673 byte di attributi e 769 byte complessivi in 43 frammenti. Le risposte del corpo da 32, 64, 128 e 255 byte sono state completate lato Android. Il confronto a parità di campione e ritardo serve per isolare l’effetto degli a capo. La conferma sulla `0.3.0-alpha` riguarda soprattutto l’ordine e la capienza del feed a pagine, il recupero con lo stesso bonding e la disponibilità delle letture opzionali. [Guida per telefono e controller](prove-telefono.md)
