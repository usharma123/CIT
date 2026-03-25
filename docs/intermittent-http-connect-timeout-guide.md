# Intermittent `java.net.http.HttpConnectTimeoutException` Troubleshooting Guide

## Problem Summary
We are seeing intermittent:

`java.net.http.HttpConnectTimeoutException: HTTP connect timed out`

This means the client could not establish a TCP connection to the target endpoint within the configured connect timeout. This occurs **before** request/response processing.

## Deployment Scenario
- The application was first deployed manually.
- It was tested using an auto-request sender app and worked as expected.
- The same environment was then handed over to the DevOps team.
- After deployment through automation scripts, intermittent connect timeouts started appearing.
- Setup appears correct at a glance, but failures continue intermittently.

## What This Usually Points To
Intermittent connect timeout issues after scripted deployment commonly indicate:
- Environment drift introduced by automation.
- Partial infrastructure misconfiguration (only some nodes/targets affected).
- Resource saturation under the automated topology.

Typical failure path to inspect:

`DNS -> Routing -> Firewall/SG/NACL -> LB/Ingress -> Target listener -> Connection capacity`

## Most Likely Root Causes (Priority Order)

### 1) DNS or Service Discovery Drift
**Why it fits**
- Manual deployment and scripted deployment may resolve to different targets.
- If DNS returns multiple records and one target is bad, failures look intermittent.

**Checks**
```bash
dig +short your-service.domain
getent hosts your-service.domain
nc -vz <ip> <port>
curl -v --connect-timeout 2 http://<ip>:<port>/health
```
Run the DNS resolution multiple times and test each returned IP directly.

**Fixes**
- Remove bad records.
- Publish only healthy endpoints.
- Reduce TTL during rollout.
- Prefer LB DNS name over direct instance IP.

### 2) Load Balancer / Ingress Target Instability
**Why it fits**
- DevOps automation may register targets before they are truly ready.
- Health-check mismatch can route traffic to non-ready targets.

**Checks**
- Inspect target health history for flapping.
- Validate listener port, health check path, and health check port.
- Verify cross-zone behavior and AZ coverage.

**Fixes**
- Correct health checks.
- Add real readiness endpoint.
- Delay traffic until app is actually listening.
- Ensure targets exist in all intended zones.

### 3) Security Group / Firewall / NACL Differences
**Why it fits**
- Manual deployment may have been permissive.
- Scripted deployment may place workloads in stricter subnets/SGs.

**Checks**
- Compare outbound client rules and inbound server rules.
- Validate NACLs, route tables, and NAT path.
- Confirm failing instance subnet/SG is as expected.

**Fixes**
- Align SG/NACL rules across all instances.
- Correct routing and NAT associations.

### 4) Proxy or Egress Path Differences
**Why it fits**
- Automation often injects `HTTP_PROXY`/`HTTPS_PROXY` or JVM proxy flags.
- Intermittent proxy availability causes intermittent connect timeout.

**Checks**
```bash
echo $HTTP_PROXY $HTTPS_PROXY $NO_PROXY
```
Check JVM flags:
- `-Dhttp.proxyHost`
- `-Dhttps.proxyHost`
- `-Djava.net.useSystemProxies`

**Fixes**
- Set `NO_PROXY` correctly for internal services.
- Stabilize proxy path.
- Disable unintended proxy usage.

### 5) Port/Connection Exhaustion (Client, NAT, or SNAT)
**Why it fits**
- Scripted deployment may increase concurrency.
- NAT or host ephemeral ports can saturate under burst load.

**Checks**
```bash
ss -s
ss -tan state syn-sent
cat /proc/sys/net/ipv4/ip_local_port_range
netstat -an | grep TIME_WAIT | wc -l
```
Also check cloud NAT metrics such as active connection count and port allocation errors.

**Fixes**
- Reuse keep-alive connections.
- Reduce connection churn.
- Increase ephemeral port range.
- Scale or distribute NAT egress.

### 6) Tomcat Binding or Port Mismatch on Some Nodes
**Why it fits**
- One or more nodes may bind to wrong address/port.
- Intermittent behavior appears when LB rotates through mixed-good/misconfigured targets.

**Checks**
```bash
ss -lntp | grep <port>
```
Confirm each instance listens on the expected address and port.

**Fixes**
- Bind correctly (commonly `0.0.0.0` when required).
- Align container/service port mappings.
- Ensure all nodes have identical connector config.

