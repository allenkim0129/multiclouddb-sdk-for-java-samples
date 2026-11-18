# Complex POJO serialization: live accounts

This is customer code for evaluating the **existing** Map-based public API in
[microsoft/multiclouddb-sdk-for-java#122](https://github.com/microsoft/multiclouddb-sdk-for-java/pull/122).
It does not change the SDK, promise a new numeric contract, or implement partial updates.
Only synthetic orders are used. **The app is live-only and requires an explicit
provider and configured test target. Running it performs real database operations
without an additional confirmation flag. Building does not run the live app.**

**App name:** Complex POJO Serialization. **Main class:**
[`com.multiclouddb.samples.serialization.ComplexPojoSample`](src/main/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java).
The `public static void main(String[] args)` method is in
`src/main/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java`.

## Team review: start with these three customer files

| Read in order | Review focus |
| --- | --- |
| [Order.java](src/main/java/com/multiclouddb/samples/serialization/Order.java) | What the application owns: nested POJOs and Java field types |
| [ComplexPojoSample.java](src/main/java/com/multiclouddb/samples/serialization/ComplexPojoSample.java) | Visible `ORDER_JSON` at the top, Jackson setup, direct mapping and SDK calls in `exercise`; small supporting methods in the same file |
| [ConfigLoader.java](src/main/java/com/multiclouddb/samples/ConfigLoader.java) | Existing shared properties loader used by the other samples |

Short walkthrough: configure your live test target, follow one original order into
`MAPPER.convertValue`, and compare its returned Map and restored POJO to the
original input. Follow `exercise` from top to bottom:
`create`, `read`/restore, POJO-based `update`, direct Map `update`, full `upsert`,
partition-scoped `query`/restore, and owned-key cleanup.
There are no deliberate error probes, metadata demonstrations or separate helper classes.
`run` names the command argument `provider`; input creation, required-property checks
and comparisons are methods in the same file.
Jackson's tree comparison handles nested structure; a small numeric comparator
accepts equal values across Java number types without rounding or tolerance.

The intent is a small review surface for the team's typical application workflow,
not a claim that review is complete, the API is approved for customer delivery,
or three-year compatibility has been proven. PR merge/release and human review
remain separate decisions.

## Find and edit the inputs

| File under `src/main/` | Purpose |
| --- | --- |
| `java/com/multiclouddb/samples/serialization/Order.java` | Mutable POJOs with getters/setters: order, customer/address, line items and enum |
| `java/com/multiclouddb/samples/serialization/ComplexPojoSample.java` | Edit the `ORDER_JSON` text block at the top; direct Jackson mapping and public SDK create/read/update/upsert/query/delete workflow |

The sample uses **one complex order fixture with Jackson (one run)**.
The purpose is complex POJO mapping and public SDK CRUD/query. The order contains a nested
customer/address, `List<LineItem>`, string and nullable Maps, enum, nanosecond
`Instant`, `LocalDate`, null `note`/`giftMessage`, deliberately **absent** `coupon`,
an empty list, `long`, `BigInteger`, and `BigDecimal`. Baseline numeric values are
`12345`, `42`, and `125.50`; line-item prices are `50.25` and `25.00`.
`createOrder(runId, revision)` reads `ORDER_JSON`, sets the runtime run ID and
revision, and converts it to `Order`. The source JSON makes the nested input
easy to review; how a real application originally creates its POJO is not an SDK concern.
There are no serializer interfaces/factories, scenario selectors, numeric-limit
probes, or numeric string-workaround fields.

There is one hand-authored JSON input, not a separate expected-data file or
duplicate constructor literals. The live sample compares returned Maps and POJOs
with the corresponding original inputs. Offline tests additionally compare
the POJO's mapped representation with the raw JSON to catch lost or changed fields.

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

It builds installed **JAR/POM artifacts**, compiles all samples, runs offline unit tests,
and creates the executable fat JAR using the existing service-merging configuration.
It does not launch the live app. It checks SHA256 equality of SDK build JARs, installed JARs, and sample-resolved
JARs, plus installed flattened POMs. Evidence is written to
`target/serialization-build-evidence.txt`; the complete dependency mediation tree is
`target/serialization-dependencies.txt`. No SDK source/test directory is put on the
sample runtime classpath. Merely selecting a SNAPSHOT version without this preparation
does **not** establish provenance.

The profile aligns Cosmos to the PR's **4.82.0** (rather than the sample default
4.81.0), keeps AWS **2.34.0**, and aligns Jackson components using the **2.18.9 BOM**
to match the pinned SDK's Azure dependencies rather than downgrading them to the
sample default 2.18.6.
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

