(ns org.impulsegraph.core
  (:refer-clojure :exclude [in filter])
  (:import [org.impulsegraph.storage.csr BinarySnapshotLoader GraphSnapshot]
           [org.impulsegraph.api ImpulseGraphSnapshot ImpulseQueryBuilder ReturnType ArgType]
           [org.impulsegraph.api.bitset ImpulseBitSet]
           [org.impulsegraph.vm DefaultImpulseQueryEvaluator]
           [java.lang AutoCloseable]
           [java.lang.foreign Arena]
           [java.nio.file Path]
           [java.io File]))

;; ----------------------------------------------------------------------------
;; Protocols & Snapshot Wrapper
;; ----------------------------------------------------------------------------

(defprotocol IImpulseSnapshot
  "Protocol for inspecting graph snapshots."
  (edge-count [this relation-name] "Returns edge count for relation.")
  (relation-count [this] "Returns total relation count.")
  (relation-names [this] "Returns set of relation names."))

(extend-type ImpulseGraphSnapshot
  IImpulseSnapshot
  (edge-count [this rel]
    (.getEdgeCount this (if (keyword? rel) (name rel) (str rel))))
  (relation-count [this]
    (.getRelationCount this))
  (relation-names [this]
    (set (.getRelationNames this))))

(deftype SnapshotWrapper [^ImpulseGraphSnapshot raw-snapshot]
  clojure.lang.Counted
  (count [_]
    (.getRelationCount raw-snapshot))

  clojure.lang.ILookup
  (valAt [this key]
    (.valAt this key nil))
  (valAt [_ key not-found]
    (let [rel-name (if (keyword? key) (name key) (str key))]
      (if (.contains (.getRelationNames raw-snapshot) rel-name)
        (.getEdgeCount raw-snapshot rel-name)
        not-found)))

  clojure.lang.IDeref
  (deref [_]
    raw-snapshot)

  java.lang.AutoCloseable
  (close [_]
    (.close raw-snapshot))

  IImpulseSnapshot
  (edge-count [_ rel]
    (.getEdgeCount raw-snapshot (if (keyword? rel) (name rel) (str rel))))
  (relation-count [_]
    (.getRelationCount raw-snapshot))
  (relation-names [_]
    (set (.getRelationNames raw-snapshot))))

;; ----------------------------------------------------------------------------
;; Snapshot Lifecycle
;; ----------------------------------------------------------------------------

(defn unwrap-snapshot
  "Extracts the underlying ImpulseGraphSnapshot from a raw or wrapped instance."
  [snap]
  (cond
    (nil? snap) nil
    (instance? SnapshotWrapper snap) @snap
    (instance? ImpulseGraphSnapshot snap) snap
    :else snap))

