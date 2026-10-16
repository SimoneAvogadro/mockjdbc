# Dataset su filesystem: analisi di fattibilità e di impatto

> Stato: **analisi con decisioni prese** (sezione 9), nessuna modifica al codice. Riferimento: versione 2.0.1
> (`master` @ `7eee4b7`). Versione obiettivo: **2.2.0**.

## 1. Obiettivo

Rendere lo unit testing di Boomi (e di Java) più ingegnerizzato: i dati di test stanno su **runtime/Atom dedicati ai
test**, organizzati **per cartella**, e ogni test indica da quale cartella leggere i CSV. Chi non vuole cablare i dati
nel processo Boomi (Message + Set Properties) li tiene su filesystem, versionabili con git e modificabili senza
redeploy.

Perimetro:

* **in lettura**: piena parità tra i dataset su file e quelli in memoria / Dynamic Process Property (DPP): nome
  tabella, `-- TESTCASE:`, procedure, `#n`, `?parametri`, `?parametri#n`, step `##STEPn` (anche come conteggio righe
  di `executeUpdate`), `getColumns`;
* nomi file **case-insensitive anche su Linux**;
* **cartelle assolute** indicate all'inizializzazione (Java) o all'inizio del test (Boomi);
* **fuori perimetro**: la cattura dei parametri in uscita (`<name>_PARAMS`, `_PARAMS#n`) resta com'è, in memoria e in
  DPP.

## 2. Stato attuale

Oggi le fonti di file sono tre, tutte con la stessa limitazione: una mappa `nome tabella in minuscolo → File`.

| Fonte | Come si attiva | Limiti |
|-------|----------------|--------|
| `addTableResource(name, File)` | Java, un file per volta | accetta percorsi assoluti, ma va chiamata per ogni tabella |
| `jdbc::mock::<dir>` | URL di connessione | solo cartelle **relative al classpath** (`ClassLoader.getResource`, `MockJdbcDriver.java:380`), non dentro un jar, lette **una volta al `connect()`** |
| `/tables/<name>.csv` | classpath, anche in un jar | cartella unica e fissa, non selezionabile per test |

### Gap di parità in lettura

La risoluzione è in `CsvStatement.createResultSet` (`CsvStatement.java:257`):

| Funzione | In memoria / DPP | Su file oggi |
|----------|:---:|:---:|
| nome tabella, `-- TESTCASE:`, procedura | sì | sì (solo nome esatto in minuscolo) |
| `name#n` (query ripetute) | sì | **no** |
| `name?p1,p2` e `name?p1,p2#n` | sì | **no** |
| step `##STEPn` (risultato e conteggio righe) | sì | **no**: la decisione "c'è uno step?" guarda solo la memoria (`CsvStatement.java:157`, `CsvPreparedStatement.java:96`) |
| `getColumns` (metadata, Database V2) | sì | sì (passa da `loadTable`) |
| nomi file case-insensitive | n/a | **no** su Linux; con `jdbc::mock::` il nome non è nemmeno portato in minuscolo (`MockJdbcDriver.java:407`), quindi `Users.csv` non viene mai trovato |
| encoding | String, nessun problema | charset di default della JVM; il BOM UTF-8 (tipico dei CSV salvati da Excel) finisce nel nome della prima colonna, perché `trim()` non lo toglie |

### Incoerenza nell'ordine di priorità

* Il codice: se esiste un **file** registrato per il nome base, **vince sempre** (`CsvStatement.java:284` e 306).
  Questo nasconde anche le varianti in memoria `users#2`, `users?Smith` e le DPP `mockjdbc_users`.
* FEATURES.md, sezione 7: "the file wins" (coerente col codice).
* FEATURES.md, sezione 10: `name?params#n → … → name (in memory) → registered file → /tables/`, che fa pensare
  il contrario (incoerente col codice).
* FUTURE.md, punto 8, lo segnala già come decisione aperta.

### Boomi

In Boomi i dati arrivano solo da DPP o da `/tables/` in un jar della Custom Library. Non esiste un modo per indicare
una cartella. Il connettore può tenere le connessioni in pool, quindi tutto ciò che viene letto al `connect()`
(come oggi `jdbc::mock::`) rischia di essere vecchio rispetto al test in corso.

