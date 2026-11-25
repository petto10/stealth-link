# Risultati dei test · Stealth Link 0.1.0-alpha

Esecuzione del 25 novembre 2026 con JDK 17, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, piattaforma Android API 36 e Android Build Tools 35.0.0.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Resoconto della build debug locale, precedente alla firma di distribuzione. Build riuscita. Android Lint ha registrato **0 errori e 6 avvisi**. Tutti gli avvisi riguardano versioni più recenti disponibili di Gradle o delle librerie AndroidX/Compose. Il pacchetto conserva l’insieme di versioni usato nei test di questa alpha.

## Test automatici

| Suite | Superati |
|---|---:|
| `SocialProtocolTest` | 11 |
| `SocialPipelineTest` | 1 |
| `SocialPolicyTest` | 10 |
| `SocialQueueTest` | 7 |
| `SensitiveNotificationTest` | 2 |
| `ReadOnlySettingsCodecTest` | 5 |
| `ProfileStoreTest` | 6 |
| **Totale** | **42** |

I test verificano il flusso dalla policy alla coda e al display simulato, il filtraggio per contatto e conversazione, scadenze e duplicati, più UID legacy concorrenti, UTF‑8, limiti e ricomposizione, eventi sperimentali, interrogazioni consentite e importazione atomica dei profili.

## Verifica dell’APK

- Package Android `dev.stealthcompanion`, versione `0.1.0-alpha`, API minima 24 e target API 36.
- Firma APK v2 verificata con `apksigner` e allineamento verificato con `zipalign -c -P 16 4`.
- Manifest finale ispezionato con `aapt dump permissions`. Manca il permesso `android.permission.INTERNET`.
- SHA‑256 dell’APK `952ec609244f037242f4a4251175404a376098e97eed101c51ef9fcf21ca77cb`.

## Prove fisiche

Questo ambiente non espone controller Bluetooth, telefono Android o dispositivo di accelerazione per un emulatore. L’installazione e il comportamento Android sul telefono, la persistenza del servizio a schermo bloccato, l’associazione App Link, la riconnessione e l’effettiva risposta al protocollo dipendono dalle prove fisiche. Le osservazioni in laboratorio riguardano il firmware già installato. La guida `docs/prove-telefono.md` contiene la procedura.

La build include il simulatore, unicamente i comandi di interrogazione delle impostazioni e i controlli Social. La possibilità di inoltrare contenuto reale dipende da sottoscrizioni GATT protette sul controller selezionato. L’eventuale collegamento legacy del laboratorio è riservato a contenuti sintetici.
