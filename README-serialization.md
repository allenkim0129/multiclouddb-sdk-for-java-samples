# Complex POJO serialization: preview and live accounts

This is customer code for evaluating the **existing** Map-based public API in
[microsoft/multiclouddb-sdk-for-java#122](https://github.com/microsoft/multiclouddb-sdk-for-java/pull/122).
It does not change the SDK, promise a new numeric contract, or implement partial updates.
Only synthetic orders are used. **No live operation runs by default.**

**App name:** Complex POJO Serialization. **Main class:**
[`com.multiclouddb.samples.serialization.ComplexPojoSample`](src/serialization/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java).
The `public static void main(String[] args)` method is in
`src/serialization/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java`.

## Team review: start with these five customer files

| Read in order | Review focus |
| --- | --- |
| [Order.java](src/serialization/java/com/multiclouddb/samples/serialization/Order.java) | What the application owns: nested POJOs and Java field types |
| [OrderFixtures.java](src/serialization/java/com/multiclouddb/samples/serialization/OrderFixtures.java) | Visible synthetic inputs; compare with the [independent JSON fixtures](src/serialization/resources/fixtures) |
| [CustomerSerializers.java](src/serialization/java/com/multiclouddb/samples/serialization/CustomerSerializers.java) | Actual Jackson/Gson settings, POJO -> Map and Map -> POJO conversion |
| [LiveSettings.java](src/serialization/java/com/multiclouddb/samples/serialization/LiveSettings.java) | External properties loading, primary-key configuration, live opt-in and legacy environment compatibility |
| [ComplexPojoSample.java](src/serialization/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java) | Actual SDK calls in `exercise`, catches/branching in `demonstrateFailureHandling`, diagnostics getters, comparison and cleanup |

Short walkthrough: run **preview**, follow one original order into `toDocument`,
and compare its Map/types and restored POJO to the JSON fixture. Then read `exercise`
as customer code: explicit `create`, optional missing-read/conflict/invalid-input
examples, `read`, replacement `update`, full `upsert`, partition-scoped `query`,
and owned-key cleanup. `errorSummary`/`diagnosticsSummary` are in that same file,
not hidden behind a client/error framework. `ExpectedDocuments` only supports
comparison and display; it is not the application serializer.

The intent is a small review surface for the team's typical application workflow,
not a claim that review is complete, the API is approved for customer delivery,
or three-year compatibility has been proven. PR merge/release and human review
remain separate decisions.

## Find and edit the inputs

| File under `src/serialization/` | Purpose |
| --- | --- |
| `java/com/multiclouddb/samples/serialization/Order.java` | Application POJOs (Java 17 records): order, customer/address, line items and enum |
| `java/com/multiclouddb/samples/serialization/OrderFixtures.java` | Explicit constructors and numeric/date literals; edit inputs here |
| `resources/fixtures/baseline.json` | Independent, visible expected document for the ordinary case |
| `resources/fixtures/precision.json` | Independent decimal/large-long expectations |
| `resources/fixtures/large-integer.json` | Independent beyond-Long expectation |
| `java/com/multiclouddb/samples/serialization/CustomerSerializers.java` | Customer-owned Jackson/Gson conversion settings |
| `java/com/multiclouddb/samples/serialization/ComplexPojoSample.java` | Public SDK create/read/update/upsert/query/delete workflow |

Both serializers use the same three fixtures (six cases). Each contains a nested
customer/address, `List<LineItem>`, string and nullable Maps, enum, nanosecond
`Instant`, `LocalDate`, null `note`/`giftMessage`, deliberately **absent** `coupon`,
an empty list, `long`, `BigInteger`, and `BigDecimal`. Baseline numeric values are
`9007199254740991`, `42`, and `125.50`. The precision case uses
`9007199254740993` and `12345.67890123456789`; the large-integer case uses
`18446744073709551617`. These risk cases deliberately exceed safe portable assumptions.

`exactSequenceText` and `exactTotalText` show an **explicit alternative representation**,
not an automatic repair of the numeric fields. String storage changes numeric query,
sorting, and aggregation semantics. A failing numeric comparison remains a failure.

Expected JSON is hand-authored, not generated from the outgoing Map or returned POJO.
Only runtime `runId` and workflow `revision` are substituted. Edit both constructor
inputs and expected JSON when intentionally changing a case.

## Build the exact SDK and sample (Windows PowerShell)

Install **JDK 17**, Maven 3.9+, and Git; set `JAVA_HOME` to that JDK and add their
executables to `PATH`. Start in this sample repository's root. The build downloads
dependencies, but needs no cloud credentials.

The pinned PR head was checked on 2026-11-16: **open, unmerged**,
`ef339fb6529a1b367987bff0b4884c4127708fd9`, from
`allenkim0129/multiclouddb-sdk-for-java`, branch
`allenkim0129-customer-serialization-squashed`. Moving PR heads are not followed.

```powershell
java -version
mvn -version
.\scripts\build-serialization-sample.ps1
```

The script checks out that SHA beneath `.local/serialization-sdk`, refuses modified
or differently pinned source and local `.mvn/maven.config` overrides, and installs
the SDK into **`.local/m2-ef339fb6529a1b367987bff0b4884c4127708fd9`**, not `~/.m2`.
It reads the actual versions from the pinned SDK POM:

| Artifact | Actual version |
| --- | --- |
| API, Cosmos provider, Dynamo provider | `0.1.0-beta.2-SNAPSHOT` |
| Spanner provider (existing samples' compilation/runtime dependency only) | `0.1.0-beta.1-SNAPSHOT` |
| Parent POM | `1-SNAPSHOT` |

It builds installed **JAR/POM artifacts**, compiles all samples, runs offline tests,
creates the executable fat JAR using the existing service-merging configuration, and
runs preview. It checks SHA256 equality of SDK build JARs, installed JARs, and sample-resolved
JARs, plus installed flattened POMs. Evidence is written to
`target/serialization-build-evidence.txt`; the complete dependency mediation tree is
`target/serialization-dependencies.txt`. No SDK source/test directory is put on the
sample runtime classpath. Merely selecting a SNAPSHOT version without this preparation
does **not** establish provenance.

The profile aligns Cosmos to the PR's **4.82.0** (rather than the sample default
4.81.0), keeps AWS **2.34.0**, and aligns Jackson components using the **2.18.9 BOM**
to match the pinned SDK's Azure dependencies rather than downgrading them to the
sample default 2.18.6. Gson is **2.15.0**, matching the pinned Spanner provider's
dependency so the customer serializer does not downgrade that runtime either.
No SDK shading or relocation changes are made.

For subsequent edits to **sample code only**, rebuild with the same isolated repository:

```powershell
$sha = 'ef339fb6529a1b367987bff0b4884c4127708fd9'
$repo = Join-Path $PWD ".local\m2-$sha"
mvn "-Pserialization-pr" "-Dmaven.repo.local=$repo" clean package
if ($LASTEXITCODE -ne 0) { throw 'Sample build failed' }
```

Quote each PowerShell `"-Dname=value"` argument. Do not copy the repository's older
`0.2.0-SNAPSHOT` override example for this experiment. SDK modifications require a
separately reviewed pin and a fresh provenance build.

The normal profile still uses the original published SDK versions. Small customer-side
Map-to-JSON conversions preserve the existing applications' JSON interfaces. Two
profile-selected `CursorSerialization` helpers adapt the incompatible released/PR cursor
signatures without reflective fallbacks. Always clean when switching profiles.

## Preview: no account required

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$main = 'com.multiclouddb.samples.serialization.ComplexPojoSample'
java -cp $jar $main preview
$LASTEXITCODE
```

No arguments also means preview. It prints each original POJO, expected JSON,
converted Map, leaf runtime types, restored POJO, exact differences, and PASS/FAIL.
Preview does **not even construct an SDK client**. Exit 0 means the configured
customer conversion checks passed, **not** that a cloud provider preserves the data.

The separate defaults probe explains why the configured mapping is necessary:

- Plain Jackson does not handle `java.time` without `JavaTimeModule`. The configured
  mapper emits ISO strings and reads untyped decimal values as `BigDecimal`.
- Plain Gson's reflective `java.time` handling fails on Java 17. The configured
  Gson supplies ISO adapters, preserves null fields with `serializeNulls()`, and uses
  `ToNumberPolicy.BIG_DECIMAL` for untyped Map numbers.
- Default Gson untyped numeric parsing uses `Double`: the displayed large-long and
  decimal comparisons expose precision loss **before the SDK is called**.

No blanket stringify/normalization, floating-point tolerance, or automatic fallback
is used to make precision cases pass. The comparator accepts numerically equivalent
Java number classes and decimal scale differences (`125.50` versus `125.5`); it
does not guarantee decimal scale preservation. Missing keys differ from explicit nulls.

## Live prerequisites and safety

**You choose and provision the targets beforehand.** Use a disposable test
container/table in your own account, not production data. This sample never calls
`ensureDatabase`, `ensureContainer`, or any table/database create/delete operation.
Live calls cost RU/request capacity and require your account's networking/firewall
access. There is no unlimited polling or unscoped table scan, but SDK retries and
network timeouts can add latency/requests; this is not a hard monetary/time budget.

| Provider | Required existing schema | Data permissions |
| --- | --- | --- |
| Cosmos DB for NoSQL | Database + container, partition key path **`/partitionKey`**, normal `id` | Read metadata plus item create/read/replace/upsert/query/delete on the test container; e.g. appropriately scoped Cosmos DB data-plane RBAC |
| DynamoDB | Physical table **`<MCDB_DATABASE>__<MCDB_COLLECTION>`**, String hash key **`partitionKey`**, String range key **`sortKey`** | `dynamodb:PutItem`, `GetItem`, `Query`, `DeleteItem` on that table; no Scan/CreateTable/DeleteTable needed |

The POJO sample follows the existing `.properties` naming convention but reads its
config **from disk**, not from the JAR classpath. Its secret
`src/main/resources/serialization-cosmos-cloud.properties` file is gitignored
and explicitly excluded from the default Maven resources; the serialization profile
includes only fixtures, static UI assets and logging config. Neither profile packages
this secret file. Other samples' historical classpath-config workflow is unchanged.
Never paste account keys into source, commands recorded in shell history, chat, logs,
or a JAR. Provider diagnostic logging is disabled in this profile. Errors print
operation, exception type and SDK error category, not native messages/causes that
could expose credentials. Diagnose access errors privately in your cloud tooling.

Required target names are restricted to 1-63 letters/digits/underscores/hyphens;
`multiclouddb.runPrefix` (or legacy `MCDB_RUN_PREFIX`) allows 1-32 letters/digits/hyphens. A fresh UUID is appended on every
run; all queries are partition-scoped to that unique run.

## Cosmos live (PowerShell)

To configure the target interactively, run in your current PowerShell terminal:

```powershell
.\scripts\configure-cosmos-sample.ps1
```

It asks for endpoint, database, container, and a **hidden primary key**, with no
authentication selection menu. It writes:

```text
src/main/resources/serialization-cosmos-cloud.properties
```

This sits alongside the other sample configs. The committed
[`serialization-cosmos-cloud.properties.template`](src/main/resources/serialization-cosmos-cloud.properties.template)
shows the expected fields; you can also copy and fill it in locally:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://YOUR-ACCOUNT.documents.azure.com/
multiclouddb.connection.key=YOUR-PRIMARY-KEY
multiclouddb.database=YOUR-DATABASE
multiclouddb.collection=YOUR-CONTAINER
multiclouddb.runPrefix=pojo-test
```

The filled file contains a **plaintext primary key**. It is gitignored via
`*-cloud.properties` and explicitly excluded from the JAR. The script prints
the full path, not the contents. Gitignore is not encryption or an absolute
guarantee: never force-add, upload, share, or paste the filled file. Protect access
to your local checkout and backups. Only the empty template belongs in Git.
Paste using right-click or Shift+Insert if Ctrl+V does not work.
Use the primary key, not the connection string. Empty keys are rejected.
The script does **not** export credentials to environment variables, rebuild the
JAR, provision resources, or run tests. Existing properties are replaced only after
typing `YES`. Run the Java command below directly in future terminals; there is no
per-terminal configuration step.

**Already used the earlier JSON setup?** Migrate its values and key once:

```powershell
.\scripts\configure-cosmos-sample.ps1 -Load
```

If the properties file already exists, `-Load` leaves it unchanged and reports its
location; Java validates it before connecting. Otherwise it migrates
`.local/cosmos-sample.json`. A keyless old JSON prompts for the key once. The old
JSON is deliberately not deleted and may still contain a secret; remove that
specific file yourself after checking the new configuration.

**Rebuild once after pulling the properties-support code** using the pinned build
script or the sample-only Maven command above. Later properties edits require
no rebuild. **`az login` is not needed** for primary-key authentication.
Run from the repository root:

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$main = 'com.multiclouddb.samples.serialization.ComplexPojoSample'
java -cp $jar $main live cosmos --allow-live
$LASTEXITCODE
```

To choose an explicit file or run from a different directory:

```powershell
java "-Dmulticlouddb.config=C:\path\to\serialization-cosmos-cloud.properties" `
    -cp $jar $main live cosmos --allow-live
```

This property is a **filesystem path** for the POJO sample, unlike the old apps'
classpath resource name. The file is read as UTF-8 (optional BOM accepted) using
Java properties escaping rules. The setup script escapes special characters.
Configuration precedence: explicit `-Dmulticlouddb.config`, then the default
properties path for Cosmos, then legacy environment settings if no file is selected.
A selected file supplies **all** target/key values; it never merges stale environment
credentials. Missing/unreadable/invalid selected files or missing keys fail closed;
no silent environment/identity fallback. The file workflow supports Cosmos; Dynamo
continues using its documented environment/credential-chain setup. Preview never
reads the account config, even when `-Dmulticlouddb.config` is set.

Remove any old `MCDB_COSMOS_KEY` environment value if you used the previous setup:
`Remove-Item Env:MCDB_COSMOS_KEY -ErrorAction SilentlyContinue`. This does not erase
the primary key stored in either local config file.

## DynamoDB live (PowerShell)

The SDK uses the AWS default credential chain. These commands use temporary
environment credentials exported from an already configured SSO profile, so the
Java sample does not need additional AWS SSO modules on its classpath. AWS CLI v2
is required for this option; an IAM role or securely injected temporary environment
credentials work too. No endpoint override or dummy credentials are installed.

```powershell
aws sso login --profile YOUR-TEST-PROFILE
if ($LASTEXITCODE -ne 0) { throw 'AWS login failed' }
$session = aws configure export-credentials --profile YOUR-TEST-PROFILE --format process | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Credential export failed' }
$env:AWS_ACCESS_KEY_ID = $session.AccessKeyId
$env:AWS_SECRET_ACCESS_KEY = $session.SecretAccessKey
$env:AWS_SESSION_TOKEN = $session.SessionToken
$session = $null
$env:MCDB_AWS_REGION = 'us-east-1'
$env:MCDB_DATABASE = 'your-test-db'
$env:MCDB_COLLECTION = 'your-test-orders'
$env:MCDB_RUN_PREFIX = 'pojo-review'
# Existing physical table: your-test-db__your-test-orders
try {
    java -cp $jar $main live dynamo --allow-live
    $result = $LASTEXITCODE
} finally {
    Remove-Item Env:AWS_ACCESS_KEY_ID, Env:AWS_SECRET_ACCESS_KEY, Env:AWS_SESSION_TOKEN -ErrorAction SilentlyContinue
}
$result
```

Do not echo `$session` or credential environment variables. Use a fresh terminal
for this example so temporary credentials do not overwrite another workflow's
environment.

## Read results and clean up

For each configured serializer/fixture, the sample calls the public SDK:
**create -> read -> update -> read -> upsert -> read -> query -> delete**.
It prints outgoing and returned Maps/types plus restored POJOs and compares reads,
queries, and restored objects to independent expected data.

For each serializer's **baseline** case, additional failure probes run after the
confirmed create, using the same owned key or a read-only `-never-created` key.
They do not provision resources or write a separate failure-test document.

The deliberate small `update` body contains only order ID, run ID, revision and
status. **Current Cosmos/Dynamo update replaces the entire document**, so the
sample verifies the customer, items, and other omitted fields disappear. It does
not pretend this is a patch; the following full-POJO upsert restores them.
Known root provider fields (`id`, keys, Cosmos metadata) are printed and then
excluded from POJO comparison; unexpected application fields still fail.

Reads retry only missing/stale revision visibility, at most five attempts with
250 ms intervals. Query follows continuation tokens, at most ten pages per attempt
and five attempts. Precision differences are **not retried away or excused**.
Native conversion failures (including large-integer readback) remain FAIL results;
the next case runs after cleanup.

Exit **0** means all executed checks passed. Exit **1** means at least one operation,
comparison, or cleanup failed. Numeric risk cases may fail on the pinned providers;
that is useful evaluation evidence, not a reason to coerce the inputs or call the
whole experiment successful. A conversion/read exception stops that case's later
steps, so do not infer those steps ran from the overall command completing.

After each **confirmed successful create**, `finally` deletes only its exact
partitionKey + sortKey/id and checks for absence. A conflict or otherwise
unconfirmed create is never blindly deleted. Ambiguous writes, failed cleanup,
Ctrl+C, process termination, or machine loss can leave data. Save the printed
run ID and exact six candidate keys (`jackson-`/`gson-` followed by `baseline`,
`precision`, or `large-integer`). In the portal/your own tooling inspect only those
exact keys and delete documents you confirm belong to that run. Never delete all
documents by a shared prefix, the container, database, or table as cleanup.

## Failures and diagnostics: current public contract

Read `demonstrateFailureHandling` beside `exercise`, rather than treating this table
as a proposed API:

| Case | Current customer code / response | Information actually available |
| --- | --- | --- |
| Missing point read | Branch on `client.read(...) == null`; choose the application's not-found response | No exception and no `DocumentResult`; no status or request ID is invented |
| Duplicate `create` of this run's confirmed key | Catch `MulticloudDbException`, branch on `error().category() == CONFLICT`, keep the existing document | `error().provider()`, `operation()`, raw nullable `statusCode()`, `retryable()` hint; optional `diagnostics()` |
| Unsupported `Instant` left directly in a Map | Catch `INVALID_REQUEST`; fix customer mapping before retrying | Client-side validation can have no provider status/request ID. This is deliberately not the configured ISO-string path |
| Any other SDK error during an expected-error probe | Rethrow; the case records FAIL and still attempts owned-key cleanup | Authentication/throttling/etc. must not be reported as a successful conflict/validation test |
| Successful query | Inspect `QueryPage.diagnostics()` if non-null | Provider, operation, duration, optional request ID/status, item count; unavailable fields remain unavailable |
| Successful point read | Opt in with `OperationOptions.builder().includeMetadata(true)` | `DocumentResult.metadata()` is **write metadata**, not operation diagnostics: optional last-modified/TTL/version; no point-read request-ID getter is fabricated |

Use the **category** for portable classification, not the native status number.
For example, the pinned mappers classify a Cosmos duplicate's HTTP 409 and a
Dynamo conditional-write failure's HTTP 400 as CONFLICT. Those are source-contract
examples, **not measured live results**. `error().statusCode()` and
`diagnostics().statusCode()` are separate optional fields: at this pin the wrapper
enriches failures with duration/request ID but does not copy error status into
diagnostics. A null diagnostics status does not mean the error status was absent.

The sample prints an allowlist of public scalar fields. It does **not** dump
`error().message()`, `providerDetails()`, native causes, ETags, session tokens,
payloads, credentials or full diagnostics objects. Request IDs are bounded and
restricted to identifier characters before display. Payloads shown elsewhere are
the intentional synthetic input/result display, not error diagnostics.
There is **no direct native exception dependency** in the POJO error-handling
example. Additional provider-detail keys are opaque and need a separate redaction
policy if an application chooses to use them.

Successful create/update/upsert/delete return `void`; this sample cannot extract
a success request ID from them. Missing diagnostics is displayed, never filled
with a fabricated value. `retryable()` is a hint, not an instruction to retry
non-idempotent operations blindly. No exceptionless write API is added. Neither
exception-path overhead nor performance differences have been benchmarked;
printing one operation's supplied duration is not such a measurement.

## Existing apps: migration smoke and review status

The [Risk Platform](src/main/java/com/multiclouddb/samples/riskplatform/RiskPlatformApp.java)
is the existing customer-facing scenario, not replaced by a new demo framework.
Its `Models -> TenantManager -> SDK Map -> JSON HTTP/UI` path is exercised through
local HTTP using a clearly labeled fake SDK client. The
[TODO app](src/main/java/com/multiclouddb/samples/todo/TodoApp.java) is exercised the
same way. Both `startServer` methods now return the existing `HttpServer` handle
so the bounded tests can stop their servers; normal main-method startup is unchanged.

Direct migration corrections: Map readbacks remain application JSON, cursor JSON
callbacks are customer-owned, `Models` no longer calls JsonNode the portable SDK
format, and shipped Cosmos configs/templates no longer pass the PR-rejected
`connectionMode` setting. The profile now includes **only `static/**`** from the old
resource tree, restoring both apps' HTML without packaging account configs.

| Surface | Executed verification | Still not verified |
| --- | --- | --- |
| Complex POJO | Offline: six configured preview cases, exact fixtures; fake orchestration, failure branches and diagnostics redaction tests | Native Cosmos/Dynamo errors, storage precision, query diagnostics and cleanup against live targets |
| Risk Platform | Local HTTP + fake SDK: tenant create/read/list, portfolio/position creation and partition query, dashboard AUM/open-alert aggregation, read-merge-upsert alert acknowledgement preserving fields, missing 404 and injected SDK-error 500, static HTML response | Native provider mapping, complete demo seeding, browser JavaScript/visual UX, emulator/live E2E |
| TODO | Local HTTP + fake SDK: create/read/list, read-merge-upsert preserving title/date, delete, missing 404, SDK category in the existing 500 response, static HTML response | Native persistence/query behavior, browser interaction, concurrent updates, emulator/live E2E |
| Change Feed | Offline public cursor serialization/resume callback round-trip and malformed-token exception; synthetic nested Map/null/empty payload rendering through the actual watcher formatter | Hydrated native cursors, feed delivery/order, consumer-thread error handling, retention/AVAD, emulator/live E2E |
| Packaging | PR and published-profile compilation; installed/resolved SDK JAR/POM provenance; synthetic/HTML resources only in PR sample artifact | Native Windows launch (PowerShell script was exercised on Linux) |

Run the bounded offline smoke, with **no account or emulator**:

```powershell
$repo = Join-Path $PWD '.local\m2-ef339fb6529a1b367987bff0b4884c4127708fd9'
mvn "-Pserialization-pr" "-Dmaven.repo.local=$repo" `
    "-Dtest=ComplexPojoSampleTest,ExistingAppsSmokeTest,CursorSerializationTest" test
if ($LASTEXITCODE -ne 0) { throw 'Offline smoke failed' }
```

`ExistingAppsSmokeTest` explicitly refuses unexpected SDK calls (including
provisioning); its fake is not a native mapper or general query engine. HTTP is
real, storage is not. All test HTTP servers are stopped in `finally`.

### Optional emulator smoke runbook (not executed here)

Use only a **disposable local emulator** that you deliberately started. The legacy
apps' `main` methods auto-provision and/or seed fixed demo targets; they **do not
inherit the POJO sample's live opt-in/owned-key safety**. Do not point these commands
at cloud accounts or shared production data. Account/permissions/cost approval
is still required before any later live experiment.

After running the pinned build script, keep the JAR first on the classpath; add
the shipped local configs externally, never rebuild with secrets in resources:

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$cp = "$jar;src\main\resources"
# Verify the selected *-dynamo.properties endpoint is http://localhost:8000.
# Separate terminals; start your disposable DynamoDB Local instance first.
java "-Dmulticlouddb.config=todo-app-dynamo.properties" -cp $cp com.multiclouddb.samples.todo.TodoApp
java "-Drisk.config=risk-platform-dynamo.properties" -cp $cp com.multiclouddb.samples.riskplatform.RiskPlatformApp
```

In another terminal:

```powershell
$todo = 'http://localhost:8080/api/todos'
$id = 'smoke-' + [guid]::NewGuid().ToString()
$body = @{ id = $id; title = 'Synthetic smoke task' } | ConvertTo-Json
Invoke-RestMethod $todo -Method Post -ContentType 'application/json' -Body $body
Invoke-RestMethod "$todo/$id"
Invoke-RestMethod "$todo/$id" -Method Put -ContentType 'application/json' -Body '{"completed":true}'
Invoke-RestMethod $todo
Invoke-RestMethod "$todo/$id" -Method Delete
# Final GET should report HTTP 404. This deletes only the selected smoke item.
Invoke-RestMethod 'http://localhost:8090/api/tenants'
Invoke-RestMethod 'http://localhost:8090/api/dashboard?tenant=acme-capital'
```

Open the two UIs (`8080`, `8090`). In Risk Platform select the seeded tenant, inspect
portfolios/positions, and acknowledge an alert. Check that the alert's original
message/severity survive and open-alert count decreases. This is the customer
scenario to review; HTTP-fake results alone do not establish it works natively.
Stop each foreground app with Ctrl+C. Do not use database/table-wide cleanup
against live targets; dispose of only your dedicated local emulator when finished.

Change Feed additionally needs a Streams-capable local Dynamo endpoint (not every
DynamoDB Local setup exposes the required Streams API). If unavailable, record
the smoke as **blocked**, not passed; do not silently switch to a live account:

```powershell
# Only for the disposable local endpoint. Enable existing sample logs for this local run.
java "-Dmulticlouddb.config=change-feed-dynamo.properties" `
    "-Dlogback.configurationFile=src/main/resources/logback.xml" `
    -cp $cp com.multiclouddb.samples.changefeed.ChangeFeedSample
```

Check actual create/update/delete events and the token round-trip, not just exit
or compile status. For Cosmos emulator use the corresponding `*-cosmos.properties`
and your emulator truststore (`"-Djavax.net.ssl.trustStore=C:\path\to\truststore"`);
feed smoke additionally requires an emulator version supporting AVAD. The native
SDK helper may provision its test container. Extended retention needs a suitable
live account and is intentionally not part of this offline/local verification.

### Record friction, not automatic design decisions

| Observation | Current sample/application approach | Team/SDK follow-up to evaluate (not implemented or approved) |
| --- | --- | --- |
| Mapping/date/null policy is customer boilerplate; untyped numeric parsing can lose precision | Explicit Jackson/Gson settings, visible fixtures, exact comparisons; string alternatives are separate fields | Documentation/usability and numeric-contract review; no automatic serializer or numeric redesign |
| Cosmos/Dynamo `update` replaces; existing TODO edits and Risk alert acknowledgement merge client-side then upsert | Keep current business behavior and verify preserved fields | Concurrent lost-update risk remains. Any partial-update work would improve the existing update API; a separate API is not agreed |
| Missing read is null; duplicate/invalid writes throw; diagnostics availability differs by path/provider | Explicit null branch and category catches in POJO; optional fields displayed honestly | Evaluate exceptionless failure handling and diagnostics consistency; no new API or overhead claim |
| Existing app HTTP error policies are simplistic | Smoke records TODO's category-bearing 500 and Risk's generic 500; no redesign of all handlers | Existing handlers may echo exception messages, and do not surface diagnostics. Harden before customer deployment; do not treat legacy app logs/HTTP as sanitized |
| Existing list methods consume one QueryPage; watcher/consumer failure policies are not a full resilient feed processor | Small fake scenarios only; actual feed/thread behavior remains unverified | Review pagination, resumability, terminal cursors and error policy with realistic data |
| Native configuration and resources matter after compile succeeds | Remove rejected transport config; include static assets without credentials | Run actual local/native smoke and Windows checks before declaring apps migration-complete |

### Team review checklist

- Follow a visible POJO through customer Map conversion, each public CRUD/query call, and POJO restoration against independent expected data.
- Review required setup/boilerplate and failure-path code in the five linked files; note unclear ownership or surprising defaults.
- Inspect numeric precision, dates, null versus missing, and large integers; do not accept coercion as a hidden fix.
- Confirm replacement `update` versus app-side read/merge/upsert, including concurrency limitations.
- Review null returns, expected categories, unexpected exceptions, retry hints, and whether an exceptionless alternative is worth future investigation.
- Use only available diagnostics to trace a problem; record missing request/status information without dumping native text or tokens.
- Record provider/runtime, pinned SHA, exact input, observed behavior, current workaround, and a separate proposed follow-up. Human review, live/Windows verification and release approval remain open.

## Evidence and limits

The offline suite checks both codecs against all independent fixtures, missing/null
and precision mismatch detection, explicit live opt-in, target validation, bounded
partition query pagination, and cleanup ownership/failure paths. Public SDK calls
in orchestration unit tests use an explicitly labeled in-memory test double:
**they do not validate native mapping or live storage**. A separate offline cursor
round-trip checks the directly required migration of existing change-feed samples.

Observed offline results: all six configured preview cases and twenty-two JUnit tests
passed on JDK 17; the original released-SDK profile also compiled. The PowerShell
script was executed end to end using PowerShell on Linux, including the installed/
resolved artifact hash checks. Native Windows execution has not been performed.

At implementation time, **no Cosmos or Dynamo live account was accessed**.
The PR's Dynamo mapper source uses `Double` for decimal readback and `Long` for
overflowed integer parsing; beyond-Long or exponent-number readback is therefore a
known evaluation risk, not a live result. Cosmos numeric behavior must likewise be
measured with your target. No SDK implementation or product-policy change is
included in this sample.
