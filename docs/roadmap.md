# Extension points

Extend the implemented workflows with source-backed execution evidence.
A generated query or working UI alone does not establish cross-engine correctness.

| Area | Implemented boundary | Extension requirement |
| --- | --- | --- |
| SQL conversion | Bounded SQL/HoneySQL roundtrips | Add execution tests for each AST shape |
| Connections | DuckDB, Snowflake JDBC, Snow CLI references | Define authentication, declared types, timeouts, and cancellation |
| Federation | Bounded JDBC captures joined in DuckDB | Prove source consistency and type semantics before expanding limits |
| Inspection | Manifest resources, catalog schema, recorded runs and freshness | Preserve artifact provenance and explicit missing states |
| Saved queries | Browser storage and JSON export | Define import validation and shared persistence before adding sync |
| Version control | Local Git operations and explicit GitHub reads/drafts | Define review and authorization boundaries for remote writes |

Keep native dbt authoritative for project compilation and materialization.
Preserve decimal, timestamp, NULL, and duplicate semantics in source-oracle tests.
Unsupported queries must retain their drafts with actionable diagnostics.

Versioned data captures need schema, content hashes, source consistency, and artifact identity.
Keep imported baseline artifacts distinct from captured rows.
Snow CLI JSON inference cannot substitute for declared source types in federation.

External Snowflake JDBC, remote federation, and GitHub workflows need dedicated acceptance evidence.
Local fixtures and a generic CLI SELECT check cannot establish those workflows end to end.
