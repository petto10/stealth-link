# Architettura · Stealth Link

Stealth Link 0.3 collega il listener Android a un motore Kotlin puro e a un trasporto selezionabile. Un servizio Android di tipo `connectedDevice` mantiene il collegamento reale indipendente dall’Activity. La sessione reale si attiva dall’interfaccia e rimane soggetta alle regole del sistema e del produttore del telefono.

| Componente | Compito |
|---|---|
| `core/social` | Modello dei messaggi, filtri, formati compatto ed esteso, deduplicazione e accorpamento con scadenza. |
| `core/protocol` | Codec Social, correlazione UID, scelta dei limiti per record, frammenti e simulatore con MTU configurabile. |
| `app/ble` | Server GATT Social e client GATT delle impostazioni, identità del peer e sottoscrizioni, servizio foreground, timeout, riconnessione, diagnostica numerica e lettura delle impostazioni. |
| `PhoneNotificationListener` | Estrazione degli extra, contenuto MessagingStyle e forme alternative, esclusione di categorie e codici riconosciuti come sensibili. |
| `CompanionController` | Stato condiviso tra UI, listener e trasporti. Applica le preferenze, serializza le consegne e scarta i dati in coda sulle transizioni di privacy. |
| `app/ui` | Material 3, navigazione Home, Notifiche, Profili e Altro, risorse italiane e inglesi. |
| `app/profiles` | Profili propri in memoria locale, rinomina e profilo attivo locale. Validazione, archivio JSON versionato, backup e fusione atomica tramite selettore Android. |
| `app/configuration` | Modello dei dati restituiti dal controller e decodifica dei dati standard GATT facoltativi. |

## Compatibilità Social

Il telefono è il server GATT per Social e il client GATT per le impostazioni. Le nuove installazioni usano il testo esteso con limite iniziale di 112 byte UTF‑8 per il corpo, 25 ms fra frammenti e pagine numerate disattivate. L’aggiornamento conserva una scelta di trasporto già effettuata. Il profilo compatibile usa una risposta a singolo frame per attributo. Entrambi mantengono l’evento di aggiunta, la categoria Social e lo schema UID con otto bit variabili. Il record mantiene la modalità scelta e il server dimensiona i frame in base al proprio callback MTU. Il client delle impostazioni può chiedere un MTU quando l’opzione estesa è attiva e il client usa lo stesso peer approvato. I due canali riportano separatamente l’MTU. [Protocollo Bluetooth](protocollo-bluetooth.md)

**Connessione solo notifiche** registra il server Social e salta il client delle impostazioni. In modalità normale il client delle impostazioni ha un proprio ciclo di riconnessione. Il server resta registrato quando il client fallisce, così un tentativo delle impostazioni non ricrea il servizio Social. Sullo stesso collegamento fisico Android può comunque riportare eventi a entrambi i ruoli. L’attesa Social è passiva quando il controller è spento o sospeso. In Home, **Ripristina collegamento** ricrea il server Social, conserva la selezione e l’associazione del peer e lascia in uso il client delle impostazioni già connesso.

