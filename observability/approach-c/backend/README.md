# Approach C support backend

The Python REST service uses Flask and Gunicorn, the Python backend alternative shown in the Citrix architecture. Grafana remains the UI. It reads the reporting store directly, including snapshots collected through this API and recorded L3 tool results. It does not send arbitrary SQL or tool commands to the backend.

The database adapter reads an approved aggregate view of local Mocknet trade and queue counts. The local ODS emulator writes a delayed JSON export from those aggregates every thirty seconds. This exercises a separate snapshot and freshness boundary; it does not implement the real ODS contract. COR, LG2 and UDG remain explicitly unconfigured because the slide provides no schemas or connection details.

Application logs, committed journals, the checkpointed collector and the reconstructed waterfall remain in use. A separate source collection worker calls the API every ten seconds. A failed API or source cannot block journal ingestion. Original source timestamps are retained, and source values are never used to invent missing waterfall events.

## Run

From the repository root:

```bash
bash script/mocknet-approach-c.sh start
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py sources
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py tools
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py run application-health
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py run queue-diagnostics
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py run operation-evidence --operation-id OPERATION_ID
.bootstrap/observability/approach-c/venv/bin/python script/mocknet-c-api.py runs
```

The CLI loads the appropriate credential from the local secret file. Tokens do not appear in arguments, dashboard JSON, URLs or browser storage. Diagnostic results appear in [Sources and L3 diagnostics](http://localhost:3302/d/mocknet-c-sources). Grafana does not execute tools; use the CLI or an authenticated API client.

`api-start` and `api-stop` manage only the backend. Restart it after backend code/configuration changes. The main `start` command provisions the source views and reporting tables, starts the backend, and refreshes the collector. `stop` shuts down C's processes and containers without removing their volumes.

## API

Base URL: `http://127.0.0.1:18103`. Except for `/health`, requests require `Authorization: Bearer TOKEN`.

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /health` | Public loopback | Process liveness, not source health |
| `GET /api/v1/sources` | Reader or operator | Independent status, source time and bounded rows for each adapter |
| `GET /api/v1/operations/{operation_id}` | Reader or operator | Reporting summary and up to 100 component records |
| `GET /api/v1/tools` | Reader or operator | Fixed read-only diagnostic catalogue |
| `POST /api/v1/tools/{tool}/runs` | Operator | Audited diagnostic result |
| `GET /api/v1/tools/runs` | Reader or operator | Latest 50 run records |

POST bodies are `{}` for `application-health` and `queue-diagnostics`, and `{"operation_id":"..."}` for `operation-evidence`. Unknown tool names, extra arguments, invalid operation IDs and bodies over 4 KiB are rejected. The API accepts no commands, SQL, filesystem paths or target URLs. Health checks use a fixed configured endpoint, disallow redirects and have a three-second timeout. Database queries have a three-second statement timeout and bounded results.

Every diagnostic request is recorded before execution. A failed audit insert prevents execution. Results include failures without raw exception text. A worker crash may leave a running record; the dashboard marks it interrupted after two minutes. Reporting maintenance expires run records after 72 hours. These are local diagnostic records, not a regulatory audit archive.

## Access and deployment boundary

- Gunicorn binds only to `127.0.0.1:18103`, with two workers and two threads per worker. Grafana and PostgreSQL retain their loopback bindings.
- Distinct generated reader and operator tokens enforce local API roles. Run attribution is `local-operator`, not an individually authenticated employee.
- `c_source_reader` can connect only to the application database and select `c_support.database_summary`. It cannot select raw business tables.
- `c_backend_reader` can connect only to reporting and select the diagnostic views. `c_backend_writer` can insert/update run records and read their IDs; neither can alter evidence.
- Grafana retains its read-only reporting role and existing local anonymous Viewer access.
- Backend settings come from `settings.json`; passwords and API tokens come from the local secret file. No secrets are returned by the configuration adapter.

This is a local deployment. A hosted deployment still needs an internal ingress with TLS and the organization's identity provider, individual role mapping, Grafana managed login, and real source adapters. Keep database and backend listeners private behind that ingress. The existing local bearer credentials and anonymous Grafana mode do not constitute that integration. No alarm or notification components are added.

Gunicorn invocation follows the [Flask deployment documentation](https://flask.palletsprojects.com/en/stable/deploying/gunicorn/); the worker configuration follows [Gunicorn's threaded worker design](https://docs.gunicorn.org/en/stable/design.html).

## Validation

```bash
.bootstrap/observability/approach-c/venv/bin/python -m pytest -q observability/approach-c/backend/tests
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-api.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-approach-c.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-waterfall.py
```

The live API check verifies role boundaries, source provenance, three recorded diagnostic runs, operation lookup and stale-source display using a rolled-back fixture. It saves results under `.bootstrap/observability/approach-c/support-api-validation.json`.
