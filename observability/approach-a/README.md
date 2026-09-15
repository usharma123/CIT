# Approach A: application-aware L3 diagnosis

Approach A is the complete implementation developed before the JVM-only comparison. Its live dashboards retain their existing UIDs and links:

- `mocknet-traces`: Approach A | Service overview
- `mocknet-stages`: Approach A | Queue and stage diagnostics
- `mocknet-operation`: Approach A | Operation investigation

It combines custom and Java-agent spans, Micrometer application/runtime metrics, durable queue and attempt history, read-only SQL support views, Loki logs and Tempo traces. It can identify a trade, queue, attempt, failure reason, worker and SQL call.

Source remains in the established locations so existing commands and links continue to work:

- Application: `mocknet/src/`, `mocknet/pom.xml`
- Stack: `observability/compose.yaml`, `collector.yaml`, `prometheus/`, `postgres/`
- Dashboards: `script/build-mocknet-dashboards.py`, `observability/grafana/dashboards/`
- Launch and workload: `script/mocknet-grafana.sh`, `mocknet-stream.py`, `mocknet-l3-demo.py`
- Runbook and evidence: `observability/README.md`, `L3-VALIDATION.md`, `QUEUE-INCIDENT-REVIEW.md`

Before constructing B, the complete application source, dashboard/configuration and scripts were archived locally at `.bootstrap/observability/approach-a/source-snapshot.tar.gz`. The application artifact was preserved as `app.jar`, with both SHA-256 checksums in `SHA256SUMS`. Generated secrets, database contents and unrelated workspace files are excluded. These are local snapshots, not a Git commit.

Start/control A with `bun run mocknet:grafana`. Its backend stays on port 18081 and its existing transaction feed remains independent of B.

The launcher runs `.bootstrap/observability/app-runtime.jar`, copied after building, so a subsequent B build cannot replace the running A artifact. Both launchers use `script/mocknet-detach.py` to keep their JVMs independent of the launching terminal. To restore the saved original artifact after stopping A, start with `MOCKNET_APP_JAR_SOURCE="$PWD/.bootstrap/observability/approach-a/app.jar" MOCKNET_VERSION=l3-demo-v2 bash script/mocknet-grafana.sh start`. The archive is never modified by normal builds.