The default `released-sdk` profile still uses the original published SDK versions.
Selecting `serialization-pr` disables that default profile, keeping their resource
sets separate. Small customer-side
Map-to-JSON conversions preserve the existing applications' JSON interfaces. Two
profile-selected `CursorSerialization` helpers adapt the incompatible released/PR cursor
signatures without reflective fallbacks, in `src/sdk-released/java` and `src/sdk-pr/java`.
The POJO sample itself lives alongside the other apps in `src/main/java`; it is excluded
from the default released-SDK build because it requires the PR's Map API.
The `serialization-pr` profile includes it and uses
`src/main/resources/serialization/logback.xml` as its root logging config
to suppress potentially sensitive native diagnostics. The other apps' default logging
configuration is unchanged. Always clean when switching profiles.

## Run the live sample

First configure your test account using the Cosmos or DynamoDB instructions below.
Then run from the repository root (Cosmos example):

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$main = 'com.multiclouddb.samples.serialization.ComplexPojoSample'
$cp = "$jar;src/main/resources"
java -cp $cp $main cosmos
$LASTEXITCODE
```

The app prints only test `PASS` statements and errors. It does not dump POJOs,
Maps, runtime types or startup details. Inputs remain visible and editable in
`Order.java` and the `ORDER_JSON` text block in `ComplexPojoSample.java`.
There is no preview mode. Missing arguments fail with usage instead of connecting.
The build's offline unit tests remain separate from this live workflow.

### Run or debug in VS Code

Open the **repository root folder** in VS Code, with **Extension Pack for Java**
installed. Set `JAVA_HOME` to JDK 17 and put Java, Maven and Git on `PATH`;
restart VS Code after changing environment variables. The Java extension's
language-server runtime is separate from the JDK 17 used to build/run this sample.

1. Configure your existing test target in
   `src/main/resources/serialization-cosmos-cloud.properties` (or the Dynamo
   equivalent), as described below. Keep credentials out of `.vscode` files.
2. Open **Run and Debug** (`Ctrl+Shift+D`) and select
   **Complex POJO - Cosmos (live)** or **Complex POJO - Dynamo (live)**.
3. Optionally set a breakpoint on `client.create(...)` in `ComplexPojoSample.java`.
4. Press **F5** to debug, or **Ctrl+F5** to run without debugging.

The pre-launch task runs `scripts/build-serialization-sample.ps1` to build and
verify the pinned SDK and sample before each launch. This can take several minutes;
the build itself is offline with respect to your cloud accounts. If it fails,
fix the build rather than choosing **Debug Anyway**, which could run stale artifacts.
Windows uses its built-in Windows PowerShell; Linux/macOS require `pwsh` on `PATH`.

The launch uses the verified fat JAR first on the classpath, followed by
`src/main/resources` for local properties. It does not depend on VS Code's default
released-SDK Maven profile or its compiler output. `.vscode/settings.json` disables
the Java debugger's extra automatic build because the pre-launch task owns this build.
SDK source paths are included for stepping into the pinned SDK with **F11**.

The build also generates `.local/serialization-maven-settings.xml`. The workspace's
`java.configuration.maven.userSettings` points to this file so editor type analysis
uses `serialization-pr` and the same isolated SDK repository, rather than released
dependencies. This affects this VS Code workspace, not your normal Maven user settings.
If the editor still shows `document()` returning `JsonNode` instead of
`Map<String, Object>`, run the build script once, then use **Ctrl+Shift+P →
Java: Clean Java Language Server Workspace** and confirm the restart. Wait for
Java project import to finish. Do not select **Run Java** to fix a stale import;
use the named launch configurations above after rebuilding.

**F5 starts real database operations after the build succeeds.** Use only your
configured disposable test target. Let execution finish so `finally` can delete
the test document; forcibly stopping the debugger can leave that document behind.
Output appears in the integrated terminal. No live execution is part of adding
these editor configurations.

`ComplexPojoSample.java` configures one Jackson `ObjectMapper` at the top of the
class: `JavaTimeModule` handles Java dates, ISO strings replace date timestamps,
and untyped decimal values use `BigDecimal`. Null fields retain Jackson's default
inclusion behavior. Mapping uses Jackson directly, beside the SDK calls:

```java
Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);
client.create(address, key, document);
// After reading and removing provider-owned fields:
Order restored = MAPPER.convertValue(payload, Order.class);
```

`MAP_TYPE` is a Jackson `TypeReference<Map<String, Object>>`, not an SDK type.
There is no custom `Codec`, `toDocument`, or `fromDocument` layer.

Comparisons remain strict: there is no blanket stringify/normalization,
floating-point tolerance, or automatic fallback. The comparator accepts numerically equivalent
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
| DynamoDB | Physical table **`<database>__<collection>`** from the properties file, String hash key **`partitionKey`**, String range key **`sortKey`** | `dynamodb:PutItem`, `GetItem`, `Query`, `DeleteItem` on that table; no Scan/CreateTable/DeleteTable needed |

The POJO sample uses the same **`ConfigLoader` and `.properties` format as the other
samples**, with no separate settings class or `MCDB_*` environment mapping.
Filled `src/main/resources/serialization-*-cloud.properties` files are gitignored
and excluded from both builds' JARs. Put `src/main/resources` on the runtime
classpath **after the JAR** to load these files without packaging secrets.
Keeping the JAR first also preserves its safe logging configuration.
On Windows separate classpath entries with `;`; on Linux/macOS use `:`.
The shared loader no longer prints a successful configuration-load banner.
Never paste account keys into source, commands recorded in shell history, chat, logs,
or a JAR. Provider diagnostic logging is disabled in this profile. Errors print
exception type, SDK error category and status code, not native messages/causes that
could expose credentials. Diagnose access errors privately in your cloud tooling.

The sample checks for missing required properties and a provider/argument mismatch.
It does not impose its own name lengths, character restrictions, endpoint syntax or
region format rules; the SDK/provider validates those. A fresh UUID is appended to
`multiclouddb.runPrefix` on every run; queries are partition-scoped to that unique run.

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

**Rebuild once after pulling the shared-loader update** using the pinned build
script or the sample-only Maven command above. Later properties edits require
no rebuild. **`az login` is not needed** for primary-key authentication.
Run from the repository root:

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$main = 'com.multiclouddb.samples.serialization.ComplexPojoSample'
$cp = "$jar;src/main/resources"
java -cp $cp $main cosmos
$LASTEXITCODE
```

