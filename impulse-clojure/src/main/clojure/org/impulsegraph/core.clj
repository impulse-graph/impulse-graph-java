(ns org.impulsegraph.core
  (:refer-clojure :exclude [in])
  (:require [clojure.string :as str])
  (:import [org.impulsegraph.api Decimal64 ImpulseGraphSnapshot ImpulseGraphQuery ImpulseQueryBuilder ReturnType ArgType]
           [org.impulsegraph.api.bitset ImpulseBitSet]
           [org.impulsegraph.api.spi ImpulseEngineRegistry]
           [org.impulsegraph.api.statement ImpulseStatement]
           [org.impulsegraph.api.traversal DomainView]
           [org.impulsegraph.compiler.ast ImpScmNode]
           [java.io File]
           [java.lang AutoCloseable]
           [java.lang.foreign Arena ValueLayout]
           [java.math BigDecimal]
           [java.nio ByteOrder]
           [java.nio.file Path]
           [java.util Collection Map]
           [java.util.function Function]))

;; ----------------------------------------------------------------------------
;; Protocols & Snapshot Wrapper
;; ----------------------------------------------------------------------------

(defprotocol IImpulseSnapshot
  "Protocol for inspecting and managing graph snapshots."
  (edge-count [this relation-name] "Returns edge count for relation.")
  (relation-count [this] "Returns total relation count.")
  (relation-names [this] "Returns set of relation names.")
  (domain-names [this] "Returns set of domain names.")
  (node-count [this domain-name] "Returns node count for domain.")
  (metadata [this] "Returns snapshot metadata as a persistent map.")
  (off-heap-bytes [this] "Returns off-heap memory usage in bytes.")
  (drain-and-close [this] "Drains in-flight queries and closes the snapshot."))

(defn- key->str
  [k]
  (cond
    (nil? k) nil
    (keyword? k) (if-let [kns (namespace k)]
                   (str kns "/" (name k))
                   (name k))
    :else (str k)))

(extend-type ImpulseGraphSnapshot
  IImpulseSnapshot
  (edge-count [this rel]
    (.getEdgeCount this (key->str rel)))
  (relation-count [this]
    (.getRelationCount this))
  (relation-names [this]
    (set (.getRelationNames this)))
  (domain-names [this]
    (set (.getDomainNames this)))
  (node-count [this domain-name]
    (.getNodeCount this (key->str domain-name)))
  (metadata [this]
    (into {} (.getMetadataMap this)))
  (off-heap-bytes [this]
    (.getOffHeapMemorySizeBytes this))
  (drain-and-close [this]
    (.drainAndClose this)))

(deftype SnapshotWrapper [^ImpulseGraphSnapshot raw-snapshot]
  clojure.lang.Counted
  (count [_]
    (.getRelationCount raw-snapshot))

  clojure.lang.ILookup
  (valAt [this key]
    (.valAt this key nil))
  (valAt [_ key not-found]
    (if (nil? key)
      not-found
      (let [rel-name (key->str key)]
        (if (.contains (.getRelationNames raw-snapshot) rel-name)
          (.getEdgeCount raw-snapshot rel-name)
          not-found))))

  clojure.lang.IDeref
  (deref [_]
    raw-snapshot)

  java.lang.AutoCloseable
  (close [_]
    (.close raw-snapshot))

  IImpulseSnapshot
  (edge-count [_ rel]
    (.getEdgeCount raw-snapshot (key->str rel)))
  (relation-count [_]
    (.getRelationCount raw-snapshot))
  (relation-names [_]
    (set (.getRelationNames raw-snapshot)))
  (domain-names [_]
    (set (.getDomainNames raw-snapshot)))
  (node-count [_ domain-name]
    (.getNodeCount raw-snapshot (key->str domain-name)))
  (metadata [_]
    (into {} (.getMetadataMap raw-snapshot)))
  (off-heap-bytes [_]
    (.getOffHeapMemorySizeBytes raw-snapshot))
  (drain-and-close [_]
    (.drainAndClose raw-snapshot)))