### 7) TLS Edge Cases (Less Likely for Pure Connect Timeout)
**Checks**
```bash
curl -vk https://host:port
```
Validate SNI, certificate chain, and CA bundle in runtime image.

### 8) Connect Timeout Too Aggressive
**Why it fits**
- Small timeout values fail under normal latency variance in production.

**Checks**
- Review `HttpClient.newBuilder().connectTimeout(...)`.
- Compare connect latency p50/p95/p99 from real runtime hosts.

**Fixes**
- Raise connect timeout moderately (for example 2-5s).
- Add jittered retries for idempotent operations only.

## Fast Isolation Checklist (Run in This Order)
1. Log resolved destination per failed request (IP, port, and proxy usage).
2. From the failing runtime host/container, run repeated:
   - `dig +short <target>`
   - `curl -v --connect-timeout 2 http://<target>:<port>/health`
3. If DNS returns multiple IPs, test each IP directly.
4. Check LB target health and correlate failures to target/AZ.
5. Check NAT/proxy saturation and client `SYN-SENT` spikes.
6. Verify listener/bind config on **every** server node.

## Tomcat: Which Files to Check First
For connect timeouts, `server.xml` and startup/runtime environment are highest priority.

### 1) `$CATALINA_BASE/conf/server.xml` (Highest Priority)
Check `<Connector ...>` settings:
- `address` (wrong binding can make node unreachable).
- `port`, `redirectPort`.
- `proxyName`, `proxyPort`, `scheme`, `secure` (when behind proxy/LB).
- Capacity controls:
  - `maxConnections`
  - `acceptCount`
  - `maxThreads`
  - `connectionTimeout`
  - `keepAliveTimeout`
  - `maxKeepAliveRequests`

If using `<Executor ...>`:
- Validate thread pool sizing and connector executor linkage.

If HTTPS connector is used:
- Confirm keystore path/password.
- Confirm protocol/cipher configuration.
- Confirm listener port alignment.

### 2) `$CATALINA_BASE/bin/setenv.sh` (or service unit / container env)
Look for deployment-induced runtime drift:
- Proxy flags: `-Dhttp.proxyHost`, `-Dhttps.proxyHost`, `-Djava.net.useSystemProxies`.
- IP stack behavior: `-Djava.net.preferIPv4Stack=true` (or opposite).
- JVM memory/GC changes causing pause or saturation.
- Java version differences between manual and scripted deployment.

### 3) `$CATALINA_BASE/conf/context.xml` and `conf/Catalina/localhost/*.xml`
These can indirectly trigger saturation:
- JNDI DataSource pool sizing.
- Validation query and timeout settings.
- Per-app context overrides applied only in scripted deployment.

### 4) `WEB-INF/web.xml` and global `conf/web.xml` (Lower Priority for Connect Timeout)
Usually affects behavior **after** connection accept. Still verify if changed:
- Heavy filters/interceptors.
- Auth chain complexity.
- Multipart and session settings that may increase blocking.

### 5) `$CATALINA_BASE/conf/catalina.properties` and logging config
Lower probability, but review for major runtime or logging behavior drift.

## Non-Tomcat Checks You Should Not Skip
- OS limits and backlog:
```bash
ulimit -n
sysctl net.core.somaxconn
sysctl net.ipv4.tcp_max_syn_backlog
```
- LB configuration consistency across all nodes/targets.

## Quick Drift Detection Between Manual vs Scripted Deployments
```bash
diff -ruN <manual>/conf <scripted>/conf
diff -ruN <manual>/bin <scripted>/bin
diff -ruN <manual>/conf/Catalina/localhost <scripted>/conf/Catalina/localhost
```

## Minimal Data to Collect for Rapid Root-Cause Identification
Capture these six items:
1. Client runtime location and server runtime location (same VPC/subnet or crossing NAT/proxy).
2. Target scheme/port and LB presence.
3. Exact connect timeout value.
4. DNS behavior (multiple A records and/or AAAA records).
5. Proxy configuration (env vars and JVM flags).
6. Correlation to specific targets/AZs/instances.

## Practical Bottom Line
For this pattern (manual deploy works, scripted deploy intermittently times out), the most common causes are:
- One or more bad targets behind DNS/LB.
- Egress/NAT connection port exhaustion.
- Tomcat connector/bind/capacity drift in `server.xml` and startup environment.
