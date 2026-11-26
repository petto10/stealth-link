# Prove su Android e Turtle Beach Stealth Ultra

Questa guida copre la versione 0.3.0-alpha di Stealth Link. Il simulatore verifica gli scambi applicativi. Le prove sul controller misurano il collegamento Bluetooth, la compatibilità con il firmware installato e la resa sul Command Display.

## 1. Installa e prova il simulatore

1. Scarica sul telefono l’APK firmato allegato alla pre-release e aprilo. Per le prove di sviluppo puoi usare l’APK di debug compilato localmente. Se hai già una build debug installata, esporta i profili prima di rimuoverla e passare all’APK firmato. Se Android chiede l’autorizzazione a installare da quella sorgente, concedila al gestore file usato per aprire l’APK. Con Android Platform Tools e Debug USB, puoi installarlo anche con `adb install -r /percorso/dell-apk.apk`.
2. Apri **Stealth Link → Home → Simulatore**. In **Altro → Laboratorio**, imposta titolo e corpo in **Messaggio di prova** e premi **Invia prova**. Il simulatore è utilizzabile senza autorizzazioni Bluetooth o accesso alle notifiche.
3. Usa prima `Prova 01` e `Ciao`, poi un testo come `Caffè, perché, più — 👩🏽‍💻 ☕ 日本語` con un ritorno a capo. Controlla l’anteprima e i contatori. L’anteprima mostra il testo della prova. Un invio simulato conferma che il codice ha elaborato il campione, mentre la resa dei glifi e la lunghezza visualizzabile richiedono il display fisico.
4. In **Altro → Diagnostica → Esporta diagnostica**, salva il file sul telefono. Controlla che contenga stati e contatori. Scegli una destinazione locale se vuoi che il file resti sul telefono.
5. In **Altro → Laboratorio → Prove del protocollo**, abilita le prove sperimentali e scegli **MTU ATT simulato** 23. Apri **Testo più lungo**, seleziona 32 byte, ASCII e 0 ms, quindi invia A e B e confronta i byte decodificati e il marcatore finale. Ripeti con MTU 64 e 185. L’anteprima mostra il testo ricomposto dal simulatore.

