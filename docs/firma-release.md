# Firma Android e pubblicazione

L’APK di release usa l’identificativo `dev.stealthcompanion` e una chiave dedicata al solo progetto. Android identifica un aggiornamento tramite il package e il certificato. Per ogni aggiornamento usa la stessa chiave e aumenta `versionCode` in `app/build.gradle.kts`. La build di debug ha un certificato diverso. Chi la usa può esportare i profili, rimuoverla e installare la build firmata prima di importare i profili.

## Custodia della chiave

Usa un keystore PKCS#12 con alias `stealth-link`. Il workflow legge tre segreti cifrati dalle impostazioni GitHub Actions del repository.

| Segreto | Valore |
|---|---|
| `ANDROID_SIGNING_KEYSTORE_B64` | Keystore PKCS#12 codificato Base64. Base64 serve per il trasporto del file binario. |
| `ANDROID_SIGNING_PASSWORD` | Password del keystore e della chiave. |
| `ANDROID_SIGNING_CERT_SHA256` | Impronta SHA‑256 del certificato, 64 caratteri esadecimali senza separatori. |

Lo script di firma accetta solo un APK con il certificato atteso e verifica allineamento e firma prima di creare l’asset. Il keystore rimane fuori dal checkout e viene rimosso dal runner al termine della fase di firma. I segreti di GitHub Actions sono in sola scrittura. Per poter ripristinare la firma anche dopo la perdita del repository, conserva un backup del keystore cifrato e della password in posizioni sicure e indipendenti. Archivia il certificato o la sua impronta insieme alle note interne di rilascio.

## Workflow

`Android CI` compila la build debug ed esegue test e lint a ogni push o pull request. `Android prerelease` è un workflow manuale avviato da `main`. Accetta un tag prerelease esistente e raggiungibile da `main`, controlla package e versione, riesegue le verifiche, firma l’APK e pubblica la pre-release con l’APK, il file SHA‑256 e la guida. Il processo di compilazione ha permessi GitHub in sola lettura e la pubblicazione avviene in un job distinto.

Per un aggiornamento della stessa app, prepara la nuova versione, verifica il codice, crea il tag corrispondente e avvia il workflow con quel tag. La rubrica **Assets** della GitHub Release contiene il file installabile e la guida.

## Build locale firmata

Con Java 17, SDK Android 36 e Build Tools 35.0.0, imposta `ANDROID_HOME` e carica i tre parametri di firma nell’ambiente della sessione da una fonte sicura. Lo script usa `ANDROID_SIGNING_KEYSTORE` come percorso locale, `ANDROID_SIGNING_PASSWORD` e `ANDROID_SIGNING_CERT_SHA256`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
./scripts/sign-release-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk build/release/stealth-link.apk
```
