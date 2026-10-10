const dbtDocs = "https://docs.getdbt.com/reference/dbt-jinja-functions/";
const clauseDocs = "https://github.com/seancorfield/honeysql/blob/develop/doc/clause-reference.md";
const expressionDocs = "https://github.com/seancorfield/honeysql/blob/develop/doc/special-syntax.md";

function describeDbt(name, signature, description, example, page = name, kind = "dbt function") {
  return {name, signature, description, example, url: dbtDocs + page, kind};
}

export const DBT_HELP = [
  describeDbt("ref", "ref(model) / ref(package, model)", "Resolve a model, seed, or snapshot and record its dependency.", "{{ ref('customers') }}"),
  describeDbt("source", "source(source_name, table_name)", "Resolve a declared source table and record its dependency.", "{{ source('jaffle_shop', 'customers') }}"),
  describeDbt("config", "config(**options)", "Set model configuration, including materialization and tags.", "{{ config(materialized='view') }}", "config"),
  describeDbt("var", "var(name, default=None)", "Read a project variable with an optional fallback.", "{{ var('start_date', '2024-01-01') }}"),
  describeDbt("env_var", "env_var(name, default=None)", "Read an environment variable during compilation.", "{{ env_var('DBT_SCHEMA', 'dev') }}"),
  describeDbt("is_incremental", "is_incremental()", "Check whether dbt is updating an existing incremental model.", "{% if is_incremental() %} ... {% endif %}"),
  describeDbt("this", "this", "The database, schema, and identifier of this model's relation.", "select * from {{ this }}", "this", "dbt variable"),
  describeDbt("target", "target", "Information about the active dbt target.", "{{ target.name }}", "target", "dbt variable"),
  describeDbt("doc", "doc(name)", "Reference a named documentation block in resource descriptions.", "{{ doc('customer_id') }}"),
  describeDbt("log", "log(message, info=False)", "Write a compilation message to dbt logs.", "{{ log('Compiling model', info=True) }}"),
  describeDbt("return", "return(value)", "Return a value from a macro, preserving its data type.", "{% do return(['id', 'name']) %}"),
  describeDbt("run_query", "run_query(sql)", "Execute SQL during dbt compilation. This editor only inserts text.", "{% set rows = run_query('select 1') %}"),
  describeDbt("adapter.get_columns_in_relation", "adapter.get_columns_in_relation(relation)", "Ask the adapter for column metadata when dbt executes the macro.", "{{ adapter.get_columns_in_relation(ref('customers')) }}", "adapter"),
  describeDbt("adapter.get_relation", "adapter.get_relation(database, schema, identifier)", "Look up a relation through the selected adapter.", "{{ adapter.get_relation(database=target.database, schema=target.schema, identifier='customers') }}", "adapter"),
  describeDbt("adapter.dispatch", "adapter.dispatch(macro_name, macro_namespace=None)", "Select the adapter-specific implementation of a macro.", "{{ adapter.dispatch('dateadd', 'dbt') }}", "dispatch"),
  describeDbt("execute", "execute", "Whether dbt is executing rather than parsing a project.", "{% if execute %} ... {% endif %}", "execute", "dbt variable"),
  describeDbt("fromjson", "fromjson(value, default=None)", "Decode JSON text into a Jinja value.", "{% set options = fromjson('{\"enabled\": true}') %}"),
  describeDbt("fromyaml", "fromyaml(value, default=None)", "Decode YAML text into a Jinja value.", "{% set options = fromyaml('enabled: true') %}"),
  describeDbt("tojson", "tojson(value, default=None)", "Encode a Jinja value as JSON.", "{{ tojson({'enabled': true}) }}"),
  describeDbt("toyaml", "toyaml(value, default=None)", "Encode a Jinja value as YAML.", "{{ toyaml({'enabled': true}) }}"),
  describeDbt("local_md5", "local_md5(value)", "Hash text locally during compilation.", "{{ local_md5('customer') }}"),
  describeDbt("print", "print(message)", "Write a dbt output message.", "{{ print('Compiling model') }}"),
  describeDbt("set", "set(iterable, default=None)", "Collect unique values from an iterable.", "{{ set(['id', 'id', 'name']) }}"),
  describeDbt("zip", "zip(*iterables, default=None)", "Pair values from multiple iterables.", "{{ zip(['id', 'name'], ['integer', 'string']) }}"),
  describeDbt("exceptions.raise_compiler_error", "exceptions.raise_compiler_error(message)", "Stop compilation with a meaningful error.", "{{ exceptions.raise_compiler_error('Missing configuration') }}", "exceptions"),
  describeDbt("exceptions.warn", "exceptions.warn(message)", "Emit a compilation warning.", "{{ exceptions.warn('Check model configuration') }}", "exceptions"),
  describeDbt("graph", "graph", "Project nodes and their dependencies.", "{{ graph.nodes }}", "graph", "dbt variable"),
  describeDbt("model", "model", "Metadata for the model being compiled.", "{{ model.name }}", "model", "dbt variable"),
  describeDbt("flags", "flags", "Flags from the dbt invocation.", "{{ flags.FULL_REFRESH }}", "flags", "dbt variable"),
  describeDbt("modules", "modules", "Modules available in the dbt Jinja context.", "{{ modules.datetime.date.today() }}", "modules", "dbt variable"),
  describeDbt("invocation_id", "invocation_id", "Identifier for the dbt invocation.", "{{ invocation_id }}", "invocation_id", "dbt variable"),
  describeDbt("run_started_at", "run_started_at", "Timestamp at the start of the invocation.", "{{ run_started_at }}", "run_started_at", "dbt variable"),
  describeDbt("project_name", "project_name", "Name declared in dbt_project.yml.", "{{ project_name }}", "project_name", "dbt variable"),
  describeDbt("dbt_version", "dbt_version", "Version of the dbt runtime.", "{{ dbt_version }}", "dbt_version", "dbt variable"),
  describeDbt("selected_resources", "selected_resources", "Resource identifiers selected for the invocation.", "{{ selected_resources }}", "selected_resources", "dbt variable")
];

