# Architettura · Stealth Link

Stealth Link 0.2 collega il listener Android a un motore Kotlin puro e a un trasporto selezionabile. Un servizio Android di tipo `connectedDevice` mantiene il collegamento reale indipendente dall’Activity. La sessione reale si attiva dall’interfaccia e rimane soggetta alle regole del sistema e del produttore del telefono.

| Componente | Compito |
|---|---|
| `core/social` | Modello dei messaggi, filtri, formati compatto ed esteso, deduplicazione e accorpamento con scadenza. |
| `core/protocol` | Codec Social, correlazione UID, scelta dei limiti per record, frammenti e simulatore con MTU configurabile. |
| `app/ble` | Server GATT Social e client GATT delle impostazioni, identità del peer e sottoscrizioni, servizio foreground, timeout, riconnessione, diagnostica numerica e lettura delle impostazioni. |
| `PhoneNotificationListener` | Estrazione degli extra, contenuto MessagingStyle e forme alternative, esclusione di categorie e codici riconosciuti come sensibili. |
| `CompanionController` | Stato condiviso tra UI, listener e trasporti. Applica le preferenze, serializza le consegne e scarta i dati in coda sulle transizioni di privacy. |
| `app/ui` | Material 3, schermate Dispositivo, Notifiche, Laboratorio e Profili, risorse italiane e inglesi. |
| `app/profiles` | Profili propri in memoria locale. Validazione, archivio JSON versionato, backup e fusione atomica tramite selettore Android. |

## Compatibilità Social

Il telefono è il server GATT per Social e il client GATT per le impostazioni. La forma Social predefinita usa un evento di aggiunta, la categoria Social, un UID con otto bit variabili e una risposta a singolo frame per attributo. L’opzione persistente **Testo esteso (sperimentale)** permette di inviare più frammenti, mantenendo evento e schema dell’UID. La scelta del trasporto si fissa alla creazione del record. Il server dimensiona i frame in base al proprio callback MTU e usa 23 in attesa del valore negoziato. Il client delle impostazioni tenta una richiesta MTU quando l’opzione è attiva e la sessione delle impostazioni è già connessa allo stesso peer approvato. Il client e il server riportano i propri MTU separatamente. [Protocollo Bluetooth](protocollo-bluetooth.md)

Il Laboratorio invia lo stesso campione A/B con due modalità di trasporto. Le prove di identificatore e nome app, categorie, aggiornamento e rimozione usano campioni sintetici. Il simulatore emette le richieste e ricompone le risposte a MTU 23, 64 o 185. Apertura del feed, scorrimento, risveglio e altri comportamenti del display si misurano sul controller. [Guida A/B](prove-telefono.md#confronto-ab-a-mtu-23)

## Percorso delle notifiche

1. Il listener estrae i campi disponibili da `MessagingStyle` e dagli extra Android. Il motore applica le regole per sorgente, canale, conversazione e contatto prima di formare la notifica. Il titolo può includere il nome dell’app. Il corpo compatto conserva il messaggio più recente. Il formato esteso può includerne fino a tre, dal più recente.
2. La coda tiene in memoria fino a 32 eventi, usa una finestra di 350 ms per accorpare gli aggiornamenti e scade il contenuto in attesa dopo 30 secondi. La deduplicazione delle conversazioni usa identificatori derivati e limitati per cinque minuti. Gli aggiornamenti sostituiscono una voce in coda conservandone l’ordine. Il tentativo di consegna di esito ambiguo rimane segnato nella deduplicazione.
3. Il trasporto invia un evento Social alla volta e conserva il record per le richieste di titolo e corpo. Le risposte rispettano sia il limite chiesto dal controller sia quello applicativo. In modalità estesa ogni risposta è divisa in frammenti ATT e inviata in ordine aspettando i callback GATT. Il contenuto del record scade. La visualizzazione è verificata sul display.

## Protezione e diagnostica

L’app richiede un peer associato scelto dall’utente, sottoscrizioni alle caratteristiche e protezione GATT per i dati personali, sia in modalità compatta sia estesa. L’opzione legacy senza protezione dei singoli attributi è circoscritta ai campioni sintetici del Laboratorio. Le notifiche reali restano in RAM con scadenza, vengono espulse al cambio delle regole e sono oscurate o eliminate quando si blocca il telefono se la modalità riservata è attiva. Il filtro dei codici è euristico e si aggiunge alla protezione applicata da Android. La diagnostica esportata registra MTU, attributi richiesti, byte e frammenti, esiti GATT e contatori. Esclude contenuti delle notifiche, identificatori di app e notifiche, regole, nomi dei profili e indirizzi Bluetooth.

## Impostazioni e futura parità

Nell’APK corrente la caratteristica `FF01` riceve solo le interrogazioni note di slot attivo (`11 BB`), nome slot (`D1 09`) e proprietà di sistema (`D1 06`). ATT usa un’operazione di scrittura per trasportare la richiesta di lettura. Il comando applicativo interroga il controller senza alterare i preset. Il client serializza le richieste, calcola il checksum delle richieste e valida prefisso, lunghezza e slot atteso delle risposte, e conserva un’indicazione separata dello stato parziale. L’eventuale checksum di risposta richiede una verifica sulle catture fisiche.

| Area futura | Modello locale già predisposto | Prova necessaria |
|---|---|---|
| Profili | Nomi, identità locale, opzioni profonde e formato versionato. | Conferma dei 10 slot, encoding e limiti del nome, sequenza di selezione e persistenza. |
| Mapping e Pro‑Aim | Mappatura paddles e intensità. | Confronto con i mapping offerti dal firmware e prova durante il gioco. |
| Stick e trigger | Risposta e dead zone distinte. | Confronto dei valori di UI e filo, intervalli validi, indipendenza dei due stick. |
| Audio | EQ, livello, mute, mix e monitoraggio. | Conferma con cuffie collegate e verifica delle scale per firmware. |
| Vibrazione | Quattro valori di motore. | Verifica identità e scala di ogni motore. |
| RGB | Colore, effetto, velocità e intensità. | Conferma MTU e formato dei 33 byte, con misure distinte per palette ed effetti. |
| Sistema | Modalità, luminosità, timeout, LED di carica. | Rilettura, verifica sleep/reconnect e persistenza. |

Quando le scritture saranno autorizzate, ciascuna operazione potrà usare un piano immutabile con campi selezionati, un singolo controller e una sola transazione in volo. La UI dovrà presentare lo stato di trasferimento, i campi confermati e l’esito parziale separatamente. Il commit delle impostazioni va verificato sul controller, con una rilettura quando possibile. Dopo una disconnessione, la nuova sessione rilegge il contesto prima di prendere in considerazione un altro tentativo. Aggiornamenti firmware e ripristino richiedono un’autorizzazione distinta.

## Dati locali

Le regole si salvano come preferenze private. Le notifiche e le bozze di test hanno vita limitata all’app in esecuzione. I profili risiedono nella directory esclusa dal backup Android. L’utente sceglie manualmente la destinazione dei file esportati. Il manifest dell’APK non richiede `INTERNET` e l’app non include SDK di telemetria.
