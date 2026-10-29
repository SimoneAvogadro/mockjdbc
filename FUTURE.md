# Future ideas

Improvements that were identified while documenting the library (see [FEATURES.md](FEATURES.md)) but are **not
implemented yet**. Each item says what is missing today and what a change would involve.

Items 12 to 25 come from a review (October 2026) of the driver against the way Boomi processes use it through the
Database (Legacy) and Database V2 connectors. Where an item describes connector behaviour it says when it was seen in a
run; the rest is to be confirmed with a test process. What mockjdbc deliberately will not do is listed at the end,
under [Not planned](#not-planned).

## Result set getters

### 1. SQL `NULL` in varchar columns

* **Today:** an empty value is SQL `NULL` only in columns with a declared type other than `varchar`; in `varchar` (or
  untyped) columns it is the empty string, so a `NULL` string cannot be mocked. The CSV reader (opencsv 2.3) does not
  tell an unquoted empty value from a quoted one (`""`).
* **Idea:** an opt-in marker for NULL in varchar columns (e.g. a configurable token such as `\N`), or a CSV reader that
  distinguishes unquoted empty values.

## Date and time handling

### 2. ISO-style default formats

* **Today:** the defaults are `dd-MMM-yy`, `HH:mm`, `yyyyMMdd HHmmss.SSS`. Month names are always English
  (`Locale.ENGLISH`, whatever the JVM locale), but the time default drops seconds, and the timestamp default requires
  milliseconds. Users have to call `setDateFormat` / `setTimeFormat` / `setTimestampFormat` in every thread.
* **Idea:** default to ISO patterns (`yyyy-MM-dd`, `HH:mm:ss`, `yyyy-MM-dd HH:mm:ss[.SSS]`), possibly accepting several
  patterns per getter.
* **Compatibility:** breaking for existing CSVs and `?params` keys that use the current formats; consider a major version
  or a switch to select the old behaviour.

### 3. Format settings shared across threads and reset by `reset()`

* **Today:** the formats are `ThreadLocal`, so they apply only to the calling thread, and `reset()` does not restore
  the defaults.
* **Idea:** a process-wide setting (with thread-local override if needed) and restore the defaults in `reset()`.

## Encoding

### 4. Explicit charset for `InputStream` resources

* **Today:** `addInMemoryTableResource(String, InputStream)` and `addInMemoryTableResource(int, InputStream)` read the stream
  with the default charset of the VM, like CSV files.
* **Idea:** add overloads taking a `Charset` for streams whose encoding differs from the VM default.

## Resources and connections

### 5. Stored procedures: cursors, several result sets, functions

* **Today:** `prepareCall` supports OUT parameters (signature resources with `in_`/`out_`/`inout_`/`ret_` columns) and
  one result set per call (see [FEATURES.md, section 3 c](FEATURES.md#c-stored-procedures)). Not supported: REF CURSOR
  OUT parameters (`getObject` returning a `ResultSet`), several result sets (`getMoreResults`), a procedure returning
  both OUT parameters and a result set (also IN parameters with a result set: with Database V2 the IN values are then
  not captured), update counts before the result set, and `getFunctionColumns` for functions.
* **Idea:** extra resources per procedure, e.g. `<proc>_<param>` for a cursor parameter and `<proc>_RS2`, `<proc>_RS3`
  for further result sets; a function signature served by `getFunctionColumns`.
* **Impact on Boomi unit tests:** processes using Oracle cursors, several result sets or the Database V2 *Execute
  Function* operation cannot be mocked yet (with an empty `getFunctionColumns` the operation probably binds no
  parameters; untested). Several result sets on a plain `PreparedStatement` (not a call) are item 16.

### 6. Database directories loaded lazily

* **Today:** for `jdbc::mock::<dir>` URLs the CSV files are read when `connect()` is called. After `reset()`, a connection
  that is already open no longer sees those tables (new connections reload them).
* **Idea:** look up the directory on demand (or re-register it after `reset()`) so open connections keep working.

### 7. Table-name detection

The shared identifier rule (digits, `_`, quotes, schema-qualified names, first `FROM` outside parentheses, INSERT
without a column list) is done (`SqlNames`, see FEATURES.md section 3a). Still not recognised, so they need a
`-- QUERY_ID:` comment:

* **Today:** T-SQL forms with a word between the keyword and the table: `UPDATE TOP (10) t` (gives `TOP`),
  `DELETE TOP (5) FROM t`; `MERGE INTO t`;
  `UPDATE ... FROM` with an alias (`UPDATE o SET ... FROM orders o`, gives the alias `o`); temporary tables (`#tmp`) and
  quoted names with spaces (`[order details]`).
* **Idea:** skip `TOP (n)` / `TOP n [PERCENT]`, add MERGE; for `#tmp` and spaces, decide a resource-name mapping
  (`#` and spaces clash with the `#n` occurrence and the lookup separators).

### 8. Precedence between files and in-memory resources

* **Today:** if both a registered file and an in-memory resource exist for the same name, the **file wins**
  (step resources always win over both).
* **Idea:** decide whether in-memory should override files, since it is registered closer to the test; document the
  decision either way.

## Boomi runtime

### 9. Jar files of a removed library stay locked (unresolved)

* **Today:** on Windows, after a mockjdbc jar is removed from a Custom Library, the Boomi cleanup reports
  `Unable to remove un-deployed jar file` for it and for the other jars of the same library (also data-only jars), in
  the Legacy (`userlib\database`) and in the Database V2 folder. Stopping the Atom, deleting the jars and restarting is
  required (documented in FEATURES.md, section 11).
* **Done on the driver side:** no driver class in ThreadLocals (2.0.1), `/tables/` read from jars without caching,
  no registration in `DriverManager` inside Boomi. With these, after a forced GC one of the two driver class loaders
  was released (impossible with b12), but the runtime still keeps the removed jar open: the remaining reference is held
  by Boomi itself.
* **Guarded by a test:** `JarReleaseTest` loads the library as Boomi does (a class loader of its own under a fake
  runtime, a pooled thread that outlives it, no `close()`), uses every feature of the driver and fails if the class
  loader stays referenced or a jar of the library stays open (checked on `/proc/self/fd` on Linux, by deleting the jars
  on Windows). It catches a registration in `DriverManager` inside Boomi, a driver object in a `ThreadLocal` and a jar
  resource read through a cached URL connection.
* **Legacy:** Boomi does not add a removed jar to `ignored_jars.txt` of `userlib\database`, so the jar stays on the
  classpath and the cleanup never tries to delete it.
* **Boomi cloud runtime:** the same happens there, where the runtime cannot be stopped or cleaned by the account. Seen in
  October 2026: after each library deploy the Database V2 connector used the new jar, while the Legacy connector kept
  running an old mockjdbc build (2.1.0 table-name detection and 2.2.0 stored procedures missing). Verify Legacy changes
  on a local Atom.
* **Idea:** investigate with Boomi support (class loader lifecycle of Custom Libraries on Windows); nothing else known
  to change in the driver.

## Build

### 10. Logging dependencies and the AspectJ tracing aspect

* **Today:** the build targets Java 8 and works on current JDKs (AspectJ weaving via `dev.aspectj:aspectj-maven-plugin`).
  `logback-classic`/`logback-core` are still declared as normal dependencies, so they end up in the classpath of every
  application using the driver, and the tracing aspect (`AspectLogger.aj`) requires the AspectJ runtime (`aspectjrt`), one more jar
  to install wherever the driver runs (e.g. Boomi).
* **Idea:** move logback to the `test` scope, and decide whether the TRACE logging of every public method is still worth
  the AspectJ build step and runtime.

## Documentation

### 11. Publication on Maven Central

* **Today:** no artifact is published on Maven: the jar comes from the GitHub releases or from `mvn package`, and the
  docs deliberately describe only that.
* **Idea:** when a release is published (coordinates `me.avogadro.mockjdbc:mockjdbc`, as in `pom.xml`), add the Maven /
  Gradle dependency snippet back to the README "Installation" section and to FEATURES.md section 1.

## Statement parameters

### 12. Silent no-op setters reachable from Boomi (high priority)

* **Today:** many setters inherited from `PreparedStatementAdapter` do nothing and capture nothing (list in
  [FEATURES.md, section 10](FEATURES.md#10-resolution-order-and-good-to-know)). **CLOB** and **BLOB** fields of a
  Legacy profile are expected to be bound through the stream setters (`setCharacterStream`, `setBinaryStream`), which
  are no-ops, so their value would be captured as empty (to be confirmed with a test process). Database V2 binds by
  the column types that `getColumns` reports, and mockjdbc reports only 9 types (`VARCHAR`, `INTEGER`, `BIGINT`,
  `DOUBLE`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`), so the other no-op setters are not expected to be
  reached today.
* **Idea:** capture the stream setters: read the `Reader` / `InputStream` into the parameters string (text for a CLOB,
  Base64 or hex for a BLOB). If mockjdbc adds CLOB, NVARCHAR, BLOB or JSON column types (see item 20), the setters they
  can make reachable must capture too: `setClob`, `setNString`, `setBlob`, `setBinaryStream(int, InputStream)` and
  `setNClob`. Optionally, make the remaining unimplemented methods throw instead of silently doing nothing.
* **Impact on Boomi unit tests:** a Legacy write with a CLOB or BLOB field cannot be checked: the field is always empty
  in `mockjdbc_<name>_PARAMS`.

### 13. Typed capture (low priority)

* **Today:** a captured value is its `toString()` only. That is enough when the process already passed DEV against the
  real database: the unit test only has to capture the value, the types were checked there.
* **Idea (from the user):** for full automation (unit tests with mockjdbc first, then the first DEV runs), a process
  property such as `mockjdbc##capture_user_types=true` would record each value with the type it
  was bound with, e.g. `42:Int`, `42:String`, `NULL` for `setNull`, `<unspecified>` for a parameter never bound. The
  values become unambiguous, also NULL versus empty string and commas inside values.
* **Impact on Boomi unit tests:** a test could check that a number is bound as a number and a missing field as `NULL`,
  before the process ever runs against the database.

## Connections and transactions

### 14. `Connection.isValid()` returns false (high priority, likely a bug)

* **Today:** `isValid(timeout)` always returns `false`. A connection pool with no validation query validates a
  borrowed connection with `isValid(timeout)`, so a Database V2 connection with pooling and test-on-borrow fails with
  `isValid() returned false`.
* **Idea:** return `true` while the connection is open (`false` after `close()`).
* **Workaround today:** set the validation query `SELECT 1` (mockjdbc answers a SELECT without FROM with one row), or
  disable pooling in the test connection.

### 15. Commit and rollback (not planned)

Out of the goals of mockjdbc: see [Not planned](#not-planned).

## Results of statements

### 16. Multiple result sets and `getUpdateCount()` (medium, latent)

* **Today:** on `CsvPreparedStatement`, `getUpdateCount()` returns `0` and `getMoreResults()` returns `false`
  (`CsvCallableStatement` already returns `-1`). A caller that follows the JDBC contract and calls `getMoreResults()`
  until `getUpdateCount()` is `-1` would never end, for example with a Legacy select profile of two or more RESULT_SET
  blocks (not reproduced).
* **Idea:** follow the JDBC contract: `getUpdateCount()` is `-1` when the current result is a result set or there are
  no more results. Several result sets for procedures are item 5.

### 17. Update counts and generated keys (medium)

* **Today:** `executeUpdate` returns 1 for a recognised table (0 otherwise), and `getGeneratedKeys()` is always empty.
  The Legacy response does not show them. The Database V2 response document has `"Rows Effected"` for Standard and
  Dynamic Update/Delete and Upsert, `"Inserted Id"` for inserts and `"No of records in batch"` for batches, so the count
  matters when the process maps these fields or decides on them. A numeric step resource can set the count
  (FEATURES.md section 5).
* **Idea:** resources `mockjdbc_<name>_rowcount[#n]` (affected rows) and `mockjdbc_<name>_keys[#n]` (a CSV of the
  generated keys), per name and occurrence.
* **Impact on Boomi unit tests:** processes that route on `Rows Effected` (e.g. "no row updated: insert instead") or
  use the inserted ID downstream could be tested.

## Failure paths

### 18. Fault injection (done in 2.3.0, ideas left)

* **Done:** error resources make a statement throw the `SQLException` its mock declares, per name, occurrence or bound
  values, also in batches; a lost connection is an error with SQLState `08` on the steps that fail
  ([FEATURES.md, section 12](FEATURES.md#12-simulating-errors-sqlexception)).
* **Idea left: an error when connecting.** `mockjdbc_##CONNECT` (no counter) would make every new connection fail, as
  when the database is down. Useful only for a process that has a Try/Catch for "database down" around a Database V2
  step with *Return Application Error Responses* on: a statement error then comes out as an error document, so that
  Try/Catch cannot be reached with error resources today. Not planned until a real process needs it; with connection
  pooling it also needs item 14.
* **Ideas left:** an error on `commit()` and in the middle of reading a result set (after n rows); presets per vendor
  (deadlock, unique key violation, timeout, ...), to be checked against the vendor documentation.

## Resource lookup

### 19. A mock key that matches nothing (to investigate)

* **Today:** a lookup that finds no resource returns an empty result set without error (and a write is captured
  anyway). Failing would catch typos in the keys, but the current behaviour lets a unit test cover only some
  interactions and ignore the others (partial unit tests): little invasive, a good design even if not elegant.
* **Idea:** evaluate a middle ground: a warning (log line or property listing the unmatched names) or an opt-in strict
  mode.
* **Note:** Database V2 needs a header for every table it writes or filters. Without one, the Standard operations
  silently skip binding the fields, Dynamic Insert has no columns, and Standard Get throws
  `Keys Provided in Input Request is not matching the Request Profile`.

## Metadata

### 20. Database metadata fidelity

* **Today:**
  * `getDatabaseProductName()` is `MockJDBC`, so a connector that adapts its SQL or its binding to the database product
    (Oracle, MySQL, PostgreSQL, SQL Server: identity retrieval, upserts, date binding) takes its generic path, and tests
    exercise the generic behaviour only.
  * `getColumns` reports 9 types only (see item 12).
  * `getFunctionColumns` is empty (see item 5).
  * `supportsBatchUpdates()` returns `false`, so a connector that checks it before batching (for example for the Legacy
    *Enable Batching* option) runs row by row under the mock, and its batch behaviour is not tested.
* **Idea:** a configurable product name (to evaluate with care: a product-specific path may run vendor SQL or call
  methods the mock does not support), more column types.
* **Decided, next changes:** `supportsBatchUpdates()` = `true`, to be verified with a unit test of a Legacy operation
  with *Enable Batching* on. mockjdbc already implements `addBatch()` / `executeBatch()` (one `executeUpdate` per row,
  each captured as usual, `_PARAMS#n` included; a failing row gives a `BatchUpdateException`). What would change for a
  test: the writes run when the batch is flushed (after the documents of the batch, not one by one), and a failure
  fails the whole batch instead of one document.

## Test tooling

### 21. Statement journal

* **Idea:** an ordered log of every statement (name, kind, bind values also for SELECTs, update count, batch
  boundaries, commit/rollback), readable from Java and from a Boomi property.
* **Impact on Boomi unit tests:** checks on the order of the statements, on duplicates and on N+1 query patterns.

### 22. SQL text capture (small)

* **Idea:** `mockjdbc_<name>_SQL[#n]` with the SQL text that was executed, mainly to debug dynamic SQL (not to validate
  it, see [Not planned](#not-planned)).

### 23. Recorder mode

* **Idea:** a delegating URL that wraps the real driver during a DEV run and writes the result sets as CSV mocks and
  the `_PARAMS` as expected values, with masking of sensitive data.
* **Impact on Boomi unit tests:** the mocks and the expected values of a test come from a real run instead of being
  written by hand.

## Limits

### 24. Minor limits

* **Today:**
  * the parameters of a statement are kept in a fixed `Object[500]`: more than 499 binds throws
    `ArrayIndexOutOfBoundsException`;
  * the static in-memory map of captures is never cleared inside Boomi, so it grows slowly on a long-lived Atom;
  * Dynamic Process Properties have no documented size limit (the Boomi docs allow an unlimited number; the values
    live in memory), so very large mocks or journals are bounded by the memory of the runtime.
* **Idea:** a growing parameter list.
* **Decided, next changes:** make `inMemoryTableResources` a synchronized map, for consistency under concurrent
  executions (`reset()` must keep it synchronized: today it assigns a new plain `HashMap`). The memory growth is not a
  concern: clearing the captures is not planned.
* **The in-memory captures inside Boomi (how the driver works today):** every write puts `<name>_PARAMS` and
  `<name>_PARAMS#n` (and an injected error `<name>_ERROR` and `<name>_ERROR#n`) both in the static
  `inMemoryTableResources` map and in the Dynamic Process Properties. Inside Boomi the process reads only the
  properties, so the map is never used there: it keeps one entry per distinct name and occurrence, shared by all the
  executions of the runtime, and is only emptied by `reset()`, which Boomi never calls. The map is a plain `HashMap`
  written by concurrent executions without synchronisation. Lookups check the map before the properties, but the map
  holds only `_PARAMS` and `_ERROR` names, which are never mock names.

### 25. Parameters kept until `clearParameters()` (JDBC)

* **Today:** a `PreparedStatement` clears its parameters after every execution, so a retry on the same statement
  without setting them again runs with no parameters (the `?params` key and `_PARAMS` are then empty;
  [FEATURES.md, section 12](FEATURES.md#12-simulating-errors-sqlexception)). JDBC keeps the values until
  `clearParameters()` or a new `setXxx`. Boomi connectors set the parameters for every document, so Boomi is not
  affected.
* **Idea:** keep the parameters until `clearParameters()`, as JDBC does. It changes a long-standing behaviour: check
  that `_PARAMS` stays right for a statement executed again with fewer parameters.

## Not planned

mockjdbc will **not parse, validate or execute SQL**. The SQL is validated during development by runs in the DEV
environment against the real database; the goal of unit tests with mockjdbc is to make the execution repeatable in a
deterministic way, not to validate the SQL (see
[FEATURES.md, section 10](FEATURES.md#what-mockjdbc-does-not-do)). So these options are out of scope:

* **SQL parsing or validation:** syntax, columns and types are checked by the DEV runs on the real database.
* **`WHERE` (or `ORDER BY`, aggregate) evaluation on CSV rows:** a query returns the rows of its mock; use `?params`
  or `#n` resources for different results.
* **Embedded SQL engine (H2, HSQLDB):** it would execute a different SQL dialect, which proves nothing about the real
  database and makes the mocks harder to write.
* **Static SQL lint:** it belongs to the development and review of the SQL, not to the unit tests.

mockjdbc has **no transaction logic** either: `setAutoCommit`, `commit()` and `rollback()` do nothing, and a write is
captured when it is executed, so a rolled-back write still shows as captured. Keeping the captures pending until
`commit()` and dropping them on `rollback()` would be complex, and Boomi unit tests rarely depend on it: out of the
goals of mockjdbc for now. Injecting an error on `commit()` (item 18) is a different thing and stays possible.
