# Protocollo Bluetooth di Stealth Link

Questa specifica descrive il comportamento di `0.3.0-alpha`. I formati Social vengono verificati con vettori e un simulatore Kotlin. Il log fisico della 0.2.1 conferma il trasporto di campioni ASCII da 128 e 255 byte. Foto e resoconto collocano la soglia visibile intorno a 120 caratteri ASCII sul controller provato. [Risultati dei test](risultati-test.md) · [Prove sul controller](prove-telefono.md#confronto-ab-a-mtu-23)

## Ruoli GATT

| Canale | Ruolo del telefono | Ruolo del controller | Servizio |
|---|---|---|---|
| Social | Server GATT, provider della notifica | Client GATT, consumer | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` |
| Impostazioni | Client GATT | Server GATT | `0000FF00-0000-1000-8000-00805F9B34FB` |

Per il canale Social si seleziona il peer già associato. Il manuale riporta `STEALTH ANCS` per App Link. La companion riconosce anche `STEALTH ULTRA APP` per le impostazioni e `STEALTH ULTRA` per i casi in cui i ruoli usano la stessa voce associata. L’identità della sessione è associata all’indirizzo scelto dall’utente e allo stato di bonding. [Guida Turtle Beach](https://manuals.plus/m/0faa5a2e652a2d99fa99066601e38e1cbab0aa1b4cb5610c31ff02d8ae52e15c)

## Social

L’app implementa un sottoinsieme di ANCS attraverso gli UUID pubblici. Il trasporto compatibile invia un singolo frame per attributo. Le nuove installazioni partono in modalità estesa, che può usare più frammenti ATT per una risposta. L’aggiornamento conserva una preferenza di trasporto già scelta. Entrambe richiedono cifratura per l’accesso ai descrittori CCC e alle scritture sul Control Point. La sessione si considera pronta quando il peer approvato ha sottoscritto entrambe le caratteristiche notificabili. La modalità legacy esplicita del Laboratorio consente il trasporto di soli campioni sintetici senza imporre la cifratura a livello di attributo. [Specifica ANCS di Apple](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

| Caratteristica | UUID | Direzione |
|---|---|---|
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | Evento via notifica GATT al controller, con CCC `0x2902`. |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | Scrittura del controller con la richiesta degli attributi. |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | Attributi via notifica GATT al controller, con CCC `0x2902`. |

### Evento, richiesta e risposta

1. Notification Source emette otto byte. L’evento predefinito è `00 10 04 c 00 00 00 c`. I campi sono evento, flag, categoria, conteggio categoria e UID di quattro byte in little-endian. `00` indica aggiunta, `04` è Social e `10` conserva il valore dei flag osservato nel formato legacy. `c` occupa il conteggio e il byte più significativo dell’UID. Il codec conserva la forma legacy e confronta l’intero UID nelle richieste.
2. Il controller scrive `00 [UID:4] [ID attributo:1] [massimo:2]` sul Control Point per chiedere il titolo `01` o il corpo `03`. Il massimo è espresso in byte e codificato little-endian. Il trasporto compatto risponde a un attributo per richiesta. Quello esteso accetta anche entrambi gli attributi nella stessa richiesta e li restituisce nell’ordine richiesto. Il codec valida dimensioni, comando, attributi e UID prima di costruire la risposta.
3. Data Source emette `00 [UID:4] [ID attributo:1] [lunghezza:2] [testo UTF-8]`. La lunghezza è little-endian e il testo è privo di terminatore. Titolo e corpo possono essere chiesti in qualunque ordine. L’app mantiene i due attributi nello stesso record di sessione e serializza i pacchetti seguendo i callback GATT.

Esempio sintetico con contatore `01` e titolo `Caffè`.

```text
Evento       00 10 04 01 00 00 00 01
Richiesta    00 00 00 00 01 01 FF 00
Risposta     00 00 00 00 01 01 06 00 43 61 66 66 C3 A8
```

Nel trasporto a **singolo frame** il limite del testo è il minimo tra richiesta del peer, 255 byte e spazio ATT disponibile dopo l’header. Con MTU 23, il valore di una notifica ATT può contenere 20 byte e l’header ne occupa 8, lasciando 12 byte all’attributo. Un accento può occupare più di un byte. Quando il testo supera il budget, l’encoder si ferma a un confine di grafema rilevato dal pattern `\X`. Una risposta può avere lunghezza zero se anche il primo grafema supera il budget. [Bluetooth Core, Attribute Protocol § 3.4.7.1](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/attribute-protocol--att-.html)

Nel trasporto **esteso** il codec considera la richiesta del peer e il limite di 1024 byte per attributo. Il corpo delle notifiche ordinarie usa anche il limite applicativo configurato, con 110 byte UTF‑8 per pagina come valore iniziale. Si codifica prima il testo UTF‑8, si aggiunge un solo header a inizio risposta e si suddivide il flusso in valori entro il budget ATT. Il ricevente deve concatenare i frammenti prima di leggere testo e lunghezza. I confini fra frame possono cadere dentro una sequenza UTF‑8, mentre il testo della risposta completa finisce a un confine valido. Il limite di ciò che viene mostrato dipende dal firmware, dai glifi e dall’interfaccia del display. [Specifica ANCS di Apple](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

Il profilo a singolo frame usa un corpo compatto con l’ultimo messaggio disponibile per la conversazione. Con **Notifiche → Formato sul controller → Testo esteso (consigliato)**, il corpo può includere fino a tre messaggi. Il record Social memorizza la modalità quando viene creato e conserva lo stesso formato di evento e UID. I pulsanti A/B del Laboratorio applicano la modalità al singolo campione, senza riavviare la sessione. Il nome dell’app può comparire come testo nel titolo, se disponibile e abilitato nella regola. La sua visibilità dipende dalla richiesta del controller e dal budget dell’attributo. L’attributo ANCS dedicato all’identificatore e la relativa risposta con il nome sono riservati alla prova sintetica.

L’intervallo iniziale di 25 ms si applica tra frammenti della stessa risposta alle notifiche ordinarie in modalità estesa. Il frame successivo parte dopo il callback e la pausa configurata. I campioni del Laboratorio conservano la lunghezza e il ritmo scelti per la prova. Il limite ordinario è selezionabile tra 64, 96, 110, 120, 128 e 255 byte. Il limite osservato nelle richieste del peer può ridurre il budget.

La suddivisione è attiva per impostazione iniziale nel testo esteso e richiede la regola **Consenti pagine numerate per questa app**. Si può disattivare globalmente o per app. Si inviano fino a due, tre o quattro pagine, secondo il limite scelto, con quattro iniziali. Ogni pagina è una notifica distinta. Il titolo `i/n` usa il mittente quando disponibile. La suddivisione usa confini di grafema e punti di interruzione di riga. Il testo che eccede il budget termina con `...`. **Righe compatte** rimuove i ritorni a capo quando attivo. **Invia prima l’ultima pagina** inverte l’ordine di invio per il feed che mostra per primi gli avvisi più recenti. L’app aspetta l’esito GATT della pagina in volo prima di inviare la successiva. A esito ambiguo l’invio si ferma. Gli avvisi riservati durante il blocco usano una sola pagina.

### MTU e diagnostica

L’MTU Social parte da 23 e usa gli aggiornamenti ricevuti dal callback del server GATT per il peer approvato. La diagnostica distingue il valore ATT iniziale dall’MTU Social osservato e mostra a parte l’MTU delle impostazioni. Se il client delle impostazioni è già connesso allo stesso endpoint approvato, l’app può chiedere un MTU più alto sul client. L’MTU è una proprietà del bearer ATT. La stessa voce Bluetooth è una condizione per il tentativo e il valore del client non viene usato per dimensionare i frame Social. Sui dispositivi Android 14 e successivi il sistema richiede 517 alla prima chiamata GATT per un MTU e ignora le richieste successive. Per dimensionare i frame Social, l’app continua a usare il valore osservato sul server. [Bluetooth Core, Attribute Protocol §§ 3.2.8 e 3.4.2](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/attribute-protocol--att-.html) · [Android BluetoothGatt.requestMtu](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)) · [Android BluetoothGattServerCallback.onMtuChanged](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onMtuChanged(android.bluetooth.BluetoothDevice,%20int))

La diagnostica associa gli esiti GATT ai valori numerici di MTU, sottoscrizioni, attributi e frammenti. Distingue i byte richiesti dal peer, dichiarati dall’header e inviati con callback riuscito. La dimensione dell’attributo descrive i byte di testo, mentre la dimensione del frammento include l’header quando presente. Il campo `response` è un numero progressivo locale. Le prove di lunghezza aggiungono un identificativo locale, parametri, esito e conteggio delle parti completate. `PROBE_DETAIL_MARKED` annota l’istante in cui l’utente segnala di aver aperto il dettaglio, sulla stessa scala temporale degli eventi GATT. L’esportazione omette testo, UID delle notifiche, pacchetti, nomi e indirizzi Bluetooth.

La coda delle notifiche personali conserva al massimo 32 elementi per 30 secondi. La sessione Social mantiene fino a 32 record. I record personali scadono entro 30 secondi, quelli delle prove di lunghezza sintetiche possono restare fino a 120 secondi nella stessa sessione. Il riuso dell’UID legacy è differito fino alla relativa scadenza. Una consegna per volta è seguita fino ai callback GATT dei due attributi o a un timeout. Durante il periodo di conservazione, il server può rispondere alle richieste successive di un attributo del record. Il callback registra un esito di trasporto. La visualizzazione richiede un riscontro sul controller. [Android BluetoothGattServerCallback.onNotificationSent](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,%20int))

### Riscontro di trasporto sullo Stealth Ultra

Nell’esportazione della 0.2.0, due titoli estesi hanno completato 31 byte in due frammenti da 20 e 19 byte. Due corpi estesi hanno completato 254 byte di testo. I 13 frammenti da 20 byte e quello da 2 totalizzano 262 byte, di cui 8 di header. Il massimo richiesto in entrambi i casi era 255 byte. Gli intervalli fra primo e ultimo callback del corpo sono stati 43 e 83 ms. La fotografia del feed esteso mostra il prefisso `Caffè pronto · già qui`. L’esportazione non registra un’apertura del dettaglio o un secondo giro di richieste per il record. Una nuova prova può aggiungere il marcatore manuale di apertura per correlare ciò che appare sul display con eventuali richieste successive. [Misure sul controller](prove-telefono.md#confronto-ab-a-mtu-23)

Il log fisico della 0.2.1 registra 12 richieste di attributi da 255 byte, sei consegne complete e nessuna interruzione o scarto. I 43 frame Data Source trasportano 673 byte di testo su 769 byte complessivi, con 8 byte di header per risposta. Le prove di lunghezza hanno completato 32, 64, 255 e 128 byte di corpo. I 255 byte hanno occupato 14 frame e i 128 byte 7 frame. Il server usa ATT 23 iniziale e il client impostazioni riporta 247. Tutte le richieste hanno `repeat=0`. Il marcatore di apertura della prova da 64 byte non ha una successiva richiesta associata nella traccia.

L’utente riferisce di aver letto tutto il campione da 32 byte, più righe e scorrimento. Le due foto del dettaglio mostrano marcatori intorno a 120 caratteri del campione ASCII. Le foto non riportano l’identificativo della prova. La quantità visibile è un’osservazione del display e resta distinta dalla lunghezza trasmessa. I campioni ASCII lunghi usavano 25 ms di pacing, il campione a più righe 64 byte e 0 ms. La nuova prova a stessa lunghezza e intervallo varia solo i ritorni a capo. [Dettaglio delle misure](prove-telefono.md#confronto-ab-a-mtu-23)

### Esperimenti e simulazione

| Funzione | Comportamento dell’alpha |
|---|---|
| Lunghezza e scorrimento | Il Laboratorio confronta un campione identico a singolo frame e frammentato. Le prove di lunghezza offrono corpi UTF‑8 da 12, 20, 32, 64, 128, 200 o 255 byte con un marcatore finale. Il Laboratorio offre ASCII, accenti, emoji o più righe. Per una prova a pari byte, ASCII e più righe usano lo stesso testo di base con spazi sostituiti da ritorni a capo nel secondo campione. La ricomposizione e lo scorrimento sul controller richiedono osservazioni distinte. |
| Intervallo fra frammenti | Il profilo di prova può aggiungere 0, 10, 25, 50, 100 o 300 ms fra frammenti della stessa risposta. Il server aspetta il callback del frame corrente prima di programmare il successivo. |
| Pagine numerate | Una prova suddivide il campione in notifiche Social compatibili distinte, con titolo numerato, corpo fino a 12 byte UTF‑8 e intervallo fra frammenti a 0. La capacità e l’ordine del feed vanno misurati sul controller. |
| Identificatore app e icone | La prova può rispondere con l’identificatore sintetico (`00` come attributo di notifica) e con il nome sintetico alla relativa richiesta `Get App Attributes`. L’icona eventualmente mostrata è una decisione del controller. |
| Categorie | Il campione sperimentale usa categoria `06`, con `04` come riferimento Social. |
| Aggiornamento e rimozione | Il Laboratorio può inviare evento `01` o `02` con lo stesso UID di una notifica sintetica già pubblicata. |
| Apertura, risveglio, vibrazione e durata | Il Laboratorio invia un campione Social e permette di registrare ciò che fa il controller. L’osservazione fisica misura questi comportamenti. |

Il codec espone anche un’opzione per UID con 32 bit variabili. L’APK usa gli UID legacy sia in modalità compatta sia estesa. Il simulatore è un client di test che emette richieste e ricompone le risposte, con MTU ATT selezionabile tra 23, 64 e 185. Le prove del simulatore verificano il contratto implementato e lasciano la resa sul Command Display alla prova fisica. [Codec e sessione](../core/src/main/kotlin/dev/stealthcompanion/core/protocol) · [Risultati dei test](risultati-test.md)

## Impostazioni in sola lettura

Con **Connessione solo notifiche**, Stealth Link usa il server GATT Social e salta l’apertura del client delle impostazioni. Le letture e la richiesta MTU dal client sono disponibili nella normale connessione con canale impostazioni. Le disconnessioni del client e il distacco del peer vengono registrati come eventi distinti. Il log descrive le transizioni GATT e i relativi stati Android. Per identificare un evento di alimentazione serve osservare il controller. [Prova di isolamento](prove-telefono.md#separa-social-dalle-disconnessioni)

**Ripristina collegamento** ricrea il server Social con il peer approvato e conserva l’associazione Bluetooth. Il client delle impostazioni già connesso resta attivo. L’app attende le nuove sottoscrizioni GATT prima di riprendere Social. Una richiesta di collegamento dal server è possibile quando il collegamento Social può essere gestito separatamente.

Il client si sottoscrive alla caratteristica `0000FF01-0000-1000-8000-00805F9B34FB` del servizio `FF00`. Trasporta la richiesta di interrogazione tramite ATT e riceve il risultato come notifica GATT sulla stessa caratteristica. Le richieste sono di sei byte e l’ultimo è la somma dei cinque precedenti modulo 256. La coda usa un’interrogazione per volta.

| Interrogazione | Richiesta | Risposta accettata |
|---|---|---|
| Slot attivo | `11 BB 00 00 00 CC` | Prefisso `11 BB 00`, almeno 4 byte, indice `0..9` a offset 3. |
| Nome slot | `D1 09 s 00 00 checksum` | Prefisso `D1 09 00`, 21 byte, stesso indice a offset 3. Nome UTF‑8 agli offset da 4 a 19, interrotto al primo zero. |
| Proprietà di sistema | `D1 06 00 00 00 D7` | Prefisso `D1 06 00`, almeno 9 byte. Agli offset da 3 a 8 compaiono modalità energetica, luminosità display, valore RGB, timeout display, sospensione e luminosità di carica. |
| Gestione energia facoltativa | `C2 00 00 00 00 C2` | Prefisso `C2 00`, 10 byte. Modalità a offset 2, timeout display a 3, luminosità display a 4 e timeout sospensione a 6. |
| LED di carica facoltativo | `C4 00 00 00 00 C4` | Prefisso `C4 00`, 6 byte. Valore luminosità LED di carica a offset 2. |

La companion mostra gli slot numerati da 1 a 10 e verifica gli indici trasmessi come valori da 0 a 9. La lettura convalida il prefisso, la lunghezza e quando previsto lo slot atteso. Le proprietà `D1 06` forniscono i valori di base. Quando valide, le risposte `C2` e `C4` aggiornano i campi corrispondenti. Per `C2` la modalità è accettata da 0 a 3 e la luminosità display da 0 a 10. Per `C4` la luminosità di carica è accettata da 0 a 10. Il significato di eventuali byte aggiuntivi e di un checksum nelle risposte resta da confermare con catture reali. L’APK include come richieste proprietarie solo le interrogazioni elencate. [Codec di lettura](../app/src/main/java/dev/stealthcompanion/ble/ReadOnlySettingsCodec.kt) · [Architettura](architettura.md)

Il client può anche leggere le caratteristiche GATT standard esposte dal controller. Il Device Information Service `180A` può fornire revisione firmware `2A26`, hardware `2A27` e software `2A28`. Il Battery Service `180F` può fornire il livello di carica `2A19` come percentuale. La disponibilità di questi dati sullo Stealth Ultra dipende dal firmware e resta da verificare fisicamente. [Device Information Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/DIS_v1.2/out/en/index-en.html) · [Battery Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BAS_v1.1/out/en/index-en.html)
