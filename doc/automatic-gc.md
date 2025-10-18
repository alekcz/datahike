# Automatic Garbage Collection in Datahike Server

## Overview

The Datahike HTTP server now includes automatic garbage collection (GC) functionality that periodically cleans up old database snapshots to prevent storage bloat. This feature is especially important for long-running production deployments where the storage can grow significantly over time.

## Problem Statement

In production deployments, especially with zero-downtime deploys or frequent transactions, Datahike can accumulate many historical snapshots. Each transaction creates a new commit with a complete database state. Without regular garbage collection, storage usage can grow to many times the actual data size (e.g., 550GB storage for 10GB of actual data).

## Solution

The automatic GC feature uses the `overtone/at-at` scheduler to periodically run garbage collection on connected databases. When enabled, it will:

1. Run GC immediately when a database connection is established
2. Schedule periodic GC runs at configurable intervals
3. Retain only recent history based on a configurable retention period
4. Clean up unreachable commits and free storage space

## Configuration

Add the following to your server configuration file:

```edn
{:gc {:enabled true         ; Enable automatic GC (default: true)
      :interval-hours 24   ; Run GC every N hours (default: 24)
      :retention-days 7}}  ; Keep history for N days (default: 7)
```

## How It Works

1. **Connection Lifecycle**: When a client connects to a database via the HTTP API, the server automatically schedules a GC job for that database.

2. **Deduplication**: Multiple connections to the same database+branch combination will NOT create duplicate GC jobs. The scheduler tracks jobs by `[store-id branch]` and skips scheduling if a job already exists.

3. **Immediate Cleanup**: GC runs immediately upon the FIRST connection to clean up any existing bloat. Subsequent connections reuse the existing schedule.

4. **Periodic Runs**: GC is then scheduled to run periodically based on the `interval-hours` setting.

5. **Retention Policy**: Only commits older than `retention-days` are eligible for garbage collection. The branch heads are always retained regardless of age.

6. **Thread Safety**: The scheduling mechanism is thread-safe, preventing race conditions when multiple connections occur simultaneously.

7. **Automatic Management**: The scheduler manages multiple database connections independently, with each database+branch combination having its own GC schedule.

## Important Notes

### GraalVM Compatibility

The `overtone/at-at` dependency is **only** added to the `:http-server` alias, not to the main Datahike library. This ensures that Datahike remains GraalVM-compatible for native image compilation.

### Production Considerations

1. **Initial GC Run**: The first GC run on a bloated database may take some time due to I/O operations when deleting many blobs, but it is NOT CPU or memory intensive. The GC algorithm is efficient and only traverses metadata, not actual data.

2. **Storage Requirements**: The GC process frees up space and does not require significant additional storage during cleanup.

3. **Monitoring**: Monitor your storage usage and GC logs to ensure the feature is working as expected:
   ```clojure
   ;; GC logs will show:
   ;; INFO: Starting scheduled GC for database: <db-name>
   ;; INFO: GC completed successfully for database: <db-name>
   ```

4. **Manual GC**: You can still run manual GC if needed:
   ```clojure
   (require '[datahike.gc :as gc])
   (import '[java.util Date])

   ;; Run GC keeping last 30 days
   (let [cutoff (Date. (- (System/currentTimeMillis) (* 30 24 60 60 1000)))]
     (<!! (gc/gc-storage! conn cutoff)))
   ```

## API Functions

The GC scheduler module (`datahike.http.gc-scheduler`) provides:

- `schedule-gc` - Schedule automatic GC for a database
- `unschedule-gc` - Cancel scheduled GC for a database
- `list-scheduled-jobs` - List all active GC schedules
- `shutdown` - Clean shutdown of all GC jobs

## Troubleshooting

### GC Not Running

Check that:
1. The `:gc :enabled` setting is `true` in your config
2. The server has sufficient permissions to modify the database storage
3. The database connection is successfully established

### Storage Not Decreasing

Remember that:
1. Branch heads are never deleted
2. Only commits older than `retention-days` are removed
3. Some storage backends may not immediately reclaim space

### Performance Characteristics

The GC algorithm is efficient and lightweight:
- **CPU Usage**: Minimal - only traverses commit metadata, not actual data
- **Memory Usage**: Very low - typically 1-5MB for metadata traversal
- **I/O Impact**: The main impact is I/O when deleting many unreachable blobs
- **Non-blocking**: Runs asynchronously without blocking database operations

The performance depends on:
1. Number of accumulated unreachable blobs (not database size)
2. Storage backend I/O characteristics
3. Frequency of GC runs (more frequent = less cleanup per run)

## Migration Guide

For existing deployments with significant storage bloat:

1. **Backup First**: Always backup your database before the first GC run
2. **Run Manual GC**: Consider running manual GC with monitoring before enabling automatic GC
3. **Gradual Cleanup**: Start with a longer retention period and gradually reduce it
4. **Monitor Resources**: Watch CPU, memory, and I/O during initial cleanup

## Example Usage

Starting the server with automatic GC:

```bash
# Create config file
cat > server-config.edn <<EOF
{:port 4000
 :gc {:enabled true
      :interval-hours 24
      :retention-days 7}}
EOF

# Start server
clojure -A:http-server server-config.edn
```

The server will now automatically manage garbage collection for all connected databases, preventing the storage bloat issues seen in long-running production deployments.