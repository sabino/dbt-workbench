# Query processor extension points

The query processor separates parse, metadata, compile, and driver concerns.
Extend each database driver only with native execution evidence for its supported operations and types.
HoneySQL generation alone does not establish cross-engine equivalence.

Add JDBC warehouse connectors with explicit profile resolution, read-only capabilities, timeouts, cancellation,
and typed result metadata. Keep dbt materializations authoritative through the selected native CLI.
Preserve decimal, timestamp, NULL, and duplicate semantics in source-oracle tests.

Extend SQL-to-HoneySQL conversion with execution roundtrip tests for each added AST node.
Unsupported queries should retain their SQL draft instead of generating opaque SQL inside EDN.
Versioned data captures should record schemas, content hashes, source consistency, and artifact identity.
Imported baseline artifacts and captured rows must remain distinct concepts.