function describeEdn(name, kind, description, example, url = clauseDocs) {
  return {name, signature: name, kind, description, example, url};
}

export const EDN_HELP = [
  describeEdn(":select", "HoneySQL clause", "Select fields or aliased expressions.", "{:select [:customer_id [[:sum :amount] :revenue]]}"),
  describeEdn(":select-distinct", "HoneySQL clause", "Select distinct field combinations.", "{:select-distinct [:customer_id]}"),
  describeEdn(":from", "HoneySQL clause", "Choose relations. A nested vector assigns an alias.", "{:from [[:orders :o]]}"),
  describeEdn(":where", "HoneySQL clause", "Filter rows with a predicate vector.", "{:where [:and [:= :status \"completed\"] [:> :amount 0]]}"),
  describeEdn(":join", "HoneySQL clause", "Join relation and predicate pairs.", "{:join [[:customers :c] [:= :o.customer_id :c.customer_id]]}"),
  describeEdn(":left-join", "HoneySQL clause", "Preserve left-hand rows without matching right-hand rows.", "{:left-join [[:customers :c] [:= :o.customer_id :c.customer_id]]}"),
  describeEdn(":right-join", "HoneySQL clause", "Preserve right-hand rows when joining.", "{:right-join [:customers [:= :orders.customer_id :customers.customer_id]]}"),
  describeEdn(":full-join", "HoneySQL clause", "Preserve unmatched rows from both joined relations.", "{:full-join [:customers [:= :orders.customer_id :customers.customer_id]]}"),
  describeEdn(":cross-join", "HoneySQL clause", "Combine every row across the listed relations.", "{:cross-join [:customers]}"),
  describeEdn(":group-by", "HoneySQL clause", "Group rows before applying aggregates.", "{:group-by [:customer_id]}"),
  describeEdn(":having", "HoneySQL clause", "Filter grouped rows with an aggregate predicate.", "{:having [:> [:sum :amount] 100]}"),
  describeEdn(":order-by", "HoneySQL clause", "Sort by expressions with optional direction and NULL ordering.", "{:order-by [[:revenue :desc :nulls-last]]}"),
  describeEdn(":limit", "HoneySQL clause", "Limit the returned row count.", "{:limit 100}"),
  describeEdn(":offset", "HoneySQL clause", "Skip rows before returning results.", "{:offset 20}"),
  describeEdn(":with", "HoneySQL clause", "Declare common table expressions.", "{:with [[:totals {:select [:customer_id] :from [:orders]}]]}"),
  describeEdn(":union-all", "HoneySQL clause", "Combine query maps and retain duplicate rows.", "{:union-all [{:select [1]} {:select [2]}]}"),
  describeEdn(":sum", "SQL aggregate", "Sum non-NULL values in each group.", "[:sum :amount]", expressionDocs),
  describeEdn(":count", "SQL aggregate", "Count rows or non-NULL values.", "[:count :*]", expressionDocs),
  describeEdn(":avg", "SQL aggregate", "Average non-NULL values in each group.", "[:avg :amount]", expressionDocs),
  describeEdn(":min", "SQL aggregate", "Return the smallest non-NULL value.", "[:min :created_at]", expressionDocs),
  describeEdn(":max", "SQL aggregate", "Return the largest non-NULL value.", "[:max :created_at]", expressionDocs),
  describeEdn(":coalesce", "SQL function", "Return the first non-NULL argument.", "[:coalesce :country \"Unknown\"]", expressionDocs),
  describeEdn(":cast", "HoneySQL expression", "Cast an expression to a declared SQL type.", "[:cast :created_at :date]", expressionDocs),
  describeEdn(":case", "HoneySQL expression", "Choose a value from ordered conditions.", "[:case [:= :status \"completed\"] 1 :else 0]", expressionDocs),
  describeEdn(":in", "HoneySQL predicate", "Test membership in a value collection.", "[:in :status [\"completed\" \"pending\"]]", expressionDocs),
  describeEdn(":and", "HoneySQL predicate", "Require every nested condition to match.", "[:and [:= :status \"completed\"] [:> :amount 0]]", expressionDocs),
  describeEdn(":or", "HoneySQL predicate", "Match any nested condition.", "[:or [:= :country \"US\"] [:= :country \"CA\"]]", expressionDocs),
  describeEdn(":not", "HoneySQL predicate", "Negate a nested condition.", "[:not [:= :status \"cancelled\"]]", expressionDocs),
  describeEdn(":=", "HoneySQL predicate", "Compare values for equality. A nil operand tests SQL NULL.", "[:= :deleted_at nil]", expressionDocs),
  describeEdn(":<>", "HoneySQL predicate", "Compare values for inequality.", "[:<> :status \"cancelled\"]", expressionDocs),
  describeEdn(":>", "HoneySQL predicate", "Compare values using greater than.", "[:> :amount 0]", expressionDocs),
  describeEdn(":>=", "HoneySQL predicate", "Compare values using greater than or equal.", "[:>= :amount 100]", expressionDocs),
  describeEdn(":<", "HoneySQL predicate", "Compare values using less than.", "[:< :amount 100]", expressionDocs),
  describeEdn(":<=", "HoneySQL predicate", "Compare values using less than or equal.", "[:<= :amount 100]", expressionDocs),
  describeEdn(":between", "HoneySQL predicate", "Match an inclusive range.", "[:between :amount 10 100]", expressionDocs),
  describeEdn(":like", "HoneySQL predicate", "Match a SQL text pattern.", "[:like :email \"%@example.com\"]", expressionDocs),
  describeEdn(":not-in", "HoneySQL predicate", "Exclude values from a collection.", "[:not-in :status [\"cancelled\" \"returned\"]]", expressionDocs),
  describeEdn(":+", "HoneySQL expression", "Add expressions.", "[:+ :subtotal :tax]", expressionDocs),
  describeEdn(":-", "HoneySQL expression", "Subtract expressions.", "[:- :subtotal :discount]", expressionDocs),
  describeEdn(":*", "HoneySQL expression", "Multiply expressions.", "[:* :quantity :unit_price]", expressionDocs),
  describeEdn(":/", "HoneySQL expression", "Divide expressions.", "[:/ :amount :quantity]", expressionDocs),
  describeEdn(":union", "HoneySQL clause", "Combine query maps and remove duplicate rows.", "{:union [{:select [1]} {:select [2]}]}"),
  describeEdn(":intersect", "HoneySQL clause", "Retain rows present in both query maps.", "{:intersect [{:select [:id] :from [:a]} {:select [:id] :from [:b]}]}"),
  describeEdn(":except", "HoneySQL clause", "Remove rows returned by the second query map.", "{:except [{:select [:id] :from [:a]} {:select [:id] :from [:b]}]}"),
  describeEdn(":param", "HoneySQL parameter", "Use a named value supplied in query parameters.", "[:param :customer_id]", expressionDocs)
];

export const SQL_HELP = EDN_HELP.filter(entry => entry.kind.startsWith("SQL")).map(entry => ({
  ...entry, name: entry.name.slice(1).toUpperCase(), signature: entry.name.slice(1).toUpperCase() + "(expression)",
  example: entry.name === ":count" ? "COUNT(*)" : entry.name === ":coalesce" ? "COALESCE(country, 'Unknown')" : entry.name.slice(1).toUpperCase() + "(amount)"
}));