## 3. Fattibilità

**Fattibile, senza nuove dipendenze e restando su Java 8.** Il punto chiave è introdurre un unico meccanismo di
risoluzione (catena di fonti) usato da query, step, `executeUpdate` e metadata. Oggi la logica è ripetuta in tre
punti e ogni fonte fa le cose a modo suo.

## 4. Proposta di design

### 4.1 Come si indica la cartella

Due livelli: una **radice** legata al runtime e una **cartella del test**, relativa alla radice o assoluta.

| Livello | Java | Boomi | Note |
|---------|------|-------|------|
| radice | `MockJdbcDriver.setTableRoot(File)`, oppure URL `jdbc::mock::/home/simonea/cliente1/unitTest/mockDB` / `jdbc::mock::C:\Dati\Cliente1\...` | URL della connessione, impostato con un'**estensione d'ambiente** sull'ambiente di test; in alternativa `-Dmockjdbc.root=...` nelle opzioni JVM del runtime o la variabile `MOCKJDBC_ROOT` | l'URL con percorso assoluto oggi dà errore, quindi l'estensione è retrocompatibile |
| cartella del test | `MockJdbcDriver.setTableFolders("T_014", "_common")` | DPP `mockjdbc#FOLDER` = `T_014;_common` (stessa convenzione di `mockjdbc#RESET`) | percorsi relativi alla radice, oppure assoluti |

**Precedenza della radice** (decisione 2: tutte e tre le fonti), dalla più specifica alla più generica:

1. URL della connessione con percorso assoluto (vale solo per quella connessione);
2. `MockJdbcDriver.setTableRoot(File)` (Java, per tutto il driver; azzerata da `reset()`);
3. proprietà JVM `mockjdbc.root`;
4. variabile d'ambiente `MOCKJDBC_ROOT`.

**Quali cartelle si leggono:**

* se è indicata una cartella del test (`mockjdbc#FOLDER` in Boomi, che ha precedenza, oppure `setTableFolders` in
  Java), si leggono **solo** quelle cartelle, nell'ordine dato, relative alla radice se non assolute;
* se non è indicata nessuna cartella ma c'è una radice, si legge la radice stessa: è lo stesso significato di oggi di
  `jdbc::mock::<dir>`, dove i CSV stanno direttamente nella cartella;
* una cartella relativa senza nessuna radice configurata è un errore (decisione 4).

`jdbc::mock::<dir>` con percorso **relativo** mantiene il significato attuale (cartella del classpath), ma letta in
modo lazy e con le stesse regole sui nomi file.

Perché due livelli:

* il processo di test Boomi contiene solo `T_014`, non `C:\Dati\...`: lo stesso test gira su un Atom Windows e su
  uno Linux, e il percorso fisico sta nell'estensione d'ambiente, cioè dove Boomi si aspetta la configurazione del
  runtime;
* le DPP sono **per esecuzione** e si propagano ai sottoprocessi (Process Call): due test in parallelo sullo stesso
  Atom possono puntare a cartelle diverse senza interferire. Un'impostazione statica Java, invece, sarebbe condivisa da
  tutte le esecuzioni dell'Atom;
* in un harness che esegue più casi in sequenza nella stessa esecuzione basta cambiare `mockjdbc#FOLDER` (insieme a
  `mockjdbc#RESET`) tra un caso e l'altro.

La cartella viene **letta a ogni query**, non al `connect()`. Questo funziona anche con il pool di connessioni e
risolve FUTURE.md, punto 6.

### 4.2 Nomi dei file

**Indice della cartella invece di lookup diretti.** Si legge l'elenco dei file `*.csv` della cartella; ogni nome
viene decodificato e portato in minuscolo, ottenendo una mappa `nome risorsa → File`. Conseguenze:

* case-insensitive su ogni sistema operativo, con un solo `listFiles()` per cartella invece di molte prove sul disco;
* due file che differiscono solo per maiuscole (`Users.csv` e `users.csv`, possibile su Linux) sono un **errore
  esplicito**, non una scelta casuale;
