# Execution limits

Interactive execution supports DuckDB, Snowflake JDBC, imported Snow CLI connections, and bounded federation.
Native dbt platform support remains separate. Builds use profile targets and can write to their databases.

## Queries and captures

| Path | Boundary |
| --- | --- |
| Query results | Default 10,000 rows; maximum 100,000 |
| Query deadline | Default 30 seconds; greater than zero and less than 121 seconds |
| SQLite preview | Up to 50,000 copied rows per DuckDB input relation |
| Federation | Maximum 16 physical input relations; 50,000 rows per relation |
| Snow CLI | Bounded SELECT/WITH; maximum 32 MiB per captured output stream |

Only validated SELECT/WITH queries run in Query lab.
Unsupported SQL functions, syntax, or conversion nodes fail with a diagnostic.
SQL/EDN mode switches use a bounded conversion grammar. Unsupported expressions leave the draft and mode intact.
Compile dbt Jinja with **Preview model** before converting it. Visual mode is disabled.
An untouched EDN representation restores its original SQL, including comments and spacing.
Edited EDN must compile before saving a native `.sql` file. Conversion failure prevents that save.
**Always format on mode switch** is off by default. Enabling it can change restored SQL whitespace.
Fixed-point decimal conversion preserves values with precision and scale up to 38.
Scientific literals and quoted identifiers containing dots or slashes require SQL editing.
EDN formatting preserves standard numeric types. Explicit formatting can remove EDN comments.

SQLite copies can truncate input relations. Its date, type, collation, and numeric semantics can differ from DuckDB.
Native division is refused in SQLite. Structured division specifies floating-point results and NULL on zero.

Federation copies source relations sequentially, then joins them in isolated DuckDB.
It refuses oversized inputs instead of silently truncating them. Filter large inputs through source views before joining.
Final query filters do not reduce source captures.
Captures have no shared snapshot or transaction, so sources can change between reads.
Supported scalar types retain declared JDBC types. Unsupported or ambiguous types require explicit source casts or views.
Decimal precision is capped at 38. Repeated table names require explicit aliases.
Snow CLI connections cannot participate in federation.

## Snowflake and authentication

Snowflake JDBC supports password, OAuth, and private-key-file authentication.
Browser authentication is unsupported. Set referenced environment variables before starting the server.
Saved interactive connections do not create dbt profile targets.

Snow CLI import stores a local configuration reference, not copied credentials.
Authentication must already work in the configured CLI environment.
Results infer types from JSON values; empty results cannot supply declared column metadata.
Named and positional parameters are rendered through validated literals before execution.
Cancellation stops the local CLI process. Server-side cancellation remains unconfirmed, including on timeout.

Local tests cover supported execution boundaries. External Snowflake JDBC, cross-engine remote federation,
and GitHub end-to-end behavior require separate verification.
A generic CLI SELECT check is covered separately in the [verification record](verification.md).
It establishes neither server-side cancellation nor remote federation correctness.

## Results and persistence

Compare checks captured rows and schemas. Truncation prevents proof of complete source equality.
Baseline artifacts do not snapshot rows. Replay restores inputs, not historical database contents.
JSON decimal values preserve decimal text; consumers must retain column type metadata.
Run comparison covers recorded model/test outcomes and timing, not row differences.

Documentation depends on existing dbt artifacts. Parse or build after source changes to refresh the manifest.
Metadata infers static draft references, literal configuration, and explicit outer SELECT field names without compiling templates.
Dynamic templates, wildcards, batches, and ambiguous projections cannot establish a complete schema.
Inferred field names have no inferred types. Recorded schema and tests can differ from an unsaved draft.
Draft parsing supports SQL/Jinja and JSON. Other file types show file details and any matching recorded resources.
File tabs edit UTF-8 text up to 2 MiB. Binary and larger files provide read-only metadata without editable content.
Generated directories, symbolic links, and `profiles.yml` stay outside the explorer.
Untitled tabs remain drafts until **Save file** confirms a relative project path.
Creation refuses existing entries and excluded paths. It requires secure directory support from the filesystem.
Closing a dirty tab asks whether to discard its draft.
Catalog and run results are selected from available artifacts by file modification time.
Source freshness is displayed only from existing `sources.json` results; the app does not launch freshness checks.

Saved query drafts, formatting preferences, and keyboard shortcuts live in one browser.
Export saved queries before clearing browser storage.
Shortcut assignments reject reserved keys and conflicts. Browser and operating-system shortcuts can still intercept key combinations.
Changes archives workbench saves. External edits trigger revision conflicts but are not automatically archived.
Legacy Arrow results require rerunning their saved queries through JDBC.

## Editor assistance

Editor completion and definition lookup use a bounded local parser and index, not a language server.
Suggestions do not establish SQL validity or complete grammar coverage.
CTE and derived-query output columns are not inferred. CTE names shadow similarly named project resources.
Unresolved relations suppress unqualified field hints. Use explicit relations and aliases when their declared metadata is available.
Jinja help covers supported dbt functions, macros, and context variables inside SQL template blocks.
Documentation does not inspect their runtime values. HoneySQL EDN does not execute dbt runtime macros.

Project files and recorded manifest/catalog artifacts supply editor metadata without warehouse requests.
Dynamic references and ambiguous names have no selected definition. Package resources without editable local files provide documentation only.
Field types appear only when declared or recorded. Missing metadata cannot establish a complete schema.
Recorded artifacts can be stale. Refresh declarations and parse or build after source changes.

Paredit is the default EDN editing mode. Optional Parinfer smart mode adjusts parentheses during supported user edits.
Changing settings, converting modes, pasting, and undo/redo do not trigger Parinfer corrections.
Selected deletion and multiple-cursor edits also bypass inference.
Rainbow delimiters decorate syntax without changing the draft. Editor settings remain in the same browser.
See [editor help and shortcuts](editor.md) for navigation and structural editing.

## Git and runtime

Branch switches, rebase, and cherry-pick require a clean local tree.
Resolve or abort Git conflicts in your terminal. GitHub draft creation requires an already published matching branch.
Remote push, merge, deployment, and background GitHub polling are outside the workbench workflow.

Run one server per data directory. The instance lock prevents shared-directory servers.
Forced termination can leave a native dbt child process. Inspect active work before restarting shared data.
