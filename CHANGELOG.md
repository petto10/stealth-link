# Changelog

## 0.2.1-alpha

- Prove di lunghezza del corpo da 12 a 255 byte e campioni distinti per ASCII, accenti, emoji e ritorni a capo.
- Ritmo sperimentale dei frammenti da 0 a 300 ms, osservazione delle richieste tardive e fallback facoltativo in avvisi brevi numerati.
- Connessione solo notifiche per isolare Social dal client GATT delle impostazioni.
- Marcatore manuale per l’apertura del dettaglio, con esiti GATT e tempi correlabili per prova.
- Guida aggiornata con le osservazioni sul display della 0.2.0 e l’analisi dei frammenti e delle disconnessioni.

[Note 0.2.1-alpha in italiano e inglese](docs/releases/v0.2.1-alpha.md)

## 0.2.0-alpha

- Trasporto Social esteso sperimentale sul collegamento protetto. Per impostazione iniziale, ogni attributo usa un singolo frame e le conversazioni mostrano il messaggio più recente.
- Confronto sullo stesso campione con e senza frammentazione. Simulatore con MTU 23, 64 e 185 e anteprima dei byte decodificati.
- Diagnostica numerica con MTU Social distinto dall’MTU del client impostazioni, lunghezze richieste ed emesse, contatori e dimensioni dei frammenti.
- Aggiornamento della guida con il riscontro fisico a MTU 23 sulla versione 0.1 e la procedura A/B per verificare la ricomposizione sulla nuova versione.

[Note 0.2.0-alpha in italiano e inglese](docs/releases/v0.2.0-alpha.md)

## 0.1.0-alpha

Prima versione con notifiche Social, simulatore, Laboratorio del display, diagnostica locale e impostazioni del controller in sola lettura. [Note 0.1.0-alpha](docs/releases/v0.1.0-alpha.md) · [Pre-release](https://github.com/petto10/stealth-link/releases/tag/v0.1.0-alpha)
