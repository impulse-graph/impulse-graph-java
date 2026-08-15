(ns org.impulsegraph.core
  (:import [org.impulsegraph.core.csr BinarySnapshotLoader GraphSnapshot DefaultImpulseQueryEvaluator]
           [org.impulsegraph.api ImpulseQueryBuilder ReturnType ArgType]
           [java.lang.foreign Arena]
           [java.nio.file Files]
           [java.io File]))

(defn open-snapshot
  "Opens an immutable off-heap zero-copy Impulse binary graph snapshot (.imps)."
  [^String file-path]
  (let [path (.toPath (File. file-path))
        bytes (Files/readAllBytes path)
        arena (Arena/ofShared)
        loaded (BinarySnapshotLoader/loadSnapshot bytes arena true)]
    (GraphSnapshot. arena (.relationSnapshots loaded))))

(defn close-snapshot
  "Closes the snapshot and releases off-heap memory mappings."
  [^GraphSnapshot snapshot]
  (.close snapshot))

(defrecord TraversalStep [relation-name direction filter-expr])

(defrecord Traversal [snapshot start-node steps params])

(defn traverse
  "Initializes a fluent multi-hop graph traversal starting at `start-node`."
  [snapshot start-node]
  (->Traversal snapshot (long start-node) [] {}))

(defn out
  "Appends a forward edge walk step over the specified relation name."
  [traversal ^String relation-name]
  (update traversal :steps conj (->TraversalStep relation-name :out nil)))

(defn in
  "Appends a reverse (CSC) edge walk step over the specified relation name."
  [traversal ^String relation-name]
  (update traversal :steps conj (->TraversalStep relation-name :in nil)))

(defn out-filtered
  "Appends a forward edge walk step with a CEL filter expression."
  [traversal ^String relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep relation-name :out filter-expr)))

(defn in-filtered
  "Appends a reverse edge walk step with a CEL filter expression."
  [traversal ^String relation-name ^String filter-expr]
  (update traversal :steps conj (->TraversalStep relation-name :in filter-expr)))

(defn with-param
  "Binds a named parameter for CEL filter expression evaluation."
  [traversal ^String name val]
  (let [clean (clojure.string/replace name #"^[\$@]" "")]
    (update traversal :params assoc clean (double val))))

(defn to-query
  "Compiles the traversal into an ImpulseGraphQuery."
  [traversal]
  (let [builder (ImpulseQueryBuilder.)]
    (.input builder "Seed" ArgType/SINGLE_NODE)
    (doseq [step (:steps traversal)]
      (if (= (:direction step) :out)
        (.walkEdge builder (:relation-name step))
        (.walkTarget builder (:relation-name step))))
    (.collect builder ReturnType/DENSE_BITSET)))

(defn to-set
  "Executes the traversal and returns the matching node IDs as a Clojure persistent set."
  [traversal]
  (let [query (to-query traversal)
        evaluator (DefaultImpulseQueryEvaluator/getInstance)
        res (.evaluate evaluator query (:snapshot traversal) (long (:start-node traversal)))]
    (set (map long res))))

(defn to-vec
  "Executes the traversal and returns matching node IDs as a Clojure vector."
  [traversal]
  (vec (to-set traversal)))

(defn count-nodes
  "Returns the number of matching candidate nodes."
  [traversal]
  (count (to-set traversal)))

(defmacro defquery
  "Defines a reusable S-Expression graph query function using Clojure threading."
  [name [snap-sym seed-sym] & steps]
  `(defn ~name [~snap-sym ~seed-sym]
     (-> (traverse ~snap-sym ~seed-sym)
         ~@steps)))
