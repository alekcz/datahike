# Architecture Decision Record 006 - Automated Garbage Collection for HTTP Server

## Context

Datahike accumulates historical database snapshots over time, with each transaction creating a new commit containing the complete database state. In production deployments, especially those using zero-downtime deployment strategies, this can lead to severe storage bloat. Users have reported cases where 10GB of actual data occupies 550GB of storage after 3 years of operation - a 55x bloat factor.

The issue is particularly acute in embedded mode deployments where:
- Zero-downtime deployments create overlapping writer instances
- Each deployment creates new commits without cleaning up old ones
- Manual garbage collection is often forgotten or not scheduled regularly
- The experimental nature of GC makes users hesitant to use it

Related discussion: Storage bloat in production deployments with PostgreSQL backend

## Options

### Option A: Manual GC Only (Status Quo)

Keep garbage collection as a manual operation that users must schedule themselves.

#### Pro
- No additional dependencies
- Full control for users over when GC runs
- No risk of automated processes affecting production systems
- Maintains GraalVM compatibility throughout the codebase

#### Contra
- Users frequently forget to run GC, leading to massive storage bloat
- Requires external scheduling infrastructure (cron, systemd timers, etc.)
- Operational burden on users to monitor and maintain GC schedules
- Risk of storage exhaustion in production systems

### Option B: Built-in GC in Core Library

Add scheduling capabilities directly to the core Datahike library with automatic GC on connection.

#### Pro
- GC available for all deployment modes (embedded, server, pod)
- Consistent behavior across all usage patterns
- No separation between server and library features

#### Contra
- Breaks GraalVM native image compatibility due to scheduling library dependencies
- Adds complexity to the core library for a feature only some users need
- Scheduling libraries (at-at, chime, etc.) add significant dependencies
- May run GC when not desired in embedded applications

### Option C: Server-Only Automated GC (Implemented)

Add automated GC capabilities exclusively to the HTTP server using overtone/at-at scheduler.

#### Pro
- Preserves GraalVM compatibility for core library
- Zero configuration - works automatically when using server
- Prevents storage bloat without user intervention
- Configurable retention and scheduling policies
- Thread-safe handling of multiple connections
- Minimal performance impact (GC is lightweight, only I/O intensive during sweep)

#### Contra
- Only available for HTTP server deployments
- Adds overtone/at-at dependency to server (1.2.0, stable since 2012)
- Embedded mode users must still handle GC manually
- Additional complexity in server connection lifecycle

### Option D: External GC Service

Create a separate service/process that connects to Datahike and performs GC.

#### Pro
- Complete separation of concerns
- Could work with any Datahike deployment
- No dependencies added to Datahike itself

#### Contra
- Requires additional deployment and configuration
- Another service to monitor and maintain
- Needs credentials and network access to databases
- More complex than built-in solution

## Status

**IMPLEMENTED**

## Decision

We implemented **Option C: Server-Only Automated GC** with the following design:

1. **Dependency Isolation**: overtone/at-at is added only to the `:http-server` alias, preserving GraalVM compatibility for the core library

2. **Automatic Scheduling**: GC is scheduled automatically when clients connect to a database through the HTTP API

3. **Deduplication**: Multiple connections to the same database+branch combination share a single GC schedule, preventing duplicate jobs

4. **Configurable Behavior**:
   - `gc.enabled`: Enable/disable automated GC (default: true)
   - `gc.interval-hours`: How often to run GC (default: 24 hours)
   - `gc.retention-days`: Days of history to retain (default: 7 days)

5. **Performance Characteristics**:
   - Lightweight mark phase: Only traverses commit metadata (1-5MB memory)
   - I/O-bound sweep phase: Time depends on number of blobs to delete
   - Non-blocking: Runs asynchronously without affecting database operations

## Consequences

### Positive Consequences

1. **Storage Efficiency**: Production deployments will no longer experience unbounded storage growth. The 55x bloat scenario is prevented automatically.

2. **Zero Maintenance**: Server users get automatic GC without any configuration or operational overhead.

3. **Backward Compatibility**: Existing deployments continue to work. GC can be disabled if needed.

4. **GraalVM Preservation**: Core library remains fully compatible with native image compilation.

5. **Safe Defaults**: 7-day retention preserves recent history while preventing bloat.

### Negative Consequences

1. **Feature Disparity**: Embedded mode users don't benefit from automated GC and must continue manual management.

2. **Additional Dependency**: The server now depends on overtone/at-at (though it's a mature, stable library).

3. **Migration Consideration**: Existing bloated databases will experience heavy I/O during the first GC run as years of accumulated garbage are cleaned up.

### Migration Path

For existing deployments with storage bloat:

1. **Backup First**: Create a full backup before the first automated GC run
2. **Initial Cleanup**: The first GC will be I/O intensive, deleting potentially millions of unreachable blobs
3. **Monitor Resources**: Watch I/O metrics during initial cleanup
4. **Gradual Approach**: Consider starting with longer retention periods and reducing gradually

### Long-term Implications

1. **Operational Simplicity**: Reduces operational burden for production deployments
2. **Cost Savings**: Prevents unnecessary storage costs from bloat
3. **Reliability**: Prevents storage exhaustion failures
4. **Performance**: Regular GC prevents accumulation, keeping sweep operations fast

### Documentation and Communication

Created comprehensive documentation:
- `doc/automatic-gc.md`: User guide with configuration and troubleshooting
- `http-server/config-example-gc.edn`: Example configuration file
- Updated performance characteristics to clarify GC is not CPU/memory intensive

This change should be announced to users as a significant operational improvement for HTTP server deployments, while making clear that embedded mode users should continue their current GC practices.