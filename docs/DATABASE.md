# Database

## Production Choice

Server deployments should use PostgreSQL with TimescaleDB.

Heart-rate data is a low-frequency time series. Writes arrive in small batches, while reads are dominated by:

- latest participant state for the lobby;
- recent samples by `collector_id` and time window;
- idempotency checks by `collector_id + seq`.

PostgreSQL gives mature constraints, transactions, indexing, and operational tooling. TimescaleDB adds hypertables for time-window storage and retention-friendly queries without changing the application API.

SQLite remains supported for local development and tests, but it is not the recommended server database.

## Configuration

Set `HEARTWITH_DATABASE_URL` before starting the Rust server:

```bash
export HEARTWITH_DATABASE_URL='postgres://heartwith:heartwith@127.0.0.1:5432/heartwith'
cargo run -p heartwith-server
```

If `HEARTWITH_DATABASE_URL` starts with `postgres://` or `postgresql://`, the server uses PostgreSQL. Other URLs continue to use SQLite, for example:

```bash
HEARTWITH_DATABASE_URL='sqlite://heartwith.db' cargo run -p heartwith-server
```

## Schema

- `collectors`: one logical collector per display name, token, device metadata, latest BPM.
- `collector_seqs`: recent idempotency window for `collector_id + seq`.
- `heart_rate_samples`: append-only heart-rate samples with `(collector_id, t_ms)` indexes.

When TimescaleDB is installed, `heart_rate_samples` is converted to a hypertable on `t_ms` with 1-hour chunks. If the extension is unavailable, the server logs a warning and continues with plain PostgreSQL indexes.

## Example TimescaleDB

```bash
docker run --name heartwith-timescaledb \
  -e POSTGRES_DB=heartwith \
  -e POSTGRES_USER=heartwith \
  -e POSTGRES_PASSWORD=heartwith \
  -p 5432:5432 \
  -d timescale/timescaledb:latest-pg16
```
