# Workbench feasibility evidence

This directory preserves the supplied feasibility archive without modifying its
probes, recorded results, or existing documentation. It supports the application
at the repository root.

The [evidence index](evidence-index.json) records the scope, engine versions,
negative findings, and SHA-256 checksums for 58 files. All 58 indexed files matched
their recorded byte counts and checksums during import.

| Directory | Evidence |
| --- | --- |
| `root/` | Core SQL/query probes and the classic Jaffle Shop workflow |
| `dbt/` | dbt deferral, artifacts, and local execution boundaries |
| `postgres/` | PostgreSQL bridge and reproduction instructions |
| `semantics/` | SQL translation and cross-engine semantic differences |
| `metabase/` | Metabase compiler and preprocessing experiments |
| `unconventional/` | SQLMesh and Ibis alternatives |
| `delivery/` | Cancellation, streaming, packaging, and delivery review |

Start with the index and [delivery review](delivery/delivery-review.md), then read
the README and reproduction instructions for the specific experiment. These are
historical local/synthetic findings; no remote warehouse execution is recorded.

## Reproduction limits

Several probes assume `/tmp/workbench-spikes`; consult their instructions before
execution and use an isolated environment. Some historical documentation refers
to files that were omitted from the supplied archive, including
`sqlmesh-requirements.lock`, `ibis-requirements.lock`, and earlier Metabase build
artifacts. The archive therefore does not establish that every probe can be
reproduced standalone. Recorded findings are preserved as supplied.
