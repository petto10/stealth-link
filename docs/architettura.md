# Architettura · Stealth Link

La prima versione collega il listener Android a un motore Kotlin puro e a un trasporto selezionabile. Il simulatore riproduce la richiesta degli attributi del controller e ricostruisce la notifica inviata. Un servizio Android di tipo `connectedDevice` mantiene il collegamento reale indipendente dall’Activity. La sessione reale si attiva dall’interfaccia e rimane soggetta alle regole del sistema e del produttore del telefono.

| Componente | Compito |
|---|---|
| `core/social` | Modello dei messaggi, filtri, normalizzazione, code con scadenza, deduplicazione e accorpamento. |
| `core/protocol` | Codec Social, correlazione UID, attributi, frammenti, simulatore. |
| `app/ble` | GATT server Social e GATT client configurazione, validazione peer e sottoscrizioni, servizio foreground, timeout, riconnessione e decodifica delle sole interrogazioni impostazioni. |
| `PhoneNotificationListener` | Estrazione degli extra, contenuto MessagingStyle e forme alternative, esclusione di categorie e codici riconosciuti come sensibili. |
| `CompanionController` | Stato condiviso tra UI, listener e trasporti. Instrada i test sintetici e scarta le code sulle transizioni di privacy. |
| `app/ui` | Material 3, schermate Dispositivo, Notifiche, Laboratorio e Profili, risorse italiane e inglesi. |
| `app/profiles` | Profili propri in memoria locale. Validazione, archivio JSON versionato, backup e fusione atomica tramite selettore Android. |

## Compatibilità Social

Il server usa gli UUID ANCS sul telefono. La forma legacy usa evento di aggiunta, categoria Social, UID con otto bit variabili e una risposta per attributo. Il codec considera MTU, capacità legacy e limite chiesto dal controller. Combina ricomposizione delle risposte, UID più ampi, categorie, presenza dell’identificatore e nuovi eventi solamente nelle prove che attivano l’opzione corrispondente. Il simulatore è utile per verificare ciò che il telefono emette e ciò che un client compatibile ricostruisce. Il rendering dello Stealth Ultra resta una misura fisica.

L’app richiede un peer associato scelto dall’utente, sottoscrizioni alle caratteristiche e protezione GATT per i dati personali. La modalità di laboratorio usa contenuti sintetici. Le notifiche reali restano in RAM con scadenza, vengono espulse al cambio delle regole e sono oscurate o eliminate quando si blocca il telefono se la modalità riservata è attiva. Il filtro dei codici è euristico e si aggiunge alla protezione applicata da Android. Applicazioni, regole e nomi dei profili non fanno parte della diagnostica esportata.

## Impostazioni e futura parità

Nell’APK corrente la caratteristica `FF01` riceve solo le interrogazioni note di slot attivo (`11 BB`), nome slot (`D1 09`) e proprietà di sistema (`D1 06`). ATT usa un’operazione di scrittura per trasportare la richiesta di lettura. Il comando applicativo interroga il controller senza alterare i preset. Il client serializza le richieste, calcola il checksum delle richieste e valida prefisso, lunghezza e slot atteso delle risposte, e conserva un’indicazione separata dello stato parziale. L’eventuale checksum di risposta richiede una verifica sulle catture fisiche.

| Area futura | Modello locale già predisposto | Prova necessaria |
|---|---|---|
| Profili | Nomi, identità locale, opzioni profonde e formato versionato. | Conferma dei 10 slot, encoding e limiti del nome, sequenza di selezione e persistenza. |
| Mapping e Pro‑Aim | Mappatura paddles e intensità. | Confronto con i mapping offerti dal firmware e prova durante il gioco. |
| Stick e trigger | Risposta e dead zone distinte. | Confronto dei valori di UI e filo, intervalli validi, indipendenza dei due stick. |
| Audio | EQ, livello, mute, mix e monitoraggio. | Conferma con cuffie collegate e verifica delle scale per firmware. |
| Vibrazione | Quattro valori di motore. | Verifica identità e scala di ogni motore. |
| RGB | Colore, effetto, velocità e intensità. | Conferma MTU e formato dei 33 byte; misure separate per palette ed effetti. |
| Sistema | Modalità, luminosità, timeout, LED di carica. | Rilettura, verifica sleep/reconnect e persistenza. |

Quando le scritture saranno autorizzate, ciascuna operazione potrà usare un piano immutabile con campi selezionati, un singolo controller e una sola transazione in volo. La UI dovrà presentare lo stato di trasferimento, i campi confermati e l’esito parziale separatamente. Il commit delle impostazioni va verificato sul controller, con una rilettura quando possibile. Dopo una disconnessione, la nuova sessione rilegge il contesto prima di prendere in considerazione un altro tentativo. Aggiornamenti firmware e ripristino richiedono un’autorizzazione distinta.

## Dati locali

Le regole si salvano come preferenze private. Le notifiche e le bozze di test hanno vita limitata all’app in esecuzione. I profili risiedono nella directory esclusa dal backup Android. L’utente sceglie manualmente la destinazione dei file esportati. Il manifest dell’APK non richiede `INTERNET` e l’app non include SDK di telemetria.