Per verificare anche il listener, apri **Home → Accesso alle notifiche** e abilita Stealth Link nelle impostazioni Android. Su alcuni telefoni Android applica restrizioni aggiuntive alle app di origine esterna. Se l’accesso risulta disabilitato, controlla **Impostazioni → App → Stealth Link** e la voce **Consenti impostazioni con restrizioni**, se presente. Concedila soltanto dopo aver verificato l’origine dell’APK. [Guida Android alle impostazioni con restrizioni](https://support.google.com/android/answer/12623953)

## 2. Associa lo Stealth Ultra

1. Annota modello del telefono, versione Android, firmware controller e modalità di gioco usata durante la prova. Leggi la versione installata dal controller o da una schermata informativa del produttore.
2. Sul controller apri **App Link** e avvia l’associazione. Apri le impostazioni Bluetooth del telefono e associa la voce corrispondente. Il manuale riporta il nome `STEALTH ANCS` per App Link. Il canale impostazioni può apparire come `STEALTH ULTRA APP`. Un’associazione per il solo uso come gamepad può apparire con un nome diverso. [Guida Turtle Beach Stealth Ultra](https://manuals.plus/m/0faa5a2e652a2d99fa99066601e38e1cbab0aa1b4cb5610c31ff02d8ae52e15c)
3. Termina un’eventuale sessione attiva dell’app ufficiale sul telefono, così puoi attribuire ogni evento a una sola companion. Se vuoi usare l’app ufficiale come riferimento, esegui i due giri di prova separatamente.
4. In Stealth Link seleziona **Home → Controller** e concedi il permesso **Dispositivi nelle vicinanze** quando Android lo richiede. Premi **Aggiorna** per rileggere le associazioni. Seleziona il dispositivo destinato alle impostazioni e, se App Link ha una voce associata separata, sceglila in **Collegamento Social**. Se entrambi i ruoli usano la stessa voce associata, usa **Usa lo stesso dispositivo**.
5. Tocca **Avvia connessione** con l’interfaccia aperta. Android può mostrare una notifica di servizio. Se il sistema chiede anche il permesso di mostrare le notifiche dell’app, concedilo per avere il controllo della sessione nell’area notifiche. Lo stato **Social connesso** richiede che il controller sottoscriva le caratteristiche del server locale. Il canale delle impostazioni può avere uno stato diverso.
6. Invia `Baseline 01` e `Messaggio sintetico` da **Altro → Laboratorio**, poi apri il feed **Social** del controller. Verifica titolo e corpo, e annota se sono comparsi da soli o solo dopo aver aperto il feed.

L’inoltro di contenuti delle app richiede l’associazione del controller e la sottoscrizione protetta secondo la policy del collegamento. Un nome Bluetooth o una connessione al canale impostazioni non bastano. Se lo stato rimane in **Attesa di Social**, controlla App Link, le voci associate e il Bluetooth del telefono. Esporta la diagnostica con lo stato raggiunto. Nel Laboratorio puoi abilitare il collegamento legacy solo per campioni sintetici, mantenendo il peer già associato e approvato. Torna alla modalità protetta prima di inoltrare notifiche delle app. [Android Bluetooth in background](https://developer.android.com/develop/connectivity/bluetooth/ble/background) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

### Confronto A/B a MTU 23

Nella prova fisica con `0.1.0-alpha` il controller si è collegato, ha ricevuto notifiche e le ha rese accessibili nel menu Social. Gli accenti erano leggibili. Il titolo visualizzato era `Notifica ...` e il corpo `Caffè pront`, anche aprendo la notifica. La diagnostica ha registrato `MTU=23`, `social_ready=true`, `settings_connected=true`, `transport_completed=3` e `transport_drops=0`. Con MTU 23 il singolo frame ha spazio per 12 byte di testo dopo l’header della risposta. `Caffè pront` occupa 12 byte in UTF-8, in accordo con il limite del trasporto usato nella prima versione.

Con `0.2.0-alpha`, la prima foto del feed esteso mostra il titolo abbreviato `Notifica estes...` e il corpo `Caffè pronto · già qui`. Nella seconda foto, il feed in modalità compatibile mostra `Notifica ...` e `Caffè pront`. Le due viste del feed riportano `1 OF 1`. La terza foto mostra il dettaglio della notifica compatibile con lo stesso troncamento e il comando `BACK B`. L’utente ha riferito un troncamento anche all’apertura della notifica estesa. Il resoconto successivo sulla 0.2.1 descrive l’apertura e lo scorrimento del dettaglio.

Nella stessa serie di prove, il controller ha chiesto 255 byte per titolo e corpo nelle richieste registrate. Due risposte estese del corpo hanno completato 254 byte di testo ciascuna. I 14 frammenti per risposta sono 13 da 20 byte e uno da 2. Sottraendo gli 8 byte di header dai 262 complessivi si ottengono 254 byte. Tra il primo e l’ultimo callback dei frammenti sono trascorsi 43 ms e 83 ms. Il client impostazioni ha riportato MTU 247 anche prima di una richiesta esplicita. Per Social il server ha usato 23 come valore ATT iniziale. Nel log le richieste di titolo e corpo non si ripetono per la stessa consegna e manca un marcatore di apertura del dettaglio. Quanto il firmware ha conservato o mostrato si misura sul controller.

#### Riscontro 0.2.1 nel log e nelle foto

Il log della 0.2.1 documenta 6 consegne GATT completate, 0 interruzioni, 0 scarti e 43 frame Data Source. Il controller ha chiesto 255 byte per ciascuno dei 12 attributi. L’app ha dichiarato e inviato 673 byte di testo. I 769 byte Data Source includono 96 byte di header, 8 per risposta. Per Social il server ha usato il valore ATT iniziale 23, mentre il client impostazioni ha riportato 247 anche dopo la richiesta 517.

| Prova del Laboratorio | Intervallo impostato | Titolo inviato | Corpo richiesto | Corpo dichiarato e inviato | Frame del corpo |
|---|---:|---:|---:|---:|---:|
| 1 · ASCII 32 | 25 ms | 9 byte | 255 byte | 32 byte | 2 |
| 2 · Più righe 64 | 0 ms | 9 byte | 255 byte | 64 byte | 4 |
| 3 · ASCII 255 | 25 ms | 10 byte | 255 byte | 255 byte | 14 |
| 4 · ASCII 128 | 25 ms | 10 byte | 255 byte | 128 byte | 7 |

Ogni titolo della tabella ha usato un frame. Le altre due consegne registrano 17 byte di titolo in due frame e 61 di corpo in quattro frame ciascuna. Nelle prove ASCII lunghe l’intervallo tra callback e invio del frame successivo è stato 27–31 ms a 255 byte e 27–29 ms a 128 byte. Il marcatore del dettaglio della prova 2 cade 68,655 secondi dopo il completamento del corpo. Dopo il marcatore il log non contiene un’altra richiesta per quella prova. Le quattro chiusure Social hanno motivo `TRANSPORT_CLOSED` e coincidono con cambi del Laboratorio o arresti prima delle consegne. Il log non riporta retry o disconnessioni del client impostazioni.

L’utente riferisce di aver letto tutto il campione da 32 byte e di aver verificato ritorni a capo e scorrimento nel dettaglio. Nelle due foto il display mostra `SOCIAL` e `BACK B`. Le ultime sequenze leggibili attorno a `17 abc` e all’inizio di `18` collocano il limite visibile attorno a 120 caratteri per l’ASCII del campione. Foto e log non permettono di assegnare ogni immagine a una prova precisa o di identificare un limite interno del firmware. Il campione su più righe era visibile fino a una parte delle righe secondo il resoconto.

Il confronto tra 64 byte su più righe e 128/255 ASCII cambiava sia lunghezza sia intervallo. Per misurare l’effetto dei ritorni a capo, ripeti le prove isolate più sotto. I 110 byte iniziali per pagina lasciano margine rispetto alla soglia ASCII osservata.

Ripeti il confronto su `0.3.0-alpha` con lo stesso telefono, controller, voci Bluetooth approvate e stato di gioco. Nella prova documentata il PC era spento secondo il resoconto dell’utente. Registra modello e firmware, evitando di trascrivere gli indirizzi dei dispositivi.

1. Mantieni il collegamento Social protetto. Attendi **Social connesso** e annota l’MTU Social. Se l’app lo indica come valore ATT iniziale, 23 è il valore usato per preparare i frame e manca un aggiornamento dal callback del server. Mantieni fissi timeout, luminosità e stato del display nel corso del confronto.
2. In **Altro → Laboratorio → Esperimenti sul firmware**, attiva **Abilita le prove sperimentali** e apri **Scorrimento** per riprovare il campione della 0.2.0. Lascia disabilitato il collegamento legacy per soli campioni sintetici.
3. Premi **Invia A · Compatibilità**. Annota titolo e ultima sezione numerata visibili sia nel feed sia nel dettaglio. Salva i valori diagnostici del giro.
4. Premi **Invia B · Con frammentazione**. I due pulsanti usano lo stesso campione, che inizia con `Caffè pronto · già qui 🎮` e prosegue con 12 righe numerate. La sessione rimane attiva e la preferenza delle notifiche ordinarie resta intatta. Ripeti la misura. Se la sessione mostra un MTU diverso, registralo come prova separata e ripeti il confronto a MTU uguale quando possibile.
5. Ripeti A, B e ancora A. Durante ciascun giro verifica separatamente la navigazione tra notifiche e lo scorrimento del testo. Esegui timeout o riconnessione in un ciclo separato.
6. Confronta il numero di frammenti, le dimensioni in byte, l’esito del trasporto e la lunghezza visibile. Usa la differenza tra contatori iniziali e finali se l’esportazione contiene valori cumulativi. Più frammenti inviati con esito GATT riuscito indicano trasmissione sul collegamento. Titolo o corpo più lunghi sul display documentano la ricomposizione del campione provato.

| Giro | Modalità | MTU Social | Lunghezza richiesta ed emessa | Frammenti | Esito GATT | Ultimi caratteri visibili |
|---|---|---:|---|---:|---|---|
| A | Singolo frame | | | | | |
| B | Estesa | | | | | |
| A | Singolo frame | | | | | |

Se il controller richiede 12 byte in entrambi i giri, l’app rispetta quel limite anche nel giro B. Se B invia e completa più di 12 byte ma il testo visibile rimane identico, annota il limite osservato sia nel feed sia nel dettaglio. L’ultima riga visibile e l’esito GATT sono misure separate.

In **Notifiche → Formato sul controller** le nuove installazioni partono da **Testo esteso (consigliato)**, 25 ms fra frammenti, 110 byte per pagina e pagine numerate attive, con limite iniziale di quattro. L’aggiornamento conserva la scelta esplicita precedente fra compatibilità e testo esteso. La suddivisione richiede anche il permesso **Consenti pagine numerate per questa app** nella regola dell’app, attivo inizialmente. **Righe compatte** e **Invia prima l’ultima pagina** sono opzioni distinte, disattivate inizialmente. Disattiva le prove sperimentali nel Laboratorio prima di generare avvisi Android.

1. Per confrontare una pagina singola con più pagine, usa un lungo avviso di prova da un’app autorizzata e disattiva **Dividi in pagine numerate**. Annota l’ultima parte visibile. Il corpo abbreviato termina con `...` quando supera il budget.
2. Riattiva la suddivisione e genera un nuovo messaggio di prova equivalente. Controlla i numeri `i/n`, il mittente nel titolo quando disponibile e la permanenza di ogni voce. Con **Massimo di pagine per notifica** puoi impostare da due a quattro pagine. Usa la regola dell’app per disattivare le pagine solo per quella sorgente.
3. Se il controller mostra prima gli avvisi più recenti, prova **Invia prima l’ultima pagina**. Verifica che l’ordine fisico faccia comparire la pagina 1 per prima. In una prova separata attiva **Righe compatte** per misurare se il testo su una riga risulta più leggibile. I campioni del Laboratorio mantengono durata, lunghezza e intervallo propri.
4. Se una disconnessione interrompe una sequenza, verifica quali pagine sono già visibili. La consegna si ferma alla prima pagina con esito incerto. Dopo la riconnessione puoi creare un nuovo avviso di prova per confrontare la resa.

In un giro dedicato verifica il tentativo automatico di aumentare l’MTU. L’app lo effettua tramite il client GATT delle impostazioni se il testo esteso è attivo e il client è connesso allo stesso endpoint approvato. Il server Social usa il proprio MTU. Annota `social_mtu`, `social_mtu_source` e `configuration_client_mtu` prima e dopo. `att_default` identifica il valore iniziale e `server_callback` l’MTU osservato dal server. [Android BluetoothGatt.requestMtu](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)) · [Android BluetoothGattServerCallback.onMtuChanged](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onMtuChanged(android.bluetooth.BluetoothDevice,%20int))

### Isola lunghezza, codifica e ritmo

Apri **Altro → Laboratorio → Testo più lungo** e parti con **ASCII** e **Intervallo fra i frammenti in ms** a 0. Invia A e B per ciascuna **Dimensione del messaggio in byte (UTF-8)**, partendo da 12 e proseguendo con 20, 32, 64, 128, 200 e 255. Il campione di 32 byte termina con `#32`, quello di 255 con `#255`. Usa **Visualizza messaggio sorgente completo** per confrontare il campione con ciò che appare nel feed e nel dettaglio.

1. Ripeti attorno alla prima soglia in cui compare una differenza. **Ripeti l’ultima prova** ripropone lo stesso campione e profilo. Mantieni fissi stato del display, peer e MTU Social.
2. Tieni fissi lunghezza e intervallo. Confronta **ASCII** e **Più righe** a 128 o 255 byte, con le stesse impostazioni, e osserva il dettaglio con lo stesso scorrimento. Il Laboratorio usa lo stesso testo di base e sostituisce spazi con ritorni a capo. Prova **Accenti** ed **Emoji** separatamente scegliendo un campione che contenga il carattere desiderato. I byte UTF‑8 e i caratteri visualizzati possono avere conteggi diversi.
3. Torna ad ASCII con una lunghezza che produce più frammenti. Prova B con 0, 10, 25, 50, 100 e 300 ms. Dopo ogni giro attendi l’esito, apri la voce e registra cosa è cambiato. Ripeti A a condizioni identiche come riferimento. L’intervallo aggiunge una pausa fra i frammenti della risposta, senza cambiare il campione.
4. Apri il dettaglio subito e poi a intervalli annotati, per esempio a 5, 30 e 90 secondi. Premi **Segna apertura dettaglio** vicino a ciascuna apertura. La diagnostica registra `PROBE_DETAIL_MARKED` con l’identificativo numerico dell’ultima prova e l’istante sulla stessa scala degli eventi BLE. Il pulsante registra solo l’osservazione manuale. Il campione resta consultabile fino a 120 secondi nella stessa sessione Social, entro i limiti della cache. Se il controller chiede di nuovo un attributo valido, l’app può rispondere.
5. Se il firmware mostra ancora poco testo, prova **Invia pagine numerate**, poi **Invia pagine**. Ogni parte è una notifica Social distinta in modalità compatibile con un titolo `i/n`, fino a 12 byte UTF‑8 nel corpo e intervallo fra frammenti a 0. Controlla quante parti compaiono, come sono ordinate e se il marcatore finale è leggibile.

### Separa Social dalle disconnessioni

La diagnostica della prova 0.2.0 riporta 6 consegne completate, 2 scarti e 13 tentativi pianificati. Nel log compaiono 5 disconnessioni client con stato 147, 2 con 257, 2 mancate attivazioni del canale impostazioni con 133 e 6 tentativi di connessione falliti. Due eventi `PEER_DISCONNECTED` sono distinti dalle righe del client. Una consegna interrotta è esplicitamente marcata `TRANSPORT_CLOSED`. Le disconnessioni compaiono anche dopo giri compatibili o periodi senza frammenti. I tempi del log consentono di correlare gli eventi BLE. Per stabilire se il controller si è spento o sospeso, serve anche osservare il dispositivo.

1. Con il controller acceso e le impostazioni correnti, misura un intervallo senza invii in modalità normale. Annota se si spegne solo il display, se il controller perde la connessione e se il risveglio richiede il tasto di accensione. Esporta i tempi ed esiti.
2. Ripeti lo stesso intervallo in **Home → Controller → Connessione solo notifiche**. Social usa il server GATT e il peer approvato. Il client delle impostazioni, le interrogazioni e il tentativo MTU dal client rimangono inattivi. Attendi **Social connesso** prima di ripetere A e B.
3. Confronta retry, stato del peer e primo invio dopo il risveglio in entrambe le modalità. I controlli del tempo di sospensione possono essere consultati dal display o tramite lettura in modalità normale. Conserva la stessa configurazione nel confronto.
4. Se il controller è acceso e Social rimane indisponibile, apri **Home** e tocca **Ripristina collegamento**. L’app ricrea il server Social con il dispositivo già associato e preserva il client delle impostazioni se è già connesso. Attendi **Social connesso**, invia un nuovo campione e confronta i tempi con il ripristino automatico. L’esportazione indica il comando con `CONNECTION_RECOVERY_REQUESTED` e gli eventuali tentativi diretti con `SOCIAL_ATTACH_REQUESTED` e `SOCIAL_ATTACH_FAILED`. `PEER_WAKE_OBSERVED` segnala che Android ha osservato un collegamento del peer selezionato.

## 3. Verifica le notifiche Android

Usa messaggi di prova, nomi inventati e account o conversazioni che puoi controllare. Abilita le singole app da **Notifiche → App rilevate → Modifica regole** dopo che ciascuna ha pubblicato una notifica. Conserva **Riservatezza a telefono bloccato** durante le prime prove. Android può limitare l’accesso a notifiche sensibili, profili di lavoro e contenuti che l’app mittente omette. [API NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) · [Protezione delle notifiche sensibili su Android 15](https://developer.android.com/about/versions/15/behavior-changes-all)

| Caso | Passi | Osservazione attesa |
|---|---|---|
| App opt-in | Fai arrivare un messaggio da un’app appena rilevata. Abilitala e ripeti con un testo diverso. | L’app compare nell’elenco dopo il primo evento. Solo il secondo può essere inoltrato. Prova anche Telegram, WhatsApp Business o un’app di test. |
| Filtri | Imposta un ID di canale consentito. In una chat di prova, alterna un nome o ID consentito e uno bloccato. Ripeti con due conversazioni. | I blocchi prevalgono sulle autorizzazioni. Un elenco di autorizzazioni vuoto accetta ogni valore disponibile. I nomi sono confrontati ignorando maiuscole e spazi equivalenti. |
| Conversazione di gruppo | Pubblica una sequenza numerata da più mittenti. Ripeti con un mittente bloccato. | Il titolo dà precedenza alla conversazione, seguito dal nome dell’app se abilitato. Il corpo compatto contiene il messaggio più recente. Con testo esteso, può includere fino a tre messaggi, dal più recente. I messaggi del mittente bloccato sono esclusi. Il limite in byte può tagliare il contenuto, incluso il nome dell’app. |
| Deduplicazione e ordine | Invia tre notifiche con chiavi distinte. Aggiornane rapidamente una con lo stesso identificatore e lo stesso testo. Ripeti cambiando il testo. | Le chiavi distinte mantengono l’ordine in coda. Aggiornamenti identici sono scartati e aggiornamenti ravvicinati della stessa notifica sono accorpati. La resa di un aggiornamento già sul display si verifica nel Laboratorio. |
| Notifica silenziosa | Ripeti un avviso silenzioso prima e dopo aver abilitato **Includi avvisi silenziosi**. | L’evento viene inoltrato se Android lo consegna e la regola lo consente. |
| UI chiusa | Torna alla Home del telefono con la sessione attiva. Fai pubblicare un nuovo evento e poi riapri Stealth Link. | Il servizio può inoltrare l’evento anche mentre l’interfaccia è chiusa. |
| Telefono bloccato | Con riservatezza attiva, blocca il telefono e genera un evento consentito. Poi sblocca il telefono, cambia il testo e ripeti. | A telefono bloccato viene usato un avviso generico. Il nuovo evento dopo lo sblocco mostra i campi previsti dalle regole. |
| Sospensione del controller | Lascia che il controller entri in sospensione con il telefono bloccato. Risveglialo, attendi **Social connesso** e invia un nuovo campione. Ripeti una volta con il testo esteso. | Confronta tempi di riconnessione, nuovo MTU Social e gli eventuali timeout. Controlla il primo campione inviato dopo la riconnessione. |
| Perdita del collegamento | Spegni e riaccendi il Bluetooth o sposta il controller fuori portata. Invia due notifiche sintetiche in momenti distinti, prima e dopo il ritorno in portata. | Il dispositivo torna disponibile quando Android consente la riconnessione. Gli avvisi scaduti o con esito di consegna ambiguo non sono ripubblicati automaticamente. |
| Pausa e revoca | Attiva **Metti in pausa l’inoltro**. Poi riattiva, revoca l’accesso Android alle notifiche e prova un nuovo evento. | L’inoltro si ferma in pausa o senza accesso. Ripristinare il permesso e generare un nuovo evento permette di riprendere la prova. |

Ripeti le prove di background con il normale risparmio energetico del telefono e annota le differenze. Prova la chiusura dalla schermata recenti, il riavvio e l’arresto forzato come casi separati. Dopo **Arresto forzato**, riapri Stealth Link e avvia la sessione dalla schermata Home.

## 4. Misura il display nel Laboratorio

In **Altro → Laboratorio → Esperimenti sul firmware**, abilita le prove, mantieni collegato il controller e parti ogni volta da uno stato noto. Ripeti ciascuna osservazione almeno tre volte. Scegli **Osservato**, **Non osservato** o **Inconcludente**. Usa **Inconcludente** quando manca conferma che il campione sia arrivato o quando cambi firmware, modalità di gioco, stato del display o configurazione a metà prova.

| Prova | Preparazione e misura |
|---|---|
| Apertura automatica | Torna alla schermata principale del controller. Invia il campione e osserva se si apre il feed Social, appare un indicatore o rimane la schermata corrente. Ripeti durante il gioco. |
| Risveglio | Lascia spegnere il display per timeout, mantenendo la sessione BLE attiva. Invia il campione e misura se si accende il display o compare solo la voce nel feed dopo il risveglio manuale. |
| Lunghezza | Usa i pulsanti A e B in **Testo più lungo** e annota l’ultima sezione visibile. Ripeti con campioni di testo ASCII, lettere accentate ed emoji. Annota l’MTU Social e le lunghezze diagnostiche. |
| Scorrimento | Usa i pulsanti A e B in **Scorrimento**. Apri ciascun campione in Social, attendi qualche secondo, poi usa i comandi indicati dal controller. Distingui scorrimento automatico del testo, scorrimento manuale e navigazione tra più notifiche. |
| Icone | Confronta il campione con identificatore e nome app sintetici e un campione Social standard. Registra se il controller richiede questi attributi e se l’icona cambia tra feed e dettaglio. Il risultato riguarda l’identificatore provato e il firmware installato. |
| Categorie | Alterna un campione della categoria sperimentale e uno Social. Registra layout, indicatore e raggruppamento. Una differenza di visualizzazione documenta la categoria provata. |
| Vibrazione | Tieni immobile il controller, con vibrazione attiva. Ricevi una prova lontano da eventi di gioco che potrebbero muovere i motori. Annota il tipo e la durata dell’eventuale vibrazione. |
| Durata | Cronometra separatamente indicatore iniziale, display acceso e permanenza della voce nel feed. Ripeti evitando pressioni sui tasti e annota il timeout corrente del display. |
| Aggiornamento | Osserva la voce iniziale. Confrontala con la versione aggiornata. Annota se cambia in sede, si aggiunge una voce, rimane invariata o si cancella. |
| Rimozione | Verifica prima che la voce sia presente. Avvia o completa l’esperimento di rimozione seguendo le indicazioni del Laboratorio. Controlla feed e dettaglio. |

Per ciascun giro registra versione del firmware, telefono, modalità di collegamento al gioco, stato del display, tipo di prova, esito, tempo indicativo e ultima sezione visibile. Un video con campioni sintetici aiuta a separare in modo ripetibile la consegna dal comportamento del display.

## 5. Impostazioni e profili locali

1. Da **Altro → Configurazione → Leggi impostazioni del controller**, acquisisci slot attivo, nomi degli slot e proprietà di sistema esposte dalla lettura. I nomi degli slot compaiono in **Profili**. Confronta i valori con le informazioni sul controller o con una lettura dell’app ufficiale in una sessione separata. Registra valori mancanti e disconnessioni.
2. Sul GATT delle impostazioni, la lettura applicativa passa tramite scritture ATT di interrogazione sulla caratteristica `FF01` del servizio `FF00`, seguite da notifiche di risposta. Le richieste sono `11 BB` per lo slot attivo, `D1 09` per il nome di uno slot e `D1 06` per le proprietà di sistema, seguite dalle letture facoltative `C2` per la gestione energia e `C4` per il LED di carica. Batteria e revisioni appaiono quando le caratteristiche GATT standard sono esposte e leggibili. In una traccia controlla il CCC che abilita le risposte. [Protocollo di lettura](protocollo-bluetooth.md#impostazioni-in-sola-lettura)
3. Apri **Profili**, crea un profilo di prova, rinominalo e selezionalo. Verifica che la selezione locale sia visibile accanto agli slot del controller. Usa **Esporta backup**, poi rimuovi il profilo locale e usa **Importa** per verificare il recupero. Il backup contiene i profili locali e la selezione rimane una scelta su questo telefono. Gli slot del controller vengono letti separatamente.

Gestione del firmware, ripristino di fabbrica e modifica delle impostazioni sul controller sono fuori dal perimetro della prova. Per ripetere il confronto usa i firmware già installati sui dispositivi disponibili.

## 6. Esporta un esito riproducibile

Dal pannello **Diagnostica Bluetooth** esporta un file dopo il problema o alla fine della sessione. Puoi accompagnarlo con i passi ripetibili, le versioni e la tabella di osservazione. Il file diagnostico dell’app contiene stati, contatori, dimensioni e risultati numerici del trasporto. Prima di condividerlo, verifica l’assenza di contenuto delle notifiche, pacchetti delle app osservate, indirizzi Bluetooth, contatti e nomi di conversazioni o profili.

Per una prova di lunghezza, parti dalla riga `LENGTH_PROBE test=…`. Riporta parametri, parti completate ed esito e usa lo stesso numero nel campo `probe` degli eventi BLE. Il campo `response` ricongiunge i frammenti della singola risposta. `frame_bytes` misura la dimensione del frame, `pacing_ms` l’intervallo impostato, `callback_ms` il tempo tra invio e callback e `gap_ms` il tempo tra il callback precedente e l’invio del nuovo frammento. `PROBE_DETAIL_MARKED` consente di confrontare gli eventi con l’istante di apertura indicato dall’utente.

Nelle prove di inoltro ordinario, confronta i contatori prima e dopo l’avviso. `normal_pages_completed` conta le pagine con esito di trasporto completo, `normal_paginations_completed` i messaggi suddivisi completati e `normal_paginations_partial` le sequenze interrotte dopo una o più pagine. `normal_truncated_plans` conta i messaggi abbreviati dal limite applicativo. Annota anche l’ordine visibile sul controller, che resta una misura separata.

I campi `last_requested_title_bytes` e `last_requested_body_bytes` riportano i limiti chiesti dal controller. I campi `last_declared_…_bytes` riportano le lunghezze dichiarate nella risposta. I campi `last_sent_…_bytes` riportano i byte di testo inviati secondo i callback. `data_frames_sent` conta i frammenti Data Source e `data_wire_bytes_sent_total` include gli header. `notification_source_subscribed` e `data_source_subscribed` distinguono le sottoscrizioni. `transport_completed` e `transport_drops` descrivono il trasporto. Annota separatamente quanto appare nel feed e nel dettaglio.

Per una verifica tecnica facoltativa, abilita il registro **Bluetooth HCI snoop** nelle Opzioni sviluppatore Android per una sessione con soli dati sintetici. Esegui associazione e prova base, poi acquisisci il rapporto Android se il telefono espone la traccia. In Wireshark correla scoperta GATT, sottoscrizioni CCC, MTU, evento, richiesta degli attributi e risposte. Tieni la cattura integrale sul tuo dispositivo perché può contenere indirizzi e altri dati dell’ambiente. Condividi eventuali estratti controllati e disattiva il registro al termine.
