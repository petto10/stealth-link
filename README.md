# Stealth Link

[English](README.en.md)

Companion Android indipendente per **Turtle Beach Stealth Ultra**. La versione `0.2.1-alpha` aggiunge prove di lunghezza e ritmo per capire quanto testo il Command Display mostra nel feed e nel dettaglio. Il progetto usa Kotlin, Jetpack Compose e Material 3, con interfaccia in italiano e inglese e tema di sistema, chiaro o scuro.

## Cosa include l’alpha

- Inoltro su consenso per qualsiasi app che Android renda accessibile a `NotificationListenerService`. Filtri per canale, contatto e conversazione, gestione di `MessagingStyle`, deduplicazione, accorpamento e avvisi generici a telefono bloccato.
- Messaggi sintetici con titolo e testo modificabili, simulatore con ricomposizione degli attributi e Laboratorio per osservare il comportamento sul display fisico.
- Trasporto Social protetto a singolo frame come impostazione iniziale. La modalità estesa è sperimentale, richiede un collegamento protetto e può suddividere il testo in frammenti ATT.
- Laboratorio con campioni di lunghezza nota, codifiche separate, ritmo tra i frammenti e prova facoltativa di notifiche brevi numerate. Modalità **Connessione solo notifiche** per provare Social senza aprire il client delle impostazioni.
- Sessione BLE tramite un servizio `connectedDevice`, selezione di dispositivi già associati, tentativi di riconnessione e diagnostica esportabile senza contenuti personali.
- Lettura di slot attivo, nomi dei dieci slot e proprietà di sistema. Creazione, importazione ed esportazione di profili locali in formato JSON versionato.

Nella prova fisica con `0.2.0-alpha`, il feed Social ha mostrato più testo in modalità estesa a MTU 23. Due risposte per il corpo hanno completato 254 byte di testo via GATT. La lunghezza nel dettaglio e lo scorrimento restano da misurare. Il collegamento legacy del Laboratorio ammette soltanto messaggi sintetici. Le interrogazioni del controller sono in sola lettura. [Riscontro fisico e procedura A/B](docs/prove-telefono.md#confronto-ab-a-mtu-23)

## Installa e prova

Serve Android 7.0 o successivo. Scarica l’APK firmato dagli **Assets** della pre-release e apri **Dispositivo → Simulatore**. In **Laboratorio → Messaggio di prova** inserisci titolo e testo e tocca **Invia prova**. Il simulatore sintetico funziona senza permessi. Per verificare la raccolta delle notifiche userai l’accesso concesso nelle Impostazioni Android. Per la prova fisica associa prima il controller dal menu **App Link** e segui la procedura della guida. [Pre-release GitHub](https://github.com/petto10/stealth-link/releases) · [Guida alle prove](docs/prove-telefono.md)

Le notifiche sono elaborate sul telefono. Il manifest dell’APK esclude il permesso Internet e il progetto è privo di SDK di telemetria. I profili locali e la diagnostica sono esportati nella destinazione scelta dall’utente.

## Build e test

Usa JDK 17, Android SDK Platform 36 e Android Build Tools 35.0.0. Configura `ANDROID_HOME` oppure apri il progetto in Android Studio e imposta la posizione dell’SDK. La prima build scarica Gradle e le dipendenze Maven.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

L’APK di debug si trova in `app/build/outputs/apk/debug/app-debug.apk`, ha identificativo `dev.stealthcompanion` e usa la chiave di debug della macchina che lo ha compilato. Si installa accanto all’app ufficiale. Durante le prove fisiche lascia attiva una sola sessione companion alla volta.

## Documentazione

- [Guida per telefono e controller](docs/prove-telefono.md)
- [Protocollo Bluetooth](docs/protocollo-bluetooth.md)
- [Architettura e piano per le impostazioni](docs/architettura.md)
- [Risultati dei test](docs/risultati-test.md)
- [Firma Android e pubblicazione](docs/firma-release.md)
- [Changelog](CHANGELOG.md)
- [Note della versione 0.2.1-alpha](docs/releases/v0.2.1-alpha.md)

Stealth Link è un progetto indipendente. I nomi Turtle Beach e Stealth Ultra identificano il produttore e il dispositivo a cui è destinata la companion.