;; ----------------------------------------------------------------------------
;; Snapshot Lifecycle
;; ----------------------------------------------------------------------------

(defn unwrap-snapshot
  "Extracts the underlying ImpulseGraphSnapshot from a raw or wrapped instance."
  ^ImpulseGraphSnapshot [snap]
  (cond
    (nil? snap) nil
    (instance? SnapshotWrapper snap) @snap
    (instance? ImpulseGraphSnapshot snap) snap
    :else snap))

(defn- resolve-path
  ^Path [file-or-path]
  (cond
    (instance? Path file-or-path) file-or-path
    (instance? File file-or-path) (.toPath ^File file-or-path)
    (string? file-or-path) (Path/of ^String file-or-path (into-array String []))
    :else (throw (IllegalArgumentException.
                   (str "Cannot resolve path from: " (type file-or-path))))))

(defn open-snapshot
  "Opens an immutable off-heap zero-copy Impulse binary graph snapshot (.imps)
  using memory-mapped I/O via Java FFM. Returns an idiomatic SnapshotWrapper
  supporting ILookup (:relation-name snap), Counted (count snap), and AutoCloseable."
  ([file-or-path]
   (let [^Arena arena (Arena/ofShared)]
     (try
       (open-snapshot file-or-path arena)
       (catch Throwable t
         (.close arena)
         (throw t)))))
  ([file-or-path ^Arena arena]
   (let [^Path path (resolve-path file-or-path)
         ^ImpulseGraphSnapshot loaded (.loadSnapshot (ImpulseEngineRegistry/getProvider) path arena)]
     (->SnapshotWrapper loaded))))

(defn close-snapshot
  "Closes the snapshot and releases off-heap memory mappings."
  [snapshot]
  (if (instance? AutoCloseable snapshot)
    (.close ^AutoCloseable snapshot)
    (when-let [raw (unwrap-snapshot snapshot)]
      (.close ^AutoCloseable raw))))