Il Laboratorio invia lo stesso campione A/B con due modalità di trasporto. Può cambiare il ritardo tra frammenti o inviare più notifiche sintetiche numerate. Le prove di identificatore e nome app, categorie, aggiornamento e rimozione usano campioni sintetici. Il simulatore emette le richieste e ricompone le risposte a MTU 23, 64 o 185. Apertura del feed, scorrimento, risveglio e altri comportamenti del display si misurano sul controller. [Guida A/B](prove-telefono.md#confronto-ab-a-mtu-23)

## Percorso delle notifiche

1. Il listener estrae i campi disponibili da `MessagingStyle` e dagli extra Android. Il motore applica le regole per sorgente, canale, conversazione e contatto prima di formare la notifica. Il titolo può includere il nome dell’app. Il corpo compatto conserva il messaggio più recente. Il formato esteso può includerne fino a tre, dal più recente, separati da ` · `. Conserva i ritorni a capo del testo originale. Il mittente segue il contenuto quando aggiunge informazione.
2. La coda tiene in memoria fino a 32 eventi, usa una finestra di 350 ms per accorpare gli aggiornamenti e scade il contenuto in attesa dopo 30 secondi. La deduplicazione delle conversazioni usa identificatori derivati e limitati per cinque minuti. Gli aggiornamenti sostituiscono una voce in coda conservandone l’ordine. Il tentativo di consegna di esito ambiguo rimane segnato nella deduplicazione.
3. Il trasporto invia un evento Social alla volta e conserva il record per le richieste di titolo e corpo. Le risposte rispettano il limite chiesto dal controller e il budget applicativo del record. Le eventuali pagine numerate vengono consegnate come notifiche distinte. In modalità estesa ogni risposta è divisa in frammenti ATT e inviata in ordine aspettando i callback GATT. I record ordinari scadono dopo 30 secondi. I campioni di lunghezza possono rispondere a nuove richieste fino a 120 secondi entro la stessa sessione e la capacità della cache. La visualizzazione richiede una verifica sul display.

## Protezione e diagnostica

L’app richiede un peer associato scelto dall’utente, sottoscrizioni alle caratteristiche e protezione GATT per i dati personali, sia in modalità compatta sia estesa. L’opzione legacy senza protezione dei singoli attributi è circoscritta ai campioni sintetici del Laboratorio. Le notifiche reali restano in RAM con scadenza, vengono espulse al cambio delle regole e sono oscurate o eliminate quando si blocca il telefono se la modalità riservata è attiva. Il filtro dei codici è euristico e si aggiunge alla protezione applicata da Android. La diagnostica esportata registra MTU, attributi richiesti, byte e frammenti, esiti GATT e contatori. Esclude contenuti delle notifiche, identificatori di app e notifiche, regole, nomi dei profili e indirizzi Bluetooth.

## Impostazioni e futura parità

Nell’APK 0.3 la caratteristica `FF01` riceve le interrogazioni note di slot attivo (`11 BB`), nome slot (`D1 09`) e proprietà di sistema (`D1 06`). `C2` e `C4` sono interrogazioni facoltative per gestione energia e LED di carica, ricostruite dal codice del client di riferimento. Il client usa scritture ATT solo per trasportare queste richieste di lettura. Serializza le richieste, calcola il checksum delle richieste e valida prefisso, lunghezza e slot delle risposte. Le revisioni del dispositivo e la batteria sono campi facoltativi delle caratteristiche GATT standard. La disponibilità effettiva delle letture facoltative resta da verificare sul controller. [Protocollo di lettura](protocollo-bluetooth.md#impostazioni-in-sola-lettura)

| Area | Supporto in 0.3 | Passo verso la parità |
|---|---|---|
| Profili e slot | Lettura dello slot attivo sul controller e dei nomi dei 10 slot. Creazione, rinomina, selezione attiva locale, importazione ed esportazione dei profili dell’app. | Verificare fisicamente encoding e limiti dei nomi, poi definire selezione, applicazione e persistenza sul controller. |
| Mapping e Pro‑Aim | Modello locale per P1–P4 e livello Pro‑Aim. | Confronto con il mapping del firmware e prova durante il gioco. |
| Stick e trigger | Modello locale per curve e dead zone distinte per stick e trigger. | Confrontare valori di UI e filo, intervalli validi e indipendenza degli stick. |
| Audio | Modello locale per preset EQ, volume, mix, monitoraggio e mute del microfono. | Prova con cuffie collegate e verifica delle scale per firmware. |
| Vibrazione | Modello locale per quattro motori. | Verificare identità e scala di ogni motore. |
| RGB | Modello locale per colori, effetti, velocità e intensità. Lettura del valore di luminosità restituito da `D1 06`. | Verificare MTU e formato dei 33 byte, palette ed effetti. |
| Sistema e stato dispositivo | Lettura proprietà di sistema, interrogazioni facoltative energia e LED di carica, caratteristiche GATT standard facoltative per batteria e revisioni. Modello locale dei valori programmabili. | Verificare disponibilità per firmware, scale, sospensione e rilettura. |

Il profilo attivo locale è una scelta dell’app. Lo slot attivo del controller è un dato letto dal dispositivo. Per le future scritture, ogni operazione potrà usare un piano con campi selezionati, un singolo controller e una transazione in volo. La UI dovrà presentare stato, campi confermati ed esito parziale. Dopo una disconnessione, la nuova sessione rileggerà il contesto prima di riprovare. Applicazione e persistenza dei profili richiederanno una verifica sul controller. Firmware e ripristino restano fuori dal percorso delle impostazioni.

## Dati locali

Le regole si salvano come preferenze private. Le notifiche e le bozze di test hanno vita limitata all’app in esecuzione. I profili risiedono nella directory esclusa dal backup Android. L’utente sceglie manualmente la destinazione dei file esportati. Il manifest dell’APK non richiede `INTERNET` e l’app non include SDK di telemetria.
