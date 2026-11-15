# `impulse-clojure` — Clojure / Scheme Lisp DSL

Functional Clojure wrappers for **Impulse Graph Engine** (`0.10.0-SNAPSHOT`) in namespace `org.impulsegraph.core`, including `with-arena`, `with-snapshot`, domain-bound traversals, `ImpulseBitSet` set operations, and query execution.

## Maven Dependency

```xml
<dependency>
    <groupId>org.impulsegraph</groupId>
    <artifactId>impulse-clojure</artifactId>
    <version>0.10.0-SNAPSHOT</version>
</dependency>
```

## Example

```clojure
(require '[org.impulsegraph.core :as ig])

(ig/with-snapshot [snap "hetionet.imps"]
  (-> (ig/domain snap "User")
      (ig/from-id 0)
      (ig/out "knows")
      (ig/to-list)))
```