To select another properties resource in that directory:

```powershell
java "-Dmulticlouddb.config=serialization-other-cloud.properties" `
    -cp $cp $main cosmos
```

`multiclouddb.config` is now a **classpath resource name**, just like the other apps,
not a filesystem path. The default name is `serialization-cosmos-cloud.properties`
for Cosmos and `serialization-dynamo-cloud.properties` for Dynamo. For a different
directory, add that directory after the JAR on the classpath.
The shared loader uses Java `.properties` escaping rules (ASCII/ISO-8859-1, no BOM;
use Unicode escapes for other characters). Existing files generated by the setup
script contain ASCII properties and can be reused without changes.

The shared loader supports `-Dmulticlouddb.*` property overrides. Do not pass keys
on the command line; keep them in the ignored properties file. The sample checks
the provider matches the command and requires the target, run prefix and connection
properties before creating a client. Missing configuration does not fall back to
emulator targets or legacy `MCDB_*` environment values.

Remove any old `MCDB_COSMOS_KEY` environment value if you used the previous setup:
`Remove-Item Env:MCDB_COSMOS_KEY -ErrorAction SilentlyContinue`. This does not erase
the primary key stored in either local config file.

## DynamoDB live (PowerShell)

Copy the [Dynamo template](src/main/resources/serialization-dynamo-cloud.properties.template)
to `src/main/resources/serialization-dynamo-cloud.properties` and fill in the
region, database, collection and run prefix:

```powershell
Copy-Item src/main/resources/serialization-dynamo-cloud.properties.template `
    src/main/resources/serialization-dynamo-cloud.properties
```

