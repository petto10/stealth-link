# Prove su Android e Turtle Beach Stealth Ultra

Questa guida copre la versione 0.1.0-alpha di Stealth Link. Il simulatore verifica gli scambi applicativi. Le prove sul controller misurano il collegamento Bluetooth, la compatibilità con il firmware installato e la resa sul Command Display.

## 1. Installa e prova il simulatore

1. Scarica sul telefono l’APK firmato allegato alla pre-release e aprilo. Per le prove di sviluppo puoi usare l’APK di debug compilato localmente. Se hai già una build debug installata, esporta i profili prima di rimuoverla e passare all’APK firmato. Se Android chiede l’autorizzazione a installare da quella sorgente, concedila al gestore file usato per aprire l’APK. Con Android Platform Tools e Debug USB, puoi installarlo anche con `adb install -r /percorso/dell-apk.apk`.
2. Apri **Stealth Link → Dispositivo → Simulatore**. Nel **Laboratorio**, imposta titolo e corpo in **Messaggio di prova** e premi **Invia prova**. Il simulatore è utilizzabile senza autorizzazioni Bluetooth o accesso alle notifiche.
3. Usa prima `Prova 01` e `Ciao`, poi un testo come `Caffè, perché, più — 👩🏽‍💻 ☕ 日本語` con un ritorno a capo. Controlla l’anteprima e i contatori. L’anteprima mostra il testo della prova. Un invio simulato conferma che il codice ha elaborato il campione, mentre la resa dei glifi e la lunghezza visualizzabile richiedono il display fisico.
4. In **Dispositivo → Esporta diagnostica**, salva il file sul telefono. Controlla che contenga stati e contatori. Scegli una destinazione locale se vuoi che il file resti sul telefono.

