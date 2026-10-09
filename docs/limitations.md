# Execution limits

Interactive queries read local DuckDB profiles. Native dbt platform support is separate from JDBC preview support.
SQLite previews capture at most 50,000 rows per input relation.
Results default to 10,000 rows, with a maximum of 100,000. Queries default to a 30-second deadline.
Truncated captures cannot prove complete result equality.

Only validated SELECT/WITH queries run in Query lab. Jinja requires dbt compilation first.
The parser accepts a bounded SQL vocabulary and refuses unrecognized functions or unsafe constructs.
DuckDB-specific grammar beyond JSqlParser's vocabulary can require a simpler equivalent query.
SQL-to-EDN supports a bounded HoneySQL grammar. Unsupported AST nodes retain the SQL draft with a diagnostic.
Formatting and comments belong to the saved SQL draft; EDN expresses query meaning as plain data.
Visual mode supports one source, ordered joins, columns, basic aggregations, filters, grouping, sorting, and limits.
CTEs, query batches, nested expressions, and other advanced forms remain in SQL or EDN.
Native SQL-to-SQLite execution can differ in date, type, collation, and numeric behavior.
Native division is refused. Structured EDN division declares floating-point, NULL-on-zero semantics.

Baseline artifacts do not snapshot rows. Replay restores inputs, not a historical source database.
JSON decimal values preserve decimal text; downstream consumers should retain column type metadata.
Legacy Arrow results remain in metadata but need rerunning through JDBC to inspect rows.
Changes history covers workbench saves. External edits are detected through revision conflicts, not automatically archived.

The app uses an instance lock within its data directory. Run one server per directory.
Forced termination can leave a native dbt child process; inspect active jobs before restarting shared data.