* l'indice può essere tenuto in cache e invalidato quando cambia il `lastModified` della cartella (aggiunta o
  rimozione di file); il **contenuto** viene invece sempre riletto, così i dati si modificano senza riavviare nulla.

**Separatore dei parametri: `?` oppure `@` (decisione 3).** `?` è vietato nei nomi file su Windows, quindi si
introduce `@` come separatore alternativo, **equivalente ovunque**: in memoria, nei nomi file e nelle DPP.
`users@Smith,34` e `users?Smith,34` sono la stessa risorsa.

Perché `@` e non `§`:

| | `@` | `§` |
|-|-----|-----|
| ammesso nei nomi file Windows, Linux, macOS | sì | sì |
| ASCII (nessun problema di encoding tra filesystem, git, zip, locale del sistema, UI di Boomi) | sì | **no**: è fuori ASCII e su Linux con locale non UTF-8 o in uno zip può diventare illeggibile |
| digitabile su tastiera italiana e americana | sì | solo italiana |
| presente nei nomi di tabella | praticamente mai (in SQL Server `@t` è una variabile tabella, che non arriva al driver come `FROM @t` da Boomi) | mai |

Regole:

* il separatore è il **primo** `?` o `@` dopo il nome: i parametri possono contenere a loro volta `@` (es. un'email),
  `users@mario@example.com` = tabella `users`, parametri `mario@example.com`;
* in memoria la chiave viene normalizzata in inserimento e in lettura (`@` → `?` sul primo separatore), quindi
  `addInMemoryTableResource("users@Smith", ...)` e una query con parametro `Smith` si trovano;
* nelle DPP si prova prima la forma con `?`, poi quella con `@`, ognuna come scritta e poi in minuscolo
  (`mockjdbc_users?Smith`, `mockjdbc_users?smith`, `mockjdbc_users@Smith`, `mockjdbc_users@smith`);
* nei nomi file entrambe le forme sono accettate (su Windows si potrà usare solo `@`); due file che indicano la stessa
  risorsa con separatori diversi sono una collisione e quindi un errore;
* la cattura in uscita (`_PARAMS`) non cambia.

**Caratteri residui.** Anche con `@` possono restare caratteri vietati su Windows, soprattutto nei valori dei
parametri: `:` (negli orari, formato `HH:mm`), `/`, `\`, `*`, `"`, `<`, `>`, `|`. Solo per i **nomi file** questi
si scrivono come `%XX` (byte UTF-8), insieme a `%` stesso, ai caratteri di controllo e a spazio o punto finali (che
Windows elimina). È un caso raro: nei casi comuni il nome resta leggibile.

| Risorsa | File |
|---------|------|
| `users` | `users.csv` (o `Users.csv`, `USERS.csv`) |
| `users#2` | `users#2.csv` |
| `users?Smith,34` | `users@Smith,34.csv` |
| `users?Smith,34#2` | `users@Smith,34#2.csv` |
| `events?10:30` | `events@10%3A30.csv` |
| `##STEP3` | `##STEP3.csv` (contenuto `5` = 5 righe per `executeUpdate`) |
| `-- TESTCASE: T_014a` | `T_014a.csv` |
| `dbo.users` | `dbo.users.csv` |

### 4.3 Ordine di risoluzione unificato

**Prima la specificità del nome, poi la fonte**: per ogni nome candidato, dal più specifico al più generico, si
provano tutte le fonti in ordine.

```
nomi:   name?params#n → name?params → name#n → name        (`?` e `@` equivalenti)
fonti:  memoria → DPP (come scritto, poi minuscolo) → cartelle del test (in ordine) → file registrati / jdbc::mock:: → /tables/
```

Esempio: `users#2.csv` nella cartella e `mockjdbc_users` come DPP. La seconda query su `users` usa il file, perché è
più specifico; la prima e la terza usano la DPP. Così un dato in memoria o in una DPP può **sovrascrivere una singola
tabella** di una cartella senza nascondere le varianti più specifiche.

La stessa catena decide anche se c'è uno step (`##STEPn`) e restituisce il suo contenuto a `executeUpdate`.

### 4.4 Lettura dei file

* **Encoding**: UTF-8 come default per la nuova fonte cartella, BOM UTF-8 rimosso, charset configurabile
  (`mockjdbc#CHARSET` / `setTableCharset`). Per le fonti esistenti vedi sezione 6.
* File aperto, letto e chiuso subito: nessun lock su Windows.
* Log di debug con la sequenza di nomi e fonti provate: indispensabile quando un harness "non trova" un dato.

### 4.5 Cartelle a strati

`mockjdbc#FOLDER = T_014;_common`: prima i dati del test, poi quelli comuni (anagrafiche, tabelle di decodifica).
Separatore fisso `;`, perché `File.pathSeparator` è `:` su Linux e confligge con `C:`.

### 4.6 Errori

* Cartella indicata ma inesistente o non leggibile (decisione 4): **errore esplicito** (`SQLException` sulla query). Un harness
  configurato male deve fallire, non restituire tabelle vuote.
* Tabella non trovata: resta un result set vuoto (comportamento attuale), con il log dei tentativi.

## 5. Scenario Boomi: runtime dedicato ai test

```
/srv/mockdb/                         ← radice (estensione d'ambiente: jdbc::mock::/srv/mockdb)
├── _common/
│   ├── countries.csv
│   └── currencies.csv
├── ProgettoA/
│   ├── T_001_nuovo_cliente/
│   │   ├── customers.csv
│   │   └── customers#2.csv
│   └── T_002_cliente_esistente/
│       └── customers@C042.csv
```

Processo di test:

1. Set Properties: `mockjdbc#FOLDER` = `ProgettoA/T_001_nuovo_cliente;_common`.
2. Process Call del processo sotto test (connessione con mockjdbc tramite estensioni, come oggi).
3. Verifica di `mockjdbc_customers_params` (invariato).

Il processo sotto test resta identico. Il test contiene solo il nome della cartella e le asserzioni; i dati sono
file versionabili.

## 6. Impatto

### Codice

| Componente | Modifica | Peso |
|------------|----------|------|
| nuovo `resources/TableResolver` (catena di fonti) | nomi candidati × fonti, `exists(name)` e `read(name)` | medio |
| nuovo `resources/FolderTableSource` | indice, codifica dei nomi, cache, lettura UTF-8 con BOM | medio |
| `CsvStatement` (`query`, `createResultSet`, `loadTable`) | delega al resolver; la decisione sugli step usa il resolver | medio |
| `CsvPreparedStatement.executeUpdate` | step e conteggio righe dal resolver; **cattura `_PARAMS` invariata** | basso |
| `MockJdbcDriver` | API `setTableRoot`/`setTableFolders`/charset, `reset()` le azzera, URL con percorso assoluto, `jdbc::mock::` letto in modo lazy, correzione del minuscolo | medio |
| `MockConnection` | porta il contesto della connessione (radice dall'URL) oltre alla mappa dei file | basso |
| `MockDatabaseMetaData.getColumns` | nessuna: beneficia del resolver tramite `loadTable` | nullo |
| `BoomiExecutionUtil` | costanti e lettura di `mockjdbc#FOLDER` / `mockjdbc#CHARSET` (riusa `getDynamicProcessProperty`) | basso |

Stima: circa 400–600 righe di codice più i test; nessuna nuova dipendenza.

### Compatibilità (cambi di comportamento)

1. **Priorità file/memoria** (decisione 1: nuovo ordine per tutte le fonti): oggi un file registrato vince su memoria,
   DPP e varianti `#n`/`?`. Con l'ordine unificato vincono memoria/DPP e la specificità del nome. Il risultato cambia
   solo se lo stesso nome è definito sia su file sia in memoria/DPP, configurazione che oggi non ha senso perché il
   dato in memoria viene ignorato. Il nuovo ordine allinea il codice alla sezione 10 di FEATURES.md e nessun test
   esistente verifica "il file vince". Va comunque scritto nel CHANGELOG e chiude FUTURE.md, punto 8.
2. **Encoding**: UTF-8 per default anche per `addTableResource` / `/tables/` / `jdbc::mock::` romperebbe chi usa file
   in windows-1252 su un Atom Windows. Proposta: UTF-8 solo per la nuova fonte, charset configurabile per tutte, e
   cambio del default rimandato a una major.
3. **`jdbc::mock::<dir>` lazy**: le connessioni aperte continuano a vedere le tabelle anche dopo `reset()` (oggi no).
   È un miglioramento, senza rischi pratici.
4. **`jdbc::mock::` case-insensitive**: `Users.csv` diventa visibile; prima era ignorato. Correzione di un bug.

5. **Separatore `@`**: una chiave in memoria o una DPP che contiene già `@` dopo il nome (es. `-- TESTCASE: a@b`)
   viene ora letta come "risorsa `a` con parametri `b`". La normalizzazione è simmetrica, quindi `a@b` registrato e
   `a@b` cercato continuano a trovarsi; cambia solo che diventa equivalente anche ad `a?b`.

Versione (decisione 5): **2.2.0**, nuove funzioni senza rotture di compatibilità. `VERSION_MINOR` passa a 2 e la
metadata del driver riporta `2.2`. Nota: non esiste una 2.1.0 (ultimo tag `mockjdbc-2.0.1`).

### Documentazione

FEATURES.md (sezioni 7, 8, 10, 11, nuova sezione "cartelle di dataset"), TUTORIAL.md (variante con dati su file),
FUTURE.md (chiudere i punti 6 e 8, aggiornare il punto sul charset), CHANGELOG.md, README (key features).

### Test

Tutti eseguibili nella CI Linux attuale:

* parità: ogni riga della tabella della sezione 2 con dati su file;
* case-insensitive su Linux, collisioni `Users.csv` / `users.csv`;
* separatore `@` equivalente a `?` in memoria, DPP e file, parametri che contengono `@`, collisione
  `users?x.csv` / `users@x.csv`;
* codifica `%3A` e degli altri caratteri residui, nomi con `#` e `.`;
* priorità memoria > DPP > cartella > file registrati, cartelle a strati;
* BOM e charset;
* Boomi: `mockjdbc#FOLDER` con `FakeExecutionUtil`, cambio cartella a metà esecuzione;
* cartella mancante → errore;
* regressione: tutti i test esistenti invariati (salvo eventuale decisione sul punto 1).

Da verificare manualmente su un Atom Windows: percorsi UNC, utente del servizio, percorsi lunghi oltre 260 caratteri.

## 7. Rischi e limiti

* **Atom Cloud di Boomi**: il filesystem non è controllabile né popolabile dall'utente. La funzione ha senso su Atom
  privati o Molecole; per il cloud restano DPP e `/tables/` nel jar.
* **Molecole**: la cartella deve essere su storage condiviso o replicata su ogni nodo.
* **Permessi**: l'utente del servizio Atom deve poter leggere la cartella (attenzione a LocalSystem e alle share di
  rete su Windows).
* **Path traversal** (`..` in `mockjdbc#FOLDER`): in un runtime di test è accettabile; eventualmente opzione per
  limitare le cartelle sotto la radice.
* **CSV da Excel**: oltre al BOM, Excel con impostazioni italiane salva con `;` come separatore, mentre la libreria
  usa `,`. Un separatore configurabile sarebbe un'estensione naturale, ma è fuori da questo perimetro.

## 8. Fuori perimetro, per dopo

* Valori attesi su file (es. `customers_PARAMS.expected`) letti dall'harness: lo stesso indice potrebbe servire.
* Separatore CSV configurabile (`;` di Excel con impostazioni italiane).

## 9. Decisioni

| # | Tema | Decisione |
|---|------|-----------|
| 1 | Ordine unificato (specificità del nome, poi fonte) | per **tutte** le fonti, incluse `addTableResource`, `jdbc::mock::` e `/tables/` |
| 2 | Radice | **tutte e tre** le fonti: URL > `setTableRoot` > `-Dmockjdbc.root` > `MOCKJDBC_ROOT` (sezione 4.1) |
| 3 | Leggibilità dei nomi | `@` come separatore alternativo a `?`, equivalente in memoria, file e DPP; `%XX` solo per i caratteri residui nei nomi file (sezione 4.2) |
| 4 | Cartella mancante o non leggibile | **errore** |
| 5 | Versione | **2.2.0** |
