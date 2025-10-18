# Architecture Decision Record 005 - Dynamic Backend Loading

## Context

Datahike supports multiple storage backends through the Konserve abstraction layer, including:
- In-memory (`:mem`)
- File-based (`:file`)
- JDBC databases (`:jdbc` via datahike-jdbc)
- Redis (`:redis` via datahike-redis)
- S3 (`:s3` via datahike-s3)
- LevelDB (`:level` via datahike-leveldb)

Initially, including all backend implementations in the core library would:
- Drastically increase JAR size with database drivers (PostgreSQL, MySQL, SQLite, etc.)
- Force all users to have dependencies they don't use
- Create version conflicts between different database drivers
- Make the core library incompatible with GraalVM native compilation due to JDBC drivers
- Prevent organizations from using proprietary/custom backends

The HTTP server mode particularly needs flexibility as different clients may require different backends, but the server cannot know in advance which backends will be needed.

## Options

### Option A: Static Compilation (Original Approach)

Include all backend implementations directly in datahike core.

#### Pro
- Simple - everything works out of the box
- No runtime loading complexity
- All backends tested together
- Single version to manage

#### Contra
- Massive JAR size (100MB+ with all JDBC drivers)
- Dependency conflicts between database drivers
- GraalVM native compilation impossible
- Users forced to have unused dependencies
- Security concerns from including all database drivers
- Cannot support proprietary backends

### Option B: Manual Backend Loading

Require users to explicitly load backends in their code before use.

```clojure
(require 'datahike-jdbc.core)  ; User must do this
(d/create-database {:store {:backend :jdbc ...}})
```

#### Pro
- Explicit and clear what's being used
- No magic or hidden behavior
- Works with existing Clojure tooling

#### Contra
- Poor user experience
- Easy to forget the require
- HTTP server cannot know what clients need
- Breaks when backend changes implementation namespace

### Option C: Dynamic Backend Loading (Implemented)

Use Clojure's runtime `require` to load backend namespaces on-demand based on convention.

#### Pro
- Small core JAR (~5MB vs 100MB+)
- Users only depend on backends they need
- GraalVM compatibility preserved for core
- Supports proprietary/custom backends
- Backends can evolve independently
- HTTP server can load any backend at startup
- Convention-based (`datahike-{name}.core`)

#### Contra
- Runtime loading can fail if backend not on classpath
- Slightly more complex error handling
- Requires convention for backend naming
- Testing requires backends to be available

### Option D: Plugin System with Manifests

Create a full plugin system with manifest files, versioning, and lifecycle management.

#### Pro
- Most flexible and extensible
- Version compatibility checking
- Rich plugin metadata
- Could support hot-reloading

#### Contra
- Significant complexity increase
- Over-engineered for the use case
- More difficult to implement and maintain
- Not idiomatic Clojure

## Status

**IMPLEMENTED**

## Decision

We implemented **Option C: Dynamic Backend Loading** using the following design:

1. **Multimethod Dispatch**: Storage backends register themselves via `defmethod` on the `empty-store`, `delete-store`, and `connect-store` multimethods that dispatch on the `:backend` keyword.

2. **Convention-Based Loading**: Backend libraries follow the naming convention `datahike-{backend}` with the implementation in the `.core` namespace.

3. **Built-in Backends**: `:mem` and `:file` remain in core as they have no external dependencies.

4. **HTTP Server Loading**: The server's `load-backends` function accepts a `:stores` configuration key listing backend namespaces to load at startup:
   ```clojure
   {:stores ["datahike-jdbc.core" "datahike-redis.core"]}
   ```

5. **Lazy Loading**: Backends are only loaded when needed - either explicitly by the HTTP server or implicitly when required by client code.

6. **Error Handling**: Clear error messages when a backend is not available on the classpath.

## Consequences

### Positive Consequences

1. **Minimal Core Size**: Core Datahike JAR remains small (~5MB) and focused on the database engine.

2. **Dependency Freedom**: Users only include dependencies for backends they actually use:
   ```clojure
   ;; deps.edn - only include what you need
   {:deps {io.replikativ/datahike {:mvn/version "0.6.1"}
           io.replikativ/datahike-jdbc {:mvn/version "0.3.50"}}}
   ```

3. **GraalVM Compatibility**: Core library remains compatible with native image compilation.