Per verificare anche il listener, apri **Dispositivo → Accesso alle notifiche** e abilita Stealth Link nelle impostazioni Android. Su alcuni telefoni Android applica restrizioni aggiuntive alle app di origine esterna. Se l’accesso risulta disabilitato, controlla **Impostazioni → App → Stealth Link** e la voce **Consenti impostazioni con restrizioni**, se presente. Concedila soltanto dopo aver verificato l’origine dell’APK. [Guida Android alle impostazioni con restrizioni](https://support.google.com/android/answer/12623953)

## 2. Associa lo Stealth Ultra

1. Annota modello del telefono, versione Android, firmware controller e modalità di gioco usata durante la prova. Leggi la versione installata dal controller o da una schermata informativa del produttore.
2. Sul controller apri **App Link** e avvia l’associazione. Apri le impostazioni Bluetooth del telefono e associa la voce corrispondente. Il manuale riporta il nome `STEALTH ANCS` per App Link. Il canale impostazioni può apparire come `STEALTH ULTRA APP`. Un’associazione per il solo uso come gamepad può apparire con un nome diverso. [Guida Turtle Beach Stealth Ultra](https://manuals.plus/m/0faa5a2e652a2d99fa99066601e38e1cbab0aa1b4cb5610c31ff02d8ae52e15c)
3. Termina un’eventuale sessione attiva dell’app ufficiale sul telefono, così puoi attribuire ogni evento a una sola companion. Se vuoi usare l’app ufficiale come riferimento, esegui i due giri di prova separatamente.
4. In Stealth Link seleziona **Dispositivo → Controller** e concedi il permesso **Dispositivi nelle vicinanze** quando Android lo richiede. Premi **Aggiorna** per rileggere le associazioni. Seleziona il dispositivo destinato alle impostazioni e, se App Link ha una voce associata separata, sceglila in **Collegamento Social**. Se entrambi i ruoli usano la stessa voce associata, usa **Usa lo stesso dispositivo**.
5. Tocca **Avvia connessione** con l’interfaccia aperta. Android può mostrare una notifica di servizio. Se il sistema chiede anche il permesso di mostrare le notifiche dell’app, concedilo per avere il controllo della sessione nell’area notifiche. Lo stato **Social connesso** richiede che il controller sottoscriva le caratteristiche del server locale. Il canale delle impostazioni può avere uno stato diverso.
6. Invia `Baseline 01` e `Messaggio sintetico` dal **Laboratorio**, poi apri il feed **Social** del controller. Verifica titolo e corpo, e annota se sono comparsi da soli o solo dopo aver aperto il feed.

L’inoltro di contenuti delle app richiede l’associazione del controller e la sottoscrizione protetta secondo la policy del collegamento. Un nome Bluetooth o una connessione al canale impostazioni non bastano. Se lo stato rimane in **Attesa di Social**, controlla App Link, le voci associate e il Bluetooth del telefono. Esporta la diagnostica con lo stato raggiunto. Nel Laboratorio puoi abilitare il collegamento legacy solo per campioni sintetici, mantenendo il peer già associato e approvato. Torna alla modalità protetta prima di inoltrare notifiche delle app. [Android Bluetooth in background](https://developer.android.com/develop/connectivity/bluetooth/ble/background) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

## 3. Verifica le notifiche Android

Usa messaggi di prova, nomi inventati e account o conversazioni che puoi controllare. Abilita le singole app da **Notifiche → App rilevate → Modifica regole** dopo che ciascuna ha pubblicato una notifica. Conserva **Riservatezza a telefono bloccato** durante le prime prove. Android può limitare l’accesso a notifiche sensibili, profili di lavoro e contenuti che l’app mittente omette. [API NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) · [Protezione delle notifiche sensibili su Android 15](https://developer.android.com/about/versions/15/behavior-changes-all)

| Caso | Passi | Osservazione attesa |
|---|---|---|
| App opt-in | Fai arrivare un messaggio da un’app appena rilevata. Abilitala e ripeti con un testo diverso. | L’app compare nell’elenco dopo il primo evento. Solo il secondo può essere inoltrato. Prova anche Telegram, WhatsApp Business o un’app di test. |
| Filtri | Imposta un ID di canale consentito. In una chat di prova, alterna un nome o ID consentito e uno bloccato. Ripeti con due conversazioni. | I blocchi prevalgono sulle autorizzazioni. Un elenco di autorizzazioni vuoto accetta ogni valore disponibile. I nomi sono confrontati ignorando maiuscole e spazi equivalenti. |
| Conversazione di gruppo | Pubblica una sequenza numerata da più mittenti. Ripeti con un mittente bloccato. | Il titolo dà precedenza alla conversazione, seguito dal nome dell’app se abilitato. Nel corpo il messaggio più recente viene prima, seguito dai precedenti, fino a tre messaggi. I messaggi del mittente bloccato sono esclusi. Il display può tagliare parte del testo. |
| Deduplicazione e ordine | Invia tre notifiche con chiavi distinte. Aggiornane rapidamente una con lo stesso identificatore e lo stesso testo. Ripeti cambiando il testo. | Le chiavi distinte mantengono l’ordine in coda. Aggiornamenti identici sono scartati e aggiornamenti ravvicinati della stessa notifica sono accorpati. La resa di un aggiornamento già sul display si verifica nel Laboratorio. |
| Notifica silenziosa | Ripeti un avviso silenzioso prima e dopo aver abilitato **Includi avvisi silenziosi**. | L’evento viene inoltrato se Android lo consegna e la regola lo consente. |
| UI chiusa | Torna alla Home del telefono con la sessione attiva. Fai pubblicare un nuovo evento e poi riapri Stealth Link. | Il servizio può inoltrare l’evento anche mentre l’interfaccia è chiusa. |
| Telefono bloccato | Con riservatezza attiva, blocca il telefono e genera un evento consentito. Poi sblocca il telefono, cambia il testo e ripeti. | A telefono bloccato viene usato un avviso generico. Il nuovo evento dopo lo sblocco mostra i campi previsti dalle regole. |
| Perdita del collegamento | Spegni e riaccendi il Bluetooth o sposta il controller fuori portata. Invia due notifiche sintetiche in momenti distinti, prima e dopo il ritorno in portata. | Il dispositivo torna disponibile quando Android consente la riconnessione. Gli avvisi scaduti o con esito di consegna ambiguo non sono ripubblicati automaticamente. |
| Pausa e revoca | Attiva **Metti in pausa l’inoltro**. Poi riattiva, revoca l’accesso Android alle notifiche e prova un nuovo evento. | L’inoltro si ferma in pausa o senza accesso. Ripristinare il permesso e generare un nuovo evento permette di riprendere la prova. |

Ripeti le prove di background con il normale risparmio energetico del telefono e annota le differenze. Prova la chiusura dalla schermata recenti, il riavvio e l’arresto forzato come casi separati. Dopo **Arresto forzato**, riapri Stealth Link e avvia la sessione dalla schermata Dispositivo.

## 4. Misura il display nel Laboratorio

In **Laboratorio → Esperimenti sul firmware**, abilita le prove, mantieni collegato il controller e parti ogni volta da uno stato noto. Ripeti ciascuna osservazione almeno tre volte. Scegli **Osservato**, **Non osservato** o **Inconcludente**. Usa **Inconcludente** quando manca conferma che il campione sia arrivato o quando cambi firmware, modalità di gioco, stato del display o configurazione a metà prova.

| Prova | Preparazione e misura |
|---|---|
| Apertura automatica | Torna alla schermata principale del controller. Invia il campione e osserva se si apre il feed Social, appare un indicatore o rimane la schermata corrente. Ripeti durante il gioco. |
| Risveglio | Lascia spegnere il display per timeout, mantenendo la sessione BLE attiva. Invia il campione e misura se si accende il display o compare solo la voce nel feed dopo il risveglio manuale. |
| Lunghezza | Invia il campione numerato **Testo più lungo** e annota l’ultima sezione visibile. Ripeti con caratteri ASCII, lettere accentate ed emoji. Annota l’MTU e il limite richiesto se disponibile da una traccia. |
| Scorrimento | Apri il campione lungo in Social. Attendi qualche secondo, poi usa i comandi indicati dal controller per navigare. Distingui scorrimento automatico del testo, scorrimento manuale e navigazione tra più notifiche. |
| Icone | Confronta il campione con identificatore app sintetico e un campione Social standard. Registra l’icona e se cambia tra feed e dettaglio. Il risultato riguarda l’identificatore provato e il firmware installato. |
| Categorie | Alterna un campione della categoria sperimentale e uno Social. Registra layout, indicatore e raggruppamento. Una differenza di visualizzazione documenta la categoria provata. |
| Vibrazione | Tieni immobile il controller, con vibrazione attiva. Ricevi una prova lontano da eventi di gioco che potrebbero muovere i motori. Annota il tipo e la durata dell’eventuale vibrazione. |
| Durata | Cronometra separatamente indicatore iniziale, display acceso e permanenza della voce nel feed. Ripeti evitando pressioni sui tasti e annota il timeout corrente del display. |
| Aggiornamento | Osserva la voce iniziale. Confrontala con la versione aggiornata. Annota se cambia in sede, si aggiunge una voce, rimane invariata o si cancella. |
| Rimozione | Verifica prima che la voce sia presente. Avvia o completa l’esperimento di rimozione seguendo le indicazioni del Laboratorio. Controlla feed e dettaglio. |

Per ciascun giro registra versione del firmware, telefono, modalità di collegamento al gioco, stato del display, tipo di prova, esito, tempo indicativo e ultima sezione visibile. Un video con campioni sintetici aiuta a separare in modo ripetibile la consegna dal comportamento del display.

## 5. Impostazioni e profili locali

1. Da **Profili → Leggi impostazioni del controller**, acquisisci slot attivo, nomi degli slot e proprietà di sistema esposte dalla lettura. Confrontali con le informazioni sul controller o con una lettura dell’app ufficiale in una sessione separata. Registra valori mancanti e disconnessioni.
2. Sul GATT delle impostazioni, la lettura applicativa passa tramite scritture ATT di interrogazione sulla caratteristica `FF01` del servizio `FF00`, seguite da notifiche di risposta. Le interrogazioni ammesse sono `11 BB` per lo slot attivo, `D1 09` per il nome di uno slot e `D1 06` per le proprietà di sistema. In una traccia controlla anche il CCC che abilita le risposte.
3. Crea un profilo di prova locale e usa **Esporta backup**. Rimuovi il profilo locale e usa **Importa** per verificare il recupero. L’archivio riguarda i profili locali creati nella companion. La lettura degli slot non costituisce un backup completo del controller.

Gestione del firmware, ripristino di fabbrica e modifica delle impostazioni sul controller sono fuori dal perimetro della prova. Per ripetere il confronto usa i firmware già installati sui dispositivi disponibili.

## 6. Esporta un esito riproducibile

Dal pannello **Diagnostica Bluetooth** esporta un file dopo il problema o alla fine della sessione. Puoi accompagnarlo con i passi ripetibili, le versioni e la tabella di osservazione. Il file diagnostico dell’app contiene stati, contatori ed errori di trasporto. Prima di condividerlo, verifica l’assenza di contenuto delle notifiche, pacchetti delle app osservate, indirizzi Bluetooth, contatti e nomi di conversazioni o profili.

Per una verifica tecnica facoltativa, abilita il registro **Bluetooth HCI snoop** nelle Opzioni sviluppatore Android per una sessione con soli dati sintetici. Esegui associazione e prova base, poi acquisisci il rapporto Android se il telefono espone la traccia. In Wireshark correla scoperta GATT, sottoscrizioni CCC, MTU, evento, richiesta degli attributi e risposte. Tieni la cattura integrale sul tuo dispositivo perché può contenere indirizzi e altri dati dell’ambiente. Condividi eventuali estratti controllati e disattiva il registro al termine.