(defn open-snapshot
  "Opens an immutable off-heap zero-copy Impulse binary graph snapshot (.imps)
  using memory-mapped I/O via Java FFM. Returns an idiomatic SnapshotWrapper
  supporting ILookup (:relation-name snap), Counted (count snap), and AutoCloseable."
  ([file-or-path]
   (open-snapshot file-or-path (Arena/ofShared)))
  ([file-or-path ^Arena arena]
   (let [^Path path (cond
                      (instance? Path file-or-path) file-or-path
                      (instance? File file-or-path) (.toPath ^File file-or-path)
                      (string? file-or-path) (Path/of file-or-path (into-array String []))
                      :else (throw (IllegalArgumentException.
                                     (str "Cannot resolve path from: " (type file-or-path)))))
         loaded (BinarySnapshotLoader/loadSnapshot path arena false false)]
     (->SnapshotWrapper (.graph loaded)))))

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
  `(let [arena# (Arena/ofShared)
         snap# (open-snapshot ~path-or-file arena#)]
     (try
       (let [~binding-form snap#]
         ~@body)
       (finally
         (close-snapshot snap#)))))

;; ----------------------------------------------------------------------------
;; Traversal Pipeline Data Structures
;; ----------------------------------------------------------------------------

(defrecord TraversalStep [relation-name direction filter-expr])

(defrecord Traversal [snapshot domain start-node steps params in-domain-filter in-domain-project])

(defn traverse
  "Initializes a fluent multi-hop graph traversal.
  Supports:
    (traverse snapshot seed-or-seeds) - binds to \"default\" domain
    (traverse snapshot domain seed-or-seeds) - binds to specified domain"
  ([snapshot seed-or-seeds]
   (traverse snapshot "default" seed-or-seeds))
  ([snapshot domain seed-or-seeds]
   (let [seeds (cond
                 (integer? seed-or-seeds) (long seed-or-seeds)
                 (instance? ImpulseBitSet seed-or-seeds) seed-or-seeds
                 (coll? seed-or-seeds) (vec (map long seed-or-seeds))
                 :else (long seed-or-seeds))]
     (->Traversal (unwrap-snapshot snapshot) (name domain) seeds [] {} nil nil))))

;; ----------------------------------------------------------------------------
;; Traversal Combinators
;; ----------------------------------------------------------------------------

(defn out
  "Appends a forward (CSR) edge walk step over the specified relation name."
  [traversal ^String relation-name]
  (update traversal :steps conj (->TraversalStep relation-name :out nil)))

(defn in
  "Appends a reverse (CSC) edge walk step over the specified relation name."
  [traversal ^String relation-name]
  (update traversal :steps conj (->TraversalStep relation-name :in nil)))

(defn both
  "Appends a bidirectional (both forward and reverse) edge walk step over the relation name."
  [traversal ^String relation-name]
  (update traversal :steps conj (->TraversalStep relation-name :both nil)))

(defn out-filtered
  "Appends a forward edge walk step with a CEL filter expression."
  [traversal ^String relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep relation-name :out filter-expr)))

(defn in-filtered
  "Appends a reverse (CSC) edge walk step with a CEL filter expression."
  [traversal ^String relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep relation-name :in filter-expr)))

(defn filter-nodes
  "Appends an in-domain CEL candidate filter expression to the traversal."
  [traversal ^String cel-expr]
  (assoc traversal :in-domain-filter cel-expr))

(defn project
  "Appends an in-domain CEL state projection expression to the traversal."
  [traversal ^String proj-expr]
  (assoc traversal :in-domain-project proj-expr))

(defn with-param
  "Binds a named parameter for CEL filter or projection evaluation."
  [traversal ^String name val]
  (let [clean (clojure.string/replace name #"^[\$@]" "")]
    (update traversal :params assoc clean (if (number? val) (double val) val))))

(defn with-params
  "Binds a map of named parameters for CEL evaluation."
  [traversal param-map]
  (reduce-kv (fn [t k v] (with-param t (name k) v)) traversal param-map))

;; ----------------------------------------------------------------------------
;; Compilation & S-Expression IR
;; ----------------------------------------------------------------------------

(defn to-query
  "Compiles the traversal into an ImpulseGraphQuery."
  [traversal]
  (let [builder (ImpulseQueryBuilder.)
        seed (:start-node traversal)
        arg-type (cond
                   (number? seed) ArgType/SINGLE_NODE
                   (instance? ImpulseBitSet seed) ArgType/DENSE_BITSET
                   (coll? seed) ArgType/NODE_ARRAY
                   :else ArgType/SINGLE_NODE)]
    (.input builder (or (:domain traversal) "default") arg-type)
    (when-let [params (:params traversal)]
      (when (seq params)
        (.bindParameters builder params)))
    (when-let [proj (:in-domain-project traversal)]
      (.projectState builder proj))
    (doseq [step (:steps traversal)]
      (let [rel (:relation-name step)
            filt (:filter-expr step)]
        (case (:direction step)
          :out (if filt
                 (.walkEdgeWithCel builder rel filt)
                 (.walkEdge builder rel))
          :in  (if filt
                 (.walkReverseWithCel builder rel filt)
                 (.walkReverse builder rel))
          :both (.walkEdge builder rel))))
    (when-let [filt (:in-domain-filter traversal)]
      (.filterWithCel builder filt))
    (.collect builder ReturnType/DENSE_BITSET)))

(defn to-ast
  "Returns the root ImpScheme AST (ScmProgram) of the compiled query."
  [traversal]
  (.getAst (to-query traversal)))

(defn to-scm-string
  "Returns the human-readable ImpScheme S-Expression AST code of the traversal."
  [traversal]
  (.toScmString (to-ast traversal)))

;; ----------------------------------------------------------------------------
;; Materialization & Execution
;; ----------------------------------------------------------------------------

(declare to-bitset)

(defn- execute-single-query
  ^ImpulseBitSet [traversal]
  (let [query (to-query traversal)
        evaluator (DefaultImpulseQueryEvaluator/getInstance)
        seed (:start-node traversal)
        snap (unwrap-snapshot (:snapshot traversal))
        input-arg (cond
                    (number? seed) (long seed)
                    (instance? ImpulseBitSet seed) seed
                    (vector? seed) (long-array seed)
                    (coll? seed) (long-array (vec seed))
                    :else seed)]
    (.evaluate evaluator query snap input-arg)))

(defn to-bitset
  "Executes the traversal and returns the resulting off-heap ImpulseBitSet."
  ^ImpulseBitSet [traversal]
  (let [steps (:steps traversal)
        has-both? (some #(= (:direction %) :both) steps)]
    (if-not has-both?
      (execute-single-query traversal)
      ;; For steps with :both, expand into union of forward and reverse branch traversals
      (let [idx (.indexOf (mapv :direction steps) :both)
            step (nth steps idx)
            rel (:relation-name step)
            prefix (subvec (vec steps) 0 idx)
            suffix (subvec (vec steps) (inc idx))
            t-prefix (assoc traversal :steps prefix)
            frontier (if (empty? prefix)
                       (:start-node traversal)
                       (to-bitset t-prefix))
            trav-out (assoc traversal :start-node frontier :steps (into [(->TraversalStep rel :out nil)] suffix))
            trav-in  (assoc traversal :start-node frontier :steps (into [(->TraversalStep rel :in nil)] suffix))
            bs-out (to-bitset trav-out)
            bs-in  (to-bitset trav-in)]
        (.or bs-out bs-in)
        bs-out))))

(defn to-lazy-seq
  "Executes the traversal and returns a lazy sequence of matching node IDs."
  [traversal]
  (seq (to-bitset traversal)))

(defn to-set
  "Executes the traversal and returns matching node IDs as a Clojure persistent set."
  [traversal]
  (set (to-lazy-seq traversal)))

(defn to-vec
  "Executes the traversal and returns matching node IDs as a Clojure persistent vector."
  [traversal]
  (vec (to-lazy-seq traversal)))

(defn count-nodes
  "Returns the cardinality of matching candidate nodes."
  [traversal]
  (.cardinality (to-bitset traversal)))

;; ----------------------------------------------------------------------------
;; Query Definition Macro
;; ----------------------------------------------------------------------------

(defmacro defquery
  "Defines a reusable S-Expression graph query function using Clojure threading."
  [name [snap-sym seed-sym] & steps]
  `(defn ~name [~snap-sym ~seed-sym]
     (-> (traverse ~snap-sym ~seed-sym)
         ~@steps)))