4. **Independent Evolution**: Backends can be versioned and released independently:
   - Bug fixes don't require core releases
   - New database support added without touching core
   - Breaking changes isolated to specific backends

5. **Custom Backends**: Organizations can create proprietary backends without forking Datahike:
   ```clojure
   (defmethod store/empty-store :custom-backend [config]
     ...)
   ```

6. **HTTP Server Flexibility**: Server can support any combination of backends without recompilation.

### Negative Consequences

1. **Runtime Failures**: Missing backends only discovered at runtime:
   ```
   ERROR: Failed to load backend: datahike-jdbc.core. Is it on the classpath?
   ```

2. **Additional Dependencies**: Users must explicitly add backend dependencies:
   ```clojure
   ;; User must remember both dependencies
   io.replikativ/datahike
   io.replikativ/datahike-jdbc
   ```

3. **Testing Complexity**: Tests must conditionally run based on available backends.

4. **Discovery Challenge**: Users may not know which backends are available or how to add them.

### Implementation Details

The store multimethod in `src/datahike/store.cljc`:
```clojure
(defmulti empty-store
  "Creates an empty store"
  {:arglists '([config])}
  (fn [{:keys [backend]}] backend))

(defmethod empty-store :default [{:keys [backend]}]
  (throw (ex-info (str "Can't create a store with backend " backend ".")
                  {:backend backend})))
```

HTTP server loading in `http-server/datahike/http/server.clj`:
```clojure
(defn load-backends [config]
  (let [backends (:stores config)]
    (doseq [backend backends]
      (try
        (require (symbol backend))
        (println "Loaded backend:" backend)
        (catch Exception e
          (log/error "Failed to load backend:" backend)
          (throw e))))))
```

### Real-World Usage Examples

#### Docker Deployment with PostgreSQL

The [datahike-server](https://github.com/alekcz/datahike-server) Docker image demonstrates production usage with dynamic backend loading:

```bash
docker run \
  -p 4444:4444 \
  -e DATAHIKE_STORES='["datahike-jdbc.core"]' \
  -e DATAHIKE_EXTRA_DEPS='[[io.replikativ/datahike-jdbc "0.3.50"]
                            [org.postgresql/postgresql "42.7.5"]]' \
  ghcr.io/alekcz/datahike-server:latest
```

The server dynamically downloads and loads the JDBC backend at startup without rebuilding the image.

#### Multiple Backends Configuration

```clojure
;; config.edn for HTTP server
{:port 4444
 :stores ["datahike-jdbc.core"
          "datahike-redis.core"
          "company.proprietary-backend.core"]}
```

#### Environment-Based Backend Selection

```bash
# Development: Use file backend (built-in, no extra deps needed)
docker run -e DATAHIKE_STORES='[]' datahike-server

# Staging: Use Redis
docker run -e DATAHIKE_STORES='["datahike-redis.core"]' \
           -e DATAHIKE_EXTRA_DEPS='[[io.replikativ/datahike-redis "0.1.7"]]' \
           datahike-server

# Production: Use PostgreSQL
docker run -e DATAHIKE_STORES='["datahike-jdbc.core"]' \
           -e DATAHIKE_EXTRA_DEPS='[[io.replikativ/datahike-jdbc "0.3.50"]
                                     [org.postgresql/postgresql "42.7.5"]]' \
           datahike-server
```

This approach enables the same Docker image to be used across all environments with backend selection via configuration.

### Migration Guide

For existing users:

1. **Check Dependencies**: Ensure backend libraries are in your deps.edn/project.clj
2. **HTTP Server Config**: Add `:stores` key listing required backends
3. **Error Messages**: Watch for "backend not found" errors and add missing dependencies
4. **Custom Backends**: Follow the naming convention and implement required multimethods
5. **Docker Users**: Use environment variables to inject backends without rebuilding

### Long-term Implications

1. **Ecosystem Growth**: Easier for community to contribute new backends
2. **Enterprise Adoption**: Custom backends enable proprietary storage systems
3. **Cloud Native**: Different deployments can use different backends (dev=file, prod=jdbc)
4. **Maintenance**: Backend-specific issues isolated from core development

This architecture has proven successful, with multiple backend implementations available and the ability to support diverse deployment scenarios without compromising the core library's simplicity or compatibility.