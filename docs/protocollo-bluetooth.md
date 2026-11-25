# Protocollo Bluetooth di Stealth Link

Questa specifica descrive il comportamento di `0.2.0-alpha`. I formati Social vengono verificati con vettori e un simulatore Kotlin. Una prova fisica con `0.1.0-alpha` ha confermato la consegna Social su uno Stealth Ultra a MTU 23 e ha mostrato testo troncato. La ricomposizione della modalità estesa va misurata sul firmware con la nuova versione. [Risultati dei test](risultati-test.md) · [Confronto A/B sul controller](prove-telefono.md#confronto-ab-a-mtu-23)

## Ruoli GATT

| Canale | Ruolo del telefono | Ruolo del controller | Servizio |
|---|---|---|---|
| Social | Server GATT, provider della notifica | Client GATT, consumer | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` |
| Impostazioni | Client GATT | Server GATT | `0000FF00-0000-1000-8000-00805F9B34FB` |

Per il canale Social si seleziona il peer già associato. Il manuale riporta `STEALTH ANCS` per App Link. La companion riconosce anche `STEALTH ULTRA APP` per le impostazioni e `STEALTH ULTRA` per i casi in cui i ruoli usano la stessa voce associata. L’identità della sessione è associata all’indirizzo scelto dall’utente e allo stato di bonding. [Guida Turtle Beach](https://manuals.plus/m/0faa5a2e652a2d99fa99066601e38e1cbab0aa1b4cb5610c31ff02d8ae52e15c)

## Social

L’app implementa un sottoinsieme di ANCS attraverso gli UUID pubblici. Il trasporto Social predefinito invia un singolo frame per attributo. La modalità estesa va attivata esplicitamente e può usare più frammenti ATT per una risposta. Entrambe richiedono cifratura per l’accesso ai descrittori CCC e alle scritture sul Control Point. La sessione si considera pronta quando il peer approvato ha sottoscritto entrambe le caratteristiche notificabili. La modalità legacy esplicita del Laboratorio consente il trasporto di soli campioni sintetici senza imporre la cifratura a livello di attributo. [Specifica ANCS di Apple](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

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

Nel trasporto **esteso** il limite del testo è il minimo tra richiesta del peer e 1024 byte per attributo. Si codifica prima il testo UTF‑8, si aggiunge un solo header a inizio risposta e si suddivide il flusso in valori entro il budget ATT. Il ricevente deve concatenare i frammenti prima di leggere testo e lunghezza. I confini fra frame possono cadere dentro una sequenza UTF‑8, mentre il testo della risposta completa finisce a un confine valido. Il limite di ciò che viene mostrato dipende dal firmware, dai glifi e dall’interfaccia del display. [Specifica ANCS di Apple](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html) · [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)

Il profilo a singolo frame usa un corpo compatto con l’ultimo messaggio disponibile per la conversazione. Con **Notifiche → Testo esteso (sperimentale)**, il corpo può includere fino a tre messaggi. Il record Social memorizza la modalità quando viene creato e conserva lo stesso formato di evento e UID. I pulsanti A/B del Laboratorio applicano la modalità al singolo campione, senza riavviare la sessione. Il nome dell’app può comparire come testo nel titolo, se disponibile e abilitato nella regola. La sua visibilità dipende dalla richiesta del controller e dal budget dell’attributo. L’attributo ANCS dedicato all’identificatore e la relativa risposta con il nome sono riservati alla prova sintetica.

### MTU e diagnostica

L’MTU Social parte da 23 e usa gli aggiornamenti ricevuti dal callback del server GATT per il peer approvato. La diagnostica distingue il valore ATT iniziale dall’MTU Social osservato e mostra a parte l’MTU delle impostazioni. Se il client delle impostazioni è già connesso allo stesso endpoint approvato, l’app può chiedere un MTU più alto sul client. L’MTU è una proprietà del bearer ATT. La stessa voce Bluetooth è una condizione per il tentativo e il valore del client non viene usato per dimensionare i frame Social. Sui dispositivi Android 14 e successivi il sistema richiede 517 alla prima chiamata GATT per un MTU e ignora le richieste successive. Per dimensionare i frame Social, l’app continua a usare il valore osservato sul server. [Bluetooth Core, Attribute Protocol §§ 3.2.8 e 3.4.2](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host/attribute-protocol--att-.html) · [Android BluetoothGatt.requestMtu](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)) · [Android BluetoothGattServerCallback.onMtuChanged](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onMtuChanged(android.bluetooth.BluetoothDevice,%20int))

La diagnostica associa i risultati di consegna ai valori numerici di MTU, attributi e frammenti. La dimensione dell’attributo descrive i byte di testo. La dimensione del frammento include l’header quando presente. Il campo `response` è un numero progressivo locale, utile a correlare richieste e frammenti. L’esportazione contiene conteggi ed esiti di trasporto e omette testo, UID delle notifiche, pacchetti, nomi e indirizzi Bluetooth.

La coda applicativa conserva al massimo 32 elementi per 30 secondi. La sessione Social mantiene fino a 32 record e ritarda il riuso di un UID legacy finché non scade il periodo di conservazione. Una consegna per volta è seguita fino ai callback GATT dei due attributi o a un timeout. Il callback registra un esito di trasporto. La visualizzazione richiede un riscontro sul controller. [Android BluetoothGattServerCallback.onNotificationSent](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,%20int))

### Esperimenti e simulazione

| Funzione | Comportamento dell’alpha |
|---|---|
| Lunghezza e scorrimento | Il Laboratorio fornisce campioni sintetici per confrontare i limiti a singolo frame e la risposta frammentata. Il flusso UTF‑8 viene codificato entro il limite prima di essere diviso in valori ATT. La ricomposizione e lo scorrimento sul controller richiedono osservazioni distinte. |
| Identificatore app e icone | La prova può rispondere con l’identificatore sintetico (`00` come attributo di notifica) e con il nome sintetico alla relativa richiesta `Get App Attributes`. L’icona eventualmente mostrata è una decisione del controller. |
| Categorie | Il campione sperimentale usa categoria `06`, con `04` come riferimento Social. |
| Aggiornamento e rimozione | Il Laboratorio può inviare evento `01` o `02` con lo stesso UID di una notifica sintetica già pubblicata. |
| Apertura, risveglio, vibrazione e durata | Il Laboratorio invia un campione Social e permette di registrare ciò che fa il controller. L’osservazione fisica misura questi comportamenti. |

Il codec espone anche un’opzione per UID con 32 bit variabili. L’APK usa gli UID legacy sia in modalità compatta sia estesa. Il simulatore è un client di test che emette richieste e ricompone le risposte, con MTU ATT selezionabile tra 23, 64 e 185. Le prove del simulatore verificano il contratto implementato e lasciano la resa sul Command Display alla prova fisica. [Codec e sessione](../core/src/main/kotlin/dev/stealthcompanion/core/protocol) · [Risultati dei test](risultati-test.md)

## Impostazioni in sola lettura

Il client si sottoscrive alla caratteristica `0000FF01-0000-1000-8000-00805F9B34FB` del servizio `FF00`. Trasporta la richiesta di interrogazione tramite ATT e riceve il risultato come notifica GATT sulla stessa caratteristica. Le richieste sono di sei byte e l’ultimo è la somma dei cinque precedenti modulo 256. La coda usa un’interrogazione per volta.

| Interrogazione | Richiesta | Risposta accettata |
|---|---|---|
| Slot attivo | `11 BB 00 00 00 CC` | Prefisso `11 BB 00`, almeno 4 byte, indice `0..9` a offset 3. |
| Nome slot | `D1 09 s 00 00 checksum` | Prefisso `D1 09 00`, 21 byte, stesso indice a offset 3. Nome UTF‑8 agli offset da 4 a 19, interrotto al primo zero. |
| Proprietà di sistema | `D1 06 00 00 00 D7` | Prefisso `D1 06 00`, almeno 9 byte. Agli offset da 3 a 8 compaiono modalità energetica, luminosità display, valore RGB, timeout display, sospensione e luminosità di carica. |

La companion mostra gli slot numerati da 1 a 10 e verifica gli indici trasmessi come valori da 0 a 9. La lettura convalida il prefisso, la lunghezza e quando previsto lo slot atteso. Il significato di eventuali byte aggiuntivi e di un checksum nelle risposte resta da confermare con catture reali. L’APK include solo le interrogazioni elencate. [Codec di lettura](../app/src/main/java/dev/stealthcompanion/ble/ReadOnlySettingsCodec.kt) · [Architettura](architettura.md)
