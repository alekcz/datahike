# Dynamic Backend Loading in Datahike

## Overview

Datahike supports multiple storage backends through a dynamic loading mechanism that keeps the core library lightweight while allowing flexible storage options. This document explains how to use and configure different backends for your Datahike deployment.

## Available Backends

### Built-in Backends (No Additional Dependencies)

- **`:mem`** - In-memory storage (default)
- **`:file`** - File-based persistent storage

### External Backends (Require Additional Dependencies)

- **`:jdbc`** - JDBC databases (PostgreSQL, MySQL, SQLite, etc.) via [datahike-jdbc](https://github.com/replikativ/datahike-jdbc)
- **`:redis`** - Redis storage via [datahike-redis](https://github.com/replikativ/datahike-redis)
- **`:s3`** - Amazon S3 storage via [datahike-s3](https://github.com/replikativ/datahike-s3)
- **`:level`** - LevelDB storage via [datahike-leveldb](https://github.com/replikativ/datahike-leveldb)

## How Dynamic Loading Works

Datahike uses Clojure's multimethod system to dispatch backend operations based on the `:backend` keyword in your configuration. When you specify a backend that isn't built-in, Datahike attempts to load it at runtime using the convention `datahike-{backend}.core`.

## Configuration Methods

### Method 1: Direct Usage (Embedded Mode)

Add the backend dependency to your project:

```clojure
;; deps.edn
{:deps {io.replikativ/datahike {:mvn/version "0.6.1"}
        io.replikativ/datahike-jdbc {:mvn/version "0.3.50"}
        org.postgresql/postgresql {:mvn/version "42.7.5"}}}
```

Use the backend in your code:

```clojure
(require '[datahike.api :as d])

(def config {:store {:backend :jdbc
                     :dbtype "postgresql"
                     :host "localhost"
                     :dbname "datahike"
                     :user "datahike"
                     :password "password"}})

(d/create-database config)
(def conn (d/connect config))
```

### Method 2: HTTP Server Configuration

Configure the server to load backends at startup:

```clojure
;; server-config.edn
{:port 4444
 :stores ["datahike-jdbc.core"
          "datahike-redis.core"]}
```

Start the server with the configuration:

```bash
clojure -A:http-server server-config.edn
```

### Method 3: Docker with Environment Variables

The [datahike-server](https://github.com/alekcz/datahike-server) Docker image supports dynamic backend loading through environment variables:

```bash
docker run \
  -p 4444:4444 \
  -e DATAHIKE_STORES='["datahike-jdbc.core"]' \
  -e DATAHIKE_EXTRA_DEPS='[[io.replikativ/datahike-jdbc "0.3.50"]
                            [org.postgresql/postgresql "42.7.5"]]' \
  ghcr.io/alekcz/datahike-server:latest
```

## Backend-Specific Configuration

### PostgreSQL Example

```clojure
{:store {:backend :jdbc
         :dbtype "postgresql"
         :host "localhost"
         :port 5432
         :dbname "datahike_db"
         :user "postgres"
         :password "secret"
         :table "datahike_storage"}}
```

### Redis Example

```clojure
{:store {:backend :redis
         :uri "redis://localhost:6379"
         :key-prefix "datahike:"}}
```

### S3 Example

```clojure
{:store {:backend :s3
         :bucket "my-datahike-bucket"
         :region "us-east-1"
         :access-key "AKIAIOSFODNN7EXAMPLE"
         :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"}}
```

## Environment-Based Backend Selection

Use different backends for different environments without code changes:

```bash
# Development
export DATAHIKE_BACKEND=file
export DATAHIKE_PATH=/tmp/datahike-dev

# Staging
export DATAHIKE_BACKEND=redis
export DATAHIKE_URI=redis://staging-redis:6379

# Production
export DATAHIKE_BACKEND=jdbc
export DATAHIKE_DBTYPE=postgresql
export DATAHIKE_HOST=prod-db.example.com
export DATAHIKE_DBNAME=datahike_prod
```

Load configuration from environment:

```clojure
(defn config-from-env []
  (let [backend (keyword (System/getenv "DATAHIKE_BACKEND"))]
    (case backend
      :file {:store {:backend :file
                     :path (System/getenv "DATAHIKE_PATH")}}
      :redis {:store {:backend :redis
                      :uri (System/getenv "DATAHIKE_URI")}}
      :jdbc {:store {:backend :jdbc
                     :dbtype (System/getenv "DATAHIKE_DBTYPE")
                     :host (System/getenv "DATAHIKE_HOST")
                     :dbname (System/getenv "DATAHIKE_DBNAME")}}
      ;; Default to in-memory
      {:store {:backend :mem}})))
```

## Creating Custom Backends

Organizations can create proprietary backends by implementing the required multimethods:

```clojure
(ns mycompany.datahike-custom.core
  (:require [datahike.store :as store]
            [konserve.core :as k]))

;; Implement the required multimethods
(defmethod store/empty-store :custom [config]
  ;; Return a konserve store implementation
  (my-custom-store/create config))

(defmethod store/delete-store :custom [config]
  ;; Delete the store
  (my-custom-store/delete config))

(defmethod store/connect-store :custom [config]
  ;; Connect to existing store
  (my-custom-store/connect config))

(defmethod store/release-store :custom [config store]
  ;; Release resources
  (my-custom-store/release store))

(defmethod store/store-identity :custom [config]
  ;; Return unique identifier
  (str "custom:" (:path config)))
```

Use your custom backend:

```clojure
;; Ensure your namespace is loaded
(require 'mycompany.datahike-custom.core)

(def config {:store {:backend :custom
                     :path "/custom/storage/path"
                     :custom-option "value"}})

(d/create-database config)
```

## Troubleshooting

### Backend Not Found Error

```
ERROR: Can't create a store with backend :jdbc
```

**Solution**: Add the backend dependency to your project:
```clojure
io.replikativ/datahike-jdbc {:mvn/version "0.3.50"}
```

### Backend Failed to Load

```
ERROR: Failed to load backend: datahike-jdbc.core. Is it on the classpath?
```

**Solution**:
1. Check that the backend library is in your dependencies
2. Verify the version is compatible with your Datahike version
3. For HTTP server, ensure the backend is listed in `:stores` configuration

### Database Driver Missing

```
ERROR: No suitable driver found for jdbc:postgresql://localhost/datahike
```

**Solution**: Add the database driver dependency:
```clojure
org.postgresql/postgresql {:mvn/version "42.7.5"}
```

## Performance Considerations

1. **Backend Selection**:
   - `:mem` - Fastest, but no persistence
   - `:file` - Good for single-node deployments
   - `:jdbc` - Best for multi-node, production deployments
   - `:redis` - Good for caching and temporary data

2. **Connection Pooling**: JDBC backend supports connection pooling for better performance

3. **Network Latency**: Remote backends (JDBC, Redis, S3) add network latency

## Best Practices

1. **Development**: Use `:mem` or `:file` for fast iteration
2. **Testing**: Use `:mem` for unit tests, same backend as production for integration tests
3. **Production**: Use `:jdbc` with PostgreSQL for reliability and scalability
4. **Docker**: Use environment variables for backend configuration to maintain single image
5. **Dependencies**: Only include backends you actually use to minimize JAR size

## Migration Between Backends

Export data from one backend and import to another:

```clojure
;; Export from file backend
(def file-conn (d/connect {:store {:backend :file :path "/tmp/datahike"}}))
(def datoms (d/datoms @file-conn :eavt))

;; Import to PostgreSQL backend
(def jdbc-config {:store {:backend :jdbc
                          :dbtype "postgresql"
                          :host "localhost"
                          :dbname "datahike"}})
(d/create-database jdbc-config)
(def jdbc-conn (d/connect jdbc-config))
(d/transact jdbc-conn (vec datoms))
```

## Further Reading

- [Architecture Decision Record for Dynamic Backend Loading](./adl/adr-005-dynamic-backend-loading.md)
- [Datahike Configuration Documentation](./config.md)
- [Konserve Storage Abstraction](https://github.com/replikativ/konserve)
- [Datahike-Server Docker Image](https://github.com/alekcz/datahike-server)