(defmacro with-snapshot
  "Executes body with snapshot bound to `binding-form`, guaranteeing off-heap
  memory-mapping deallocation on exit."
  [[binding-form path-or-file] & body]
  `(with-open [~binding-form (open-snapshot ~path-or-file)]
     ~@body))

;; ----------------------------------------------------------------------------
;; Traversal Pipeline Data Structures
;; ----------------------------------------------------------------------------

(defrecord TraversalStep [op relation-name filter-expr args])

(defrecord Traversal [snapshot domain start-node steps params])

(defn traverse
  "Initializes a fluent multi-hop graph traversal bound to an explicit domain."
  [snapshot domain seed-or-seeds]
  (when (nil? domain)
    (throw (IllegalArgumentException. "Domain must not be nil")))
  (when (nil? seed-or-seeds)
    (throw (IllegalArgumentException. "Seed or seeds must not be nil")))
  (let [seeds (cond
                (integer? seed-or-seeds) (long seed-or-seeds)
                (instance? ImpulseBitSet seed-or-seeds) seed-or-seeds
                (coll? seed-or-seeds) (mapv long seed-or-seeds)
                :else (throw (IllegalArgumentException.
                               (str "Unsupported seed type: " (type seed-or-seeds)))))]
    (->Traversal (unwrap-snapshot snapshot) (key->str domain) seeds [] {})))

;; ----------------------------------------------------------------------------
;; Traversal Combinators (Ordered Pipeline Steps)
;; ----------------------------------------------------------------------------

(defn out
  "Appends a forward (CSR) edge walk step over the specified relation name."
  [traversal relation-name]
  (update traversal :steps conj (->TraversalStep :out (key->str relation-name) nil nil)))

(defn in
  "Appends a reverse (CSC) edge walk step over the specified relation name."
  [traversal relation-name]
  (update traversal :steps conj (->TraversalStep :in (key->str relation-name) nil nil)))

(defn both
  "Appends a bidirectional (forward and reverse) edge walk step over the relation name."
  [traversal relation-name]
  (update traversal :steps conj (->TraversalStep :both (key->str relation-name) nil nil)))

(defn out-filtered
  "Appends a forward edge walk step with a CEL filter expression."
  [traversal relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep :out (key->str relation-name) filter-expr nil)))

(defn in-filtered
  "Appends a reverse (CSC) edge walk step with a CEL filter expression."
  [traversal relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep :in (key->str relation-name) filter-expr nil)))

(defn out-union
  "Appends a union forward walk over multiple relation names."
  [traversal relation-names]
  (update traversal :steps conj (->TraversalStep :out-union nil nil (mapv key->str relation-names))))

(defn in-union
  "Appends a union reverse walk over multiple relation names."
  [traversal relation-names]
  (update traversal :steps conj (->TraversalStep :in-union nil nil (mapv key->str relation-names))))

(defn both-union
  "Appends a union bidirectional walk over multiple relation names."
  [traversal relation-names]
  (update traversal :steps conj (->TraversalStep :both-union nil nil (mapv key->str relation-names))))

(defn filter-nodes
  "Appends an in-domain CEL candidate filter expression at the current pipeline position."
  [traversal ^String cel-expr]
  (update traversal :steps conj (->TraversalStep :filter nil cel-expr nil)))

(defn project
  "Appends an in-domain CEL state projection expression at the current pipeline position."
  [traversal ^String proj-expr]
  (update traversal :steps conj (->TraversalStep :project nil proj-expr nil)))

(defn walk-state
  "Appends a forward edge walk step with a state projection expression."
  [traversal relation-name ^String state-proj]
  (update traversal :steps conj (->TraversalStep :walk-state (key->str relation-name) state-proj nil)))

(defn repeat-steps
  "Repeats the steps produced by `step-fn` `n` times."
  [traversal n step-fn]
  (let [sub (step-fn (assoc traversal :steps []))]
    (update traversal :steps conj (->TraversalStep :repeat nil nil {:count (int n) :steps (:steps sub)}))))

(defn repeat-until-stable
  "Repeats the steps produced by `step-fn` until fixed-point convergence."
  [traversal step-fn]
  (let [sub (step-fn (assoc traversal :steps []))]
    (update traversal :steps conj (->TraversalStep :repeat-until-stable nil nil {:steps (:steps sub)}))))

(defn vector-rerank
  "Appends a vector rerank step over `attr-id` with `metric` and `query-vec`."
  [traversal query-vec attr-id metric]
  (update traversal :steps conj (->TraversalStep :rerank nil nil {:vec (float-array query-vec)
                                                                  :attr (int attr-id)
                                                                  :metric (int metric)})))

(defn with-param
  "Binds a named parameter for CEL filter or projection evaluation, preserving exact Decimal64, BigDecimal, integer, and floating-point types."
  [traversal param-name val]
  (let [clean (str/replace (key->str param-name) #"^[\$@]" "")
        coerced (cond
                  (instance? Decimal64 val) val
                  (instance? BigDecimal val) val
                  (or (instance? Long val) (instance? Integer val) (instance? Short val) (instance? Byte val)) (long val)
                  (or (instance? Double val) (instance? Float val)) (double val)
                  (number? val) (double val)
                  :else val)]
    (update traversal :params assoc clean coerced)))

(defn with-params
  "Binds a map of named parameters for CEL evaluation."
  [traversal param-map]
  (reduce-kv (fn [t k v] (with-param t (key->str k) v)) traversal param-map))

;; ----------------------------------------------------------------------------
;; Compilation & S-Expression IR
;; ----------------------------------------------------------------------------

(defn- apply-steps!
  ^ImpulseQueryBuilder [^ImpulseQueryBuilder builder steps]
  (doseq [step steps]
    (let [rel (:relation-name step)
          filt (:filter-expr step)
          args (:args step)]
      (case (:op step)
        :out (if filt
               (.walkEdgeWithCel builder ^String rel ^String filt)
               (.walkEdge builder ^String rel))
        :in (if filt
              (.walkReverseWithCel builder ^String rel ^String filt)
              (.walkReverse builder ^String rel))
        :both (do
                (.walkBoth builder ^String rel)
                (when filt
                  (.filterWithCel builder ^String filt)))
        :out-union (.walkOutUnion builder ^"[Ljava.lang.String;" (into-array String args))
        :in-union (.walkInUnion builder ^"[Ljava.lang.String;" (into-array String args))
        :both-union (.walkBothUnion builder ^"[Ljava.lang.String;" (into-array String args))
        :filter (.filterWithCel builder ^String filt)
        :project (.projectState builder ^String filt)
        :walk-state (.walkEdgeWithState builder ^String rel ^String filt)
        :repeat (.repeat builder
                         (int (:count args))
                         (reify Function
                           (apply [_ b]
                             (apply-steps! ^ImpulseQueryBuilder b (:steps args)))))
        :repeat-until-stable (.repeatUntilStable builder
                                                 (reify Function
                                                   (apply [_ b]
                                                     (apply-steps! ^ImpulseQueryBuilder b (:steps args)))))
        :rerank (.vectorRerank builder ^floats (:vec args) (int (:attr args)) (int (:metric args))))))
  builder)

(defn- build-query-builder
  ^ImpulseQueryBuilder [traversal]
  (let [builder (ImpulseQueryBuilder.)
        seed (:start-node traversal)
        arg-type (cond
                   (number? seed) ArgType/SINGLE_NODE
                   (instance? ImpulseBitSet seed) ArgType/DENSE_BITSET
                   (coll? seed) ArgType/NODE_ARRAY
                   :else ArgType/SINGLE_NODE)]
    (.input builder ^String (:domain traversal) arg-type)
    (when-let [params (:params traversal)]
      (when (seq params)
        (.bindParameters builder ^Map params)))
    (apply-steps! builder (:steps traversal))))

(defn to-query
  "Compiles the traversal into an ImpulseGraphQuery returning a dense bitset."
  ^ImpulseGraphQuery [traversal]
  (.collect (build-query-builder traversal) ReturnType/DENSE_BITSET))

(defn to-ast
  "Returns the root ImpScheme AST (ImpScmNode) of the compiled query."
  ^ImpScmNode [traversal]
  (.getAst (to-query traversal)))

(defn to-scm-string
  "Returns the human-readable ImpScheme S-Expression AST code of the traversal."
  ^String [traversal]
  (.toScmString (to-ast traversal)))

;; ----------------------------------------------------------------------------
;; Materialization & Execution
;; ----------------------------------------------------------------------------

(defn- execute-query
  [traversal ^ImpulseGraphQuery query]
  (let [seed (:start-node traversal)
        ^ImpulseGraphSnapshot snap (unwrap-snapshot (:snapshot traversal))
        input-arg (cond
                    (number? seed) (long seed)
                    (instance? ImpulseBitSet seed) seed
                    (vector? seed) (long-array seed)
                    (coll? seed) (long-array (vec seed))
                    :else seed)]
    (.execute query snap input-arg)))

(defn to-bitset
  "Executes the traversal and returns the resulting off-heap ImpulseBitSet."
  ^ImpulseBitSet [traversal]
  (execute-query traversal (to-query traversal)))

(defn to-set
  "Eagerly executes the traversal and returns matching node IDs as a persistent set."
  [traversal]
  (let [bs (to-bitset traversal)]
    (persistent! (reduce conj! (transient #{}) (seq bs)))))

(defn to-vec
  "Eagerly executes the traversal and returns matching node IDs as a persistent vector."
  [traversal]
  (let [bs (to-bitset traversal)]
    (vec (seq bs))))

(defn count-nodes
  "Returns the cardinality of matching candidate nodes."
  ^long [traversal]
  (.cardinality (to-bitset traversal)))

(defn reduce-sum
  "Executes the traversal and returns the sum reduction of projected values."
  [traversal]
  (execute-query traversal (.reduceSum (build-query-builder traversal))))

(defn reduce-min
  "Executes the traversal and returns the min reduction of projected values."
  [traversal]
  (execute-query traversal (.reduceMin (build-query-builder traversal))))

(defn reduce-max
  "Executes the traversal and returns the max reduction of projected values."
  [traversal]
  (execute-query traversal (.reduceMax (build-query-builder traversal))))

(defn reduce-avg
  "Executes the traversal and returns the average reduction of projected values."
  [traversal]
  (execute-query traversal (.reduceAvg (build-query-builder traversal))))

(defn reduce-first
  "Executes the traversal and returns the first matching projected value."
  [traversal]
  (execute-query traversal (.reduceFirst (build-query-builder traversal))))

(defn reduce-arg-max
  "Executes the traversal and returns the node ID with the maximum projected value."
  [traversal]
  (execute-query traversal (.reduceArgMax (build-query-builder traversal))))

(defn reduce-arg-min
  "Executes the traversal and returns the node ID with the minimum projected value."
  [traversal]
  (execute-query traversal (.reduceArgMin (build-query-builder traversal))))

;; ----------------------------------------------------------------------------
;; Kleisli DomainView & Prepared Statement Interop
;; ----------------------------------------------------------------------------

(defn domain
  "Returns a Kleisli DomainView entry point for the named domain."
  ^DomainView [snapshot domain-name]
  (.domain (unwrap-snapshot snapshot) (key->str domain-name)))

(defn prepare
  "Prepares an ImpulseStatement (e.g. Cypher, ImpLog, ImpK, ImpScheme) on the snapshot."
  ^ImpulseStatement [snapshot ^String query-str]
  (.prepare (unwrap-snapshot snapshot) query-str))

(defn bind-params
  "Binds a Clojure map of parameters to an ImpulseStatement."
  ^ImpulseStatement [^ImpulseStatement stmt param-map]
  (reduce-kv
    (fn [^ImpulseStatement s k v]
      (let [pname (key->str k)]
        (cond
          (instance? Decimal64 v) (.bindLong s pname (.unscaled ^Decimal64 v))
          (or (instance? Long v) (instance? Integer v)) (.bindLong s pname (long v))
          (or (instance? Double v) (instance? Float v)) (.bindDouble s pname (double v))
          (instance? Boolean v) (.bindBoolean s pname (boolean v))
          (string? v) (.bindString s pname ^String v)
          (coll? v) (.bindNodes s pname ^Collection (mapv long v))
          :else (.bind s pname v))))
    stmt
    param-map))

;; ----------------------------------------------------------------------------
;; DECIMAL64 Fixed-Point Ergonomics
;; ----------------------------------------------------------------------------

(def ^:private le-long-layout
  (.withOrder ValueLayout/JAVA_LONG_UNALIGNED ByteOrder/LITTLE_ENDIAN))

(defn decimal64?
  "Returns true if `x` is an instance of `org.impulsegraph.api.Decimal64`."
  [x]
  (instance? Decimal64 x))

(defn dec64
  "Constructs a `Decimal64` from a string, BigDecimal, integer, or existing `Decimal64`.
  1-arity infers minimal exact `(precision, scale)` (integers default to DECIMAL(18, 0)).
  3-arity parses/scales explicitly to `DECIMAL(precision, scale)`."
  (^Decimal64 [value]
   (cond
     (instance? Decimal64 value) value
     (string? value) (let [ps (Decimal64/inferPrecisionScale ^String value)]
                       (Decimal64/parse ^String value (aget ps 0) (aget ps 1)))
     (instance? BigDecimal value) (let [plain (.toPlainString ^BigDecimal value)
                                        ps (Decimal64/inferPrecisionScale plain)]
                                    (Decimal64/fromBigDecimal ^BigDecimal value (aget ps 0) (aget ps 1)))
     (integer? value) (Decimal64. (long value) 18 0)
     :else (throw (IllegalArgumentException.
                    (str "Cannot convert to Decimal64: " (type value))))))
  (^Decimal64 [value precision scale]
   (let [p (int precision)
         s (int scale)]
     (Decimal64/validatePrecisionScale p s)
     (cond
       (instance? Decimal64 value) (Decimal64/fromBigDecimal (.toBigDecimal ^Decimal64 value) p s)
       (string? value) (Decimal64/parse ^String value p s)
       (instance? BigDecimal value) (Decimal64/fromBigDecimal ^BigDecimal value p s)
       (integer? value) (Decimal64. (Math/multiplyExact (long value) (aget Decimal64/POW10 s)) p s)
       :else (throw (IllegalArgumentException.
                      (str "Cannot convert to Decimal64: " (type value))))))))

(defn ->decimal64
  "Alias for `dec64`, converting a string, BigDecimal, integer, or Decimal64 into `Decimal64`."
  (^Decimal64 [value]
   (dec64 value))
  (^Decimal64 [value precision scale]
   (dec64 value precision scale)))

(defn unscaled->decimal64
  "Wraps an already-unscaled 64-bit integer as a `Decimal64` with explicit `(precision, scale)`."
  ^Decimal64 [unscaled precision scale]
  (Decimal64. (long unscaled) (int precision) (int scale)))

(defn ->bigdec
  "Converts a `Decimal64` (or existing `BigDecimal`) into an exact `java.math.BigDecimal`."
  ^BigDecimal [value]
  (cond
    (instance? Decimal64 value) (.toBigDecimal ^Decimal64 value)
    (instance? BigDecimal value) value
    :else (throw (IllegalArgumentException.
                   (str "Cannot convert to BigDecimal: " (type value))))))

(defn decimal64->str
  "Formats a `Decimal64` as an exact base-10 string with zero floating-point drift."
  ^String [^Decimal64 value]
  (.toDecimalString value))

(defn filter-decimal
  "Appends an in-domain CEL candidate filter comparing a `DECIMAL64` node attribute against an exact `Decimal64`."
  [traversal attr-name op ^Decimal64 value]
  (filter-nodes traversal (str "node." (key->str attr-name) " " (key->str op) " " (.toDecimalString value))))

(defn domain-decimal64
  "Reads a non-nullable `Decimal64` domain attribute directly from the snapshot's off-heap attribute segment."
  ^Decimal64 [snapshot domain-name attr-name node-id precision scale]
  (let [raw (unwrap-snapshot snapshot)
        seg (.getDomainAttributeSegment raw (key->str domain-name) (key->str attr-name))
        idx (long node-id)]
    (when (or (= 0 (.address seg)) (< (.byteSize seg) (* (inc idx) 8)))
      (throw (IllegalArgumentException.
               (str "Domain '" (key->str domain-name) "' attribute '" (key->str attr-name)
                    "' segment unavailable for node " idx))))
    (Decimal64. (.get seg le-long-layout (* idx 8)) (int precision) (int scale))))

(defmacro with-statement
  "Executes body with a prepared ImpulseStatement bound to `binding-form`, closing it on exit."
  [[binding-form snapshot query-str] & body]
  `(with-open [~binding-form (prepare ~snapshot ~query-str)]
     ~@body))

;; ----------------------------------------------------------------------------
;; Query Definition Macro
;; ----------------------------------------------------------------------------

(defmacro defquery
  "Defines a reusable S-Expression graph query function with explicit domain."
  [name [snap-sym domain-sym seed-sym] & steps]
  `(defn ~name [~snap-sym ~domain-sym ~seed-sym]
     (-> (traverse ~snap-sym ~domain-sym ~seed-sym)
         ~@steps)))