```properties
multiclouddb.provider=dynamo
multiclouddb.connection.region=us-east-1
multiclouddb.database=your-test-db
multiclouddb.collection=your-test-orders
multiclouddb.runPrefix=pojo-review
```

The SDK uses the AWS default credential chain. These commands use temporary
environment credentials exported from an already configured SSO profile, so the
Java sample does not need additional AWS SSO modules on its classpath. AWS CLI v2
is required for this option; an IAM role or securely injected temporary environment
credentials work too. No endpoint override or dummy credentials are installed.

```powershell
$jar = 'target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar'
$main = 'com.multiclouddb.samples.serialization.ComplexPojoSample'
$cp = "$jar;src/main/resources"
aws sso login --profile YOUR-TEST-PROFILE
if ($LASTEXITCODE -ne 0) { throw 'AWS login failed' }
$session = aws configure export-credentials --profile YOUR-TEST-PROFILE --format process | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Credential export failed' }
$env:AWS_ACCESS_KEY_ID = $session.AccessKeyId
$env:AWS_SECRET_ACCESS_KEY = $session.SecretAccessKey
$env:AWS_SESSION_TOKEN = $session.SessionToken
$session = $null
# Existing physical table: your-test-db__your-test-orders
try {
    java -cp $cp $main dynamo
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

The sample calls the public SDK:
**create -> read -> POJO update -> read -> Map update -> read -> upsert -> query -> delete**.
It compares reads, queries and restored POJOs to the corresponding original inputs.
Successful checks print `PASS`; document values are included only in mismatch errors.

The sample calls `restored.setStatus(Order.Status.PAID)` and `restored.setRevision(2)`,
then converts that complete POJO to a Map for `update()`. `Order` and its nested
models are regular mutable POJOs with getters/setters, not records.
**Current Cosmos/Dynamo update replaces the entire document**, not selected fields;
the readback verifies that all other fields are preserved.

The second update edits the returned Map directly, without restoring a POJO:
`payload(afterUpdate)` copies the application fields into a mutable Map without
provider metadata, then `put` changes `note` and revision to `3` before `update()`.
Both approaches send the complete document and preserve unedited fields.
The following upsert writes a fresh order from the JSON input (`NEW`, revision `4`).
Known root provider fields (`id`, keys, Cosmos metadata) are excluded from
POJO comparison; unexpected application fields still fail.

Point reads call `client.read()` once, without waiting or retrying. Missing or stale
data fails the check immediately. Query follows continuation tokens, at most ten pages per attempt
and five attempts. Value differences are **not retried away or excused**.
An unexpected operation error or failed comparison throws immediately.
There is no failure accumulation or continuation after a mismatch.
Cleanup is still attempted for a confirmed create.

Exit **0** means all executed checks passed. Exit **1** means at least one operation,
comparison, or cleanup failed. An exception or assertion failure stops the workflow's later
steps, so do not infer those steps ran from the overall command completing.

After **confirmed successful create**, a `finally` block deletes
only its exact partitionKey + sortKey/id, on success or failure.
`PASS delete` means the SDK delete call completed; the sample does not add a separate
post-delete polling loop.
If both the workflow and deletion fail, the deletion exception replaces the original
exception under normal Java `finally` semantics. The client is still closed by
`try-with-resources`.
A conflict or otherwise
unconfirmed create is never blindly deleted. Ambiguous writes, failed cleanup,
Ctrl+C, process termination, or machine loss can leave data. On a caught workflow
failure, the error output includes the run's partition key and document key
`jackson-baseline`. In the portal/your own tooling inspect only that exact key and
delete a document you confirm belongs to that run. If abrupt termination prevents
that error output, establish ownership from the document's run ID and synthetic
contents before deleting anything. Never delete all
documents by a shared prefix, the container, database, or table as cleanup.

## Failures

The workflow throws on an SDK error, mapping error or failed comparison.
Only `main` replaces the failure with a sanitized exception for console output.
For SDK failures it shows the category and nullable status code, not native
messages, causes, provider details or diagnostics. Additional cleanup/client-close
failures are reported by count; inspect only the printed document key.

Sample assertion messages include the expected/actual synthetic values or the
missing property's name, never a configured credential. There is no failure counter,
catch-and-continue loop, or automatic retry of writes or point reads. Only the query
helper waits for missing/stale results to become visible. Error-contract and metadata
demonstrations are intentionally outside this POJO walkthrough.

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
| Complex POJO | Offline: one fixture through Jackson, exact comparisons; fake CRUD/query, single-read failure behavior, fail-fast cleanup and error redaction | Native Cosmos/Dynamo persistence, queries and cleanup against live targets |
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
inherit the POJO sample's owned-key cleanup safeguards**. Do not point these commands
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
| Mapping/date/null policy is customer boilerplate | Explicit Jackson settings and direct calls, one visible fixture and exact comparisons | Documentation/usability review; no automatic serializer redesign |
| Cosmos/Dynamo `update` replaces; existing TODO edits and Risk alert acknowledgement merge client-side then upsert | Keep current business behavior and verify preserved fields | Concurrent lost-update risk remains. Any partial-update work would improve the existing update API; a separate API is not agreed |
| Missing read is null; writes can throw | Read once and fail if absent or mismatched; propagate errors and display only category/status at the console boundary | Detailed error-contract and diagnostics evaluation is outside this simplified walkthrough; no new API or overhead claim |
| Existing app HTTP error policies are simplistic | Smoke records TODO's category-bearing 500 and Risk's generic 500; no redesign of all handlers | Existing handlers may echo exception messages, and do not surface diagnostics. Harden before customer deployment; do not treat legacy app logs/HTTP as sanitized |
| Existing list methods consume one QueryPage; watcher/consumer failure policies are not a full resilient feed processor | Small fake scenarios only; actual feed/thread behavior remains unverified | Review pagination, resumability, terminal cursors and error policy with realistic data |
| Native configuration and resources matter after compile succeeds | Remove rejected transport config; include static assets without credentials | Run actual local/native smoke and Windows checks before declaring apps migration-complete |

### Team review checklist

- Follow the visible JSON input into a POJO, through customer Map conversion and each public CRUD/query call, then compare the restored POJO with the original.
- Review required setup/boilerplate and failure-path code in the three linked files; note unclear ownership or surprising defaults.
- Inspect nested objects/lists/maps, enums, dates, and null versus missing. Numeric limits are outside this sample's scope; do not accept coercion as a hidden fix.
- Confirm replacement `update` versus app-side read/merge/upsert, including concurrency limitations.
- Review missing/stale reads, immediate failure on errors, and exact-key cleanup.
- Record provider/runtime, pinned SHA, exact input, observed behavior, current workaround, and a separate proposed follow-up. Human review, live/Windows verification and release approval remain open.

## Evidence and limits

The offline suite checks Jackson POJO mapping against the raw inline JSON, missing/null
and value mismatch detection, provider argument and target validation, bounded
partition query pagination, and cleanup ownership/failure paths. Public SDK calls
in orchestration unit tests use an explicitly labeled in-memory test double:
**they do not validate native mapping or live storage**. A separate offline cursor
round-trip checks the directly required migration of existing change-feed samples.

Observed offline results: twenty-five JUnit tests
passed on JDK 17; the original released-SDK profile also compiled. The PowerShell
script was executed end to end using PowerShell on Linux, including the installed/
resolved artifact hash checks. Native Windows execution has not been performed.

The implementation checks did **not access a Cosmos or Dynamo live account**.
Run against your own test targets to evaluate native provider behavior. This sample
does not establish a portable numeric-limit contract, and includes no SDK
implementation or product-policy changes.
