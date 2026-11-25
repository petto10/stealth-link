# Risultati dei test · Stealth Link 0.2.0-alpha

Verifica locale con JDK 17, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, Android SDK API 36 e Build Tools 35.0.0.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

## Test automatici

Sono passati 47 test di `core` e 24 test dell’app. I 24 test dell’app sono stati eseguiti anche sulla variante release. Build debug e release riuscite. Android Lint ha registrato zero errori, con sei avvisi sulle versioni delle dipendenze nella variante debug.

| Area | Verifiche |
|---|---|
| Codec e simulatore | Riscontro `Caffè pront` a MTU 23, budget da 23 a 517, limiti richiesti incluso zero, grafemi, emoji, ricomposizione UTF‑8, risposte con più attributi e UID legacy. |
| Coda Social | Ordine, accorpamento, scadenza, filtri, aggiornamenti sovrapposti di gruppo e inoltro di due messaggi identici con identità distinte. |
| Trasporto BLE | Ammissibilità della richiesta MTU lato client, fallback Social, invio di un frame alla volta, reset della coda dopo una risposta interrotta, capacità e diagnostica numerica dei dati e del nome app sintetico. |
| Impostazioni e profili | Interrogazioni di lettura ammesse, parsing delle risposte, salvataggio e importazione atomica dei profili locali. |

I test coprono il flusso con un ricevitore simulato. I callback Bluetooth, la gestione del servizio in background e l’effetto sul display richiedono telefono e controller.

## Distribuzione

L’APK di distribuzione ha package `dev.stealthcompanion`, `versionName` `0.2.0-alpha`, `versionCode` 2, API minima 24 e target API 36. Il manifest non richiede il permesso Internet. Il workflow della pre-release verifica firma, impronta del certificato e allineamento prima della pubblicazione. L’APK firmato e il file dei digest SHA‑256 sono allegati alla pre-release. [Pre-release pubblica](https://github.com/petto10/stealth-link/releases/tag/v0.2.0-alpha) · [Processo di firma](firma-release.md)

## Prove fisiche

La prova con `0.1.0-alpha` ha confermato connessione, notifiche, menu Social e visualizzazione di caratteri accentati. Con MTU 23 sono stati osservati il titolo `Notifica ...` e il corpo `Caffè pront`, anche nel dettaglio. La nuova prova A/B serve a verificare la ricomposizione sul firmware e la quantità di testo effettivamente visibile. La guida include prove di blocco schermo, sospensione del controller, riconnessione e funzionalità sperimentali del display. [Guida per telefono e controller](prove-telefono.md#confronto-ab-a-mtu-23)
