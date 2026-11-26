# Risultati dei test · Stealth Link 0.2.1-alpha

Verifica locale con JDK 17, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, Android SDK API 36 e Build Tools 35.0.0.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

## Test automatici

Sono passati 57 test di `core` e 33 test dell’app. I 33 test dell’app sono stati eseguiti anche sulla variante release. Build debug e release riuscite. Android Lint ha registrato zero errori, con sei avvisi sulle versioni delle dipendenze nella variante debug.

| Area | Verifiche |
|---|---|
| Codec e simulatore | Compatibilità a 12 byte con MTU 23, budget da 23 a 517, UTF‑8, grafemi e risposte con più attributi. Vettori estesi da 254 e 255 byte in 14 frammenti, letture ripetute con limiti diversi, scadenza a 120 secondi per i campioni e riserva UID dopo espulsione. |
| Laboratorio | Tutte le 168 combinazioni di lunghezza, tipo di testo e ritardo. Marcatori finali, pagine numerate ricomposte nel simulatore, cronologia limitata e diagnostica per prova priva del testo. |
| Coda Social | Ordine, accorpamento, scadenza, filtri e messaggi di gruppo. Il formato esteso preserva i ritorni a capo scritti nel testo e riduce la ripetizione di app e mittente. |
| Trasporto BLE | MTU indipendenti, invio di un frame alla volta, ritardo dal callback precedente, reset se una risposta in corso viene interrotta, attese progressive separate e conteggi conservati quando cambiano gli stati dei collegamenti. |
| Impostazioni e profili | Interrogazioni di lettura ammesse, parsing delle risposte, salvataggio e importazione atomica dei profili locali. |

I risultati verificano il flusso con un ricevitore simulato. L’accettazione del ritmo sul firmware, il recupero dopo lo spegnimento e l’effetto sul display richiedono telefono e controller.

## Distribuzione

L’APK di distribuzione ha package `dev.stealthcompanion`, `versionName` `0.2.1-alpha`, `versionCode` 3, API minima 24 e target API 36. Il manifest esclude il permesso Internet. Il workflow verifica firma, impronta del certificato e allineamento prima della pubblicazione. L’APK firmato e il file dei digest SHA‑256 sono allegati alla pre-release. [Pre-release pubblica](https://github.com/petto10/stealth-link/releases/tag/v0.2.1-alpha) · [Processo di firma](firma-release.md)

## Prove fisiche

Le prove con `0.1.0-alpha` e `0.2.0-alpha` hanno confermato connessione, ricezione, menu Social e accenti. Il feed esteso di `0.2.0-alpha` ha mostrato `Caffè pronto · già qui`. Il feed e il dettaglio compatibili mostravano `Caffè pront`. Due risposte del corpo esteso hanno prodotto 14 callback riusciti ciascuna per 254 byte di testo. La lettura integrale nel dettaglio resta da verificare con campioni graduati e richieste marcate. I log di `0.2.0-alpha` mostrano disconnessioni anche fuori dagli invii frammentati. La modalità Solo notifiche consente un confronto sullo stesso telefono e firmware. [Guida per telefono e controller](prove-telefono.md#confronto-ab-a-mtu-23)
