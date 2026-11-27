(ns org.impulsegraph.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.impulsegraph.core :as imp])
  (:import [org.impulsegraph.api Decimal64]
           [org.impulsegraph.api.bitset OffHeapBitSet]
           [org.impulsegraph.storage.csr BinarySnapshotLoader BinarySnapshotLoader$DefaultLoadedSnapshot
            DefaultSnapshotBuilder GraphSnapshot RelationSnapshot]
           [java.lang.foreign Arena ValueLayout]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files OpenOption Path]))

(defn- create-synthetic-graph
  "Creates a multi-domain graph in off-heap memory conforming to Rule 3.14:
   Domains:
     User: 4 nodes
     Group: 4 nodes
     Role: 4 nodes
   Relations:
     follows (User -> User): 0 -> 1 -> 2 -> 3 (homogeneous chaining)
     userToGroup (User -> Group): 0 -> 1, 1 -> 2
     groupToRole (Group -> Role): 1 -> 2, 2 -> 3"
  [arena]
  (let [alloc-ints (fn [xs]
                     (let [n (count xs)
                           seg (.allocate ^Arena arena
                                          (* n (.byteSize ValueLayout/JAVA_INT))
                                          (.byteAlignment ValueLayout/JAVA_INT))]
                       (dotimes [i n]
                         (.setAtIndex seg ValueLayout/JAVA_INT (long i) (int (nth xs i))))
                       seg))
        ;; follows: 0 -> 1, 1 -> 2, 2 -> 3
        f-row-offsets (alloc-ints [0 1 2 3 3])
        f-col-targets (alloc-ints [1 2 3])
        f-csc-offsets (alloc-ints [0 0 1 2 3])
        f-csc-targets (alloc-ints [0 1 2])
        rel-follows (RelationSnapshot. arena 4 3 f-row-offsets f-col-targets f-csc-offsets f-csc-targets)

        ;; userToGroup: 0 -> 1, 1 -> 2
        u2g-offsets (alloc-ints [0 1 2 2 2])
        u2g-targets (alloc-ints [1 2])
        u2g-csc-offsets (alloc-ints [0 0 1 2 2])
        u2g-csc-targets (alloc-ints [0 1])
        rel-u2g (RelationSnapshot. arena 4 2 u2g-offsets u2g-targets u2g-csc-offsets u2g-csc-targets)

        ;; groupToRole: 1 -> 2, 2 -> 3
        g2r-offsets (alloc-ints [0 0 1 2 2])
        g2r-targets (alloc-ints [2 3])
        g2r-csc-offsets (alloc-ints [0 0 0 1 2])
        g2r-csc-targets (alloc-ints [1 2])
        rel-g2r (RelationSnapshot. arena 4 2 g2r-offsets g2r-targets g2r-csc-offsets g2r-csc-targets)

        meta {"domain.User.nodeCount" "4"
              "domain.Group.nodeCount" "4"
              "domain.Role.nodeCount" "4"
              "domain.0.name" "User"
              "domain.1.name" "Group"
              "domain.2.name" "Role"}
        raw-snap (GraphSnapshot. arena
                                 {"follows" rel-follows
                                  "userToGroup" rel-u2g
                                  "groupToRole" rel-g2r}
                                 meta)]
    raw-snap))

(deftest test-traversal-dsl-construction
  (testing "Clojure S-expression threading traversal construction with explicit domain and typed params"
    (let [trav (-> (imp/traverse nil :User 14726)
                   (imp/out "DaG")
                   (imp/out "GpPW")
                   (imp/in "GpPW")
                   (imp/in-filtered "CbG" "edge.affinity <= $maxAffinity")
                   (imp/with-param "maxAffinity" 50.0)
                   (imp/with-param "$threshold" 9007199254740993))]
      (is (= "User" (:domain trav)))
      (is (= 14726 (:start-node trav)))
      (is (= 4 (count (:steps trav))))
      (is (= 50.0 (get-in trav [:params "maxAffinity"])))
      (is (instance? Double (get-in trav [:params "maxAffinity"])))
      (is (= 9007199254740993 (get-in trav [:params "threshold"])))
      (is (instance? Long (get-in trav [:params "threshold"])))
      (is (= :out (:op (first (:steps trav)))))
      (is (= :in (:op (last (:steps trav)))))))
  (testing "Explicit domain and non-nil seed validation"
    (is (thrown? IllegalArgumentException (imp/traverse nil nil 1)))
    (is (thrown? IllegalArgumentException (imp/traverse nil :User nil)))))

(deftest test-defquery-macro
  (testing "defquery macro expansion with explicit domain"
    (imp/defquery drug-repurposing [snap domain seed]
      (imp/out "DaG")
      (imp/out "GpPW")
      (imp/in "GpPW")
      (imp/in "CbG"))

    (let [q (drug-repurposing nil :Compound 14726)]
      (is (= "Compound" (:domain q)))
      (is (= 4 (count (:steps q))))
      (is (= "DaG" (:relation-name (first (:steps q)))))
      (is (= "CbG" (:relation-name (last (:steps q))))))))

(deftest test-ordered-pipeline-and-both-ast
  (testing "filter-nodes, project, and both preserve order and compile accurately"
    (let [trav (-> (imp/traverse nil :User 0)
                   (imp/filter-nodes "node.age > 18")
                   (imp/out "userToGroup")
                   (imp/project "node.score = 1.0")
                   (imp/filter-nodes "node.active == true")
                   (imp/both "follows"))
          ast (imp/to-ast trav)
          scm (imp/to-scm-string trav)]
      (is (some? ast))
      (is (string? scm))
      (is (= 5 (count (:steps trav))))
      (is (.contains scm "csr-walk"))
      (is (.contains scm "userToGroup"))
      (is (.contains scm "node.age > 18"))
      (is (.contains scm "node.active == true"))
      (is (.contains scm "follows")))))

(deftest test-snapshot-lifecycle-and-error-cleanup
  (testing "open-snapshot fails cleanly on missing file without leaking Arena"
    (let [missing (Files/createTempFile "missing-snap" ".imps" (make-array java.nio.file.attribute.FileAttribute 0))]
      (Files/deleteIfExists missing)
      (is (thrown? Exception (imp/open-snapshot missing))))
    (is (thrown? IllegalArgumentException (imp/open-snapshot 12345))))
  (testing "with-snapshot opens and closes without double-closing Arena"
    (let [tmp (Files/createTempFile "clojure-snap-test" ".imps" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [write-arena (Arena/ofConfined)]
          (let [snap (create-synthetic-graph write-arena)]
            (Files/write tmp (DefaultSnapshotBuilder/writeSnapshotBytes snap) (make-array OpenOption 0))))
        (imp/with-snapshot [snap tmp]
          (is (= 3 (count snap)))
          (is (= #{1} (-> (imp/traverse snap :User 0)
                          (imp/out "follows")
                          imp/to-set)))
          (is (= [2] (-> (imp/traverse snap :User 0)
                         (imp/out "userToGroup")
                         (imp/out "groupToRole")
                         imp/to-vec)))
          (is (contains? (imp/domain-names snap) "User"))
          (is (= 4 (.getMaxNodeCount @snap)))
          (is (map? (imp/metadata snap)))
          (is (pos? (imp/off-heap-bytes snap))))
        (finally
          (Files/deleteIfExists tmp))))))

(deftest test-bitset-clojure-idioms
  (testing "ImpulseBitSet as Clojure sequence, set, vector and count"
    (with-open [arena (Arena/ofConfined)]
      (let [bs (OffHeapBitSet. arena 100)]
        (.set bs 1)
        (.set bs 42)
        (.set bs 99)
        (is (= 3 (count (seq bs))))
        (is (= [1 42 99] (vec (seq bs))))
        (is (= #{1 42 99} (set (seq bs))))
        (is (= 3 (.cardinality bs)))))))

(deftest test-snapshot-wrapper-idioms
  (testing "SnapshotWrapper ILookup, Counted, nil/namespaced keys, and IImpulseSnapshot protocol"
    (with-open [arena (Arena/ofConfined)]
      (let [raw (create-synthetic-graph arena)
            wrapped (imp/->SnapshotWrapper raw)]
        (is (= 3 (count wrapped)))
        (is (= 3 (:follows wrapped)))
        (is (= 2 (:userToGroup wrapped)))
        (is (= 2 (:groupToRole wrapped)))
        (is (= 3 (get wrapped "follows")))
        (is (nil? (:nonExistent wrapped)))
        (is (nil? (get wrapped nil)))
        (is (= :fallback (get wrapped :ns/nonExistent :fallback)))
        (is (= 3 (imp/edge-count wrapped :follows)))
        (is (= 2 (imp/edge-count wrapped :userToGroup)))
        (is (= 3 (imp/relation-count wrapped)))
        (is (contains? (imp/relation-names wrapped) "follows"))
        (is (contains? (imp/relation-names wrapped) "userToGroup"))
        (is (contains? (imp/relation-names wrapped) "groupToRole"))))))

(deftest test-synthetic-graph-query-execution
  (testing "Homogeneous follows, heterogeneous userToGroup->groupToRole, reverse walk, both, repeat, Kleisli domain, count-nodes"
    (with-open [arena (Arena/ofConfined)]
      (let [raw (create-synthetic-graph arena)
            wrapped (imp/->SnapshotWrapper raw)]
        ;; 1-hop: 0 -> 1 via follows
        (is (= #{1} (-> (imp/traverse wrapped :User 0)
                        (imp/out "follows")
                        imp/to-set)))

        ;; Homogeneous 2-hop: (User 0) -> (User 1) -> (User 2) via follows
        (is (= [2] (-> (imp/traverse wrapped :User 0)
                       (imp/out "follows")
                       (imp/out "follows")
                       imp/to-vec)))

        ;; Repeat 2-hop: (User 0) -> (User 2)
        (is (= #{2} (-> (imp/traverse wrapped :User 0)
                        (imp/repeat-steps 2 #(imp/out % "follows"))
                        imp/to-set)))

        ;; Heterogeneous 2-hop: (User 0) -> (Group 1) -> (Role 2) via userToGroup then groupToRole
        (is (= [2] (-> (imp/traverse wrapped :User 0)
                       (imp/out "userToGroup")
                       (imp/out "groupToRole")
                       imp/to-vec)))

        ;; Reverse walk: 2 <- 1 via follows
        (is (= #{1} (-> (imp/traverse wrapped :User 2)
                        (imp/in "follows")
                        imp/to-set)))

        ;; Both forward and reverse via WalkBoth: 1 -> {0, 2} via follows
        (is (= #{0 2} (-> (imp/traverse wrapped :User 1)
                          (imp/both "follows")
                          imp/to-set)))

        ;; Collection seed: [0 1] -> {1 2} via follows
        (is (= #{1 2} (-> (imp/traverse wrapped :User [0 1])
                          (imp/out "follows")
                          imp/to-set)))

        ;; Count nodes: 0 -> {1} via follows
        (is (= 1 (-> (imp/traverse wrapped :User 0)
                     (imp/out "follows")
                     imp/count-nodes)))

        ;; Kleisli DomainView interop
        (let [dv (imp/domain wrapped :User)
              res (.collect (.out (.from dv (long-array [0])) "follows"))]
          (is (= [1] (vec (seq res)))))

        ;; Negative & edge cases: terminal node with 0 outgoing edges, empty traversal
        (is (= 0 (-> (imp/traverse wrapped :User 3)
                     (imp/out "follows")
                     imp/count-nodes)))
        (is (= #{} (-> (imp/traverse wrapped :User 3)
                       (imp/out "follows")
                       imp/to-set)))
        (is (some? (imp/domain wrapped :NonExistentDomain)))
        (is (= #{} (-> (imp/traverse wrapped :User 0)
                       (imp/in "follows")
                       imp/to-set)))
        (is (= 0 (-> (imp/traverse wrapped :User 0)
                     (imp/in "follows")
                     imp/count-nodes)))))))

(deftest test-decimal64-ergonomics-and-roundtrip
  (testing "DECIMAL(10, 2) construction, predicates, BigDecimal roundtrip, and validation"
    (let [d1 (imp/dec64 "123.45" 10 2)
          d2 (imp/->decimal64 19.995M 10 2)
          d3 (imp/dec64 42 10 2)
          d4 (imp/dec64 "-0.05")
          d5 (imp/unscaled->decimal64 12345 10 2)]
      (is (imp/decimal64? d1))
      (is (not (imp/decimal64? 123.45M)))
      (is (= 12345 (.unscaled d1)))
      (is (= "123.45" (imp/decimal64->str d1)))
      (is (= 123.45M (imp/->bigdec d1)))
      (is (= d1 d5))
      (is (= 2000 (.unscaled d2)))
      (is (= 20.00M (imp/->bigdec d2)))
      (is (= 4200 (.unscaled d3)))
      (is (= "42.00" (imp/decimal64->str d3)))
      (is (= -5 (.unscaled d4)))
      (is (= -0.05M (imp/->bigdec d4)))
      (is (thrown? IllegalArgumentException (imp/dec64 "19.995" 10 2)))
      (is (thrown? ArithmeticException (imp/dec64 "100.00" 4 2)))))

  (testing "DECIMAL(18, 2) values > 2^53 preserve exact precision without float drift"
    (let [above53-str (imp/dec64 "90071992547409.93" 18 2)
          above53-bd  (imp/->decimal64 90071992547409.93M 18 2)
          max18       (imp/dec64 "9999999999999999.99" 18 2)]
      (is (= 9007199254740993 (.unscaled above53-str)))
      (is (= above53-str above53-bd))
      (is (= "90071992547409.93" (imp/decimal64->str above53-str)))
      (is (= 90071992547409.93M (imp/->bigdec above53-str)))
      (is (= 999999999999999999 (.unscaled max18)))
      (is (= 9999999999999999.99M (imp/->bigdec max18)))))

  (testing "with-param and filter-decimal preserve exact Decimal64 and BigDecimal values"
    (let [min-bal (imp/dec64 "90071992547409.93" 18 2)
          trav (-> (imp/traverse nil :Account 0)
                   (imp/with-param "$minBal" min-bal)
                   (imp/with-param "$feeCap" 12.34M)
                   (imp/filter-decimal :balance :>= min-bal))
          scm (imp/to-scm-string trav)]
      (is (imp/decimal64? (get-in trav [:params "minBal"])))
      (is (= min-bal (get-in trav [:params "minBal"])))
      (is (instance? java.math.BigDecimal (get-in trav [:params "feeCap"])))
      (is (= 12.34M (get-in trav [:params "feeCap"])))
      (is (.contains scm "node.balance >= 90071992547409.93"))))

  (testing "DECIMAL64 snapshot (0x15 non-null and 0x95 nullable) build & read roundtrip"
    (let [tmp (Files/createTempFile "clojure-decimal64-test" ".imps"
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          type-dec (byte 0x15)
          type-dec-nullable (unchecked-byte (bit-or 0x15 0x80))
          balances [(imp/dec64 "123.45" 10 2)
                    (imp/dec64 "-999.99" 10 2)
                    (imp/dec64 "0.00" 10 2)
                    (imp/dec64 "5000000.00" 10 2)]
          notionals [(imp/dec64 "90071992547409.93" 18 2)
                     nil
                     (imp/dec64 "-90071992547409.93" 18 2)
                     (imp/dec64 "9999999999999999.99" 18 2)]
          bal-buf (.order (ByteBuffer/allocate (* 4 8)) ByteOrder/LITTLE_ENDIAN)
          not-buf (.order (ByteBuffer/allocate (* 4 8)) ByteOrder/LITTLE_ENDIAN)
          not-val (byte-array 128)]
      (doseq [^Decimal64 d balances]
        (.putLong bal-buf (.unscaled d)))
      (let [mask (reduce-kv (fn [m idx d]
                              (if (some? d)
                                (do (.putLong not-buf (.unscaled ^Decimal64 d))
                                    (bit-or m (bit-shift-left 1 idx)))
                                (do (.putLong not-buf 0)
                                    m)))
                            0
                            notionals)]
        (aset-byte not-val 0 (unchecked-byte mask)))
      (try
        (with-open [arena (Arena/ofConfined)]
          (let [rel (RelationSnapshot. arena 4 2 (int-array [0 1 2 2 2]) (int-array [1 2]))
                graph (GraphSnapshot. arena {"transfer" rel})
                builder (-> (DefaultSnapshotBuilder.)
                            (.withVersion (short 11))
                            (.withDomain 0 "Account" (byte 0x03) 4)
                            (.withDomainAttribute 0 "balance" type-dec 1 10 2 (.array bal-buf) nil nil)
                            (.withDomainAttribute 0 "notional" type-dec-nullable 1 18 2 (.array not-buf) nil not-val))
                loaded-input (BinarySnapshotLoader$DefaultLoadedSnapshot.
                               0x494D5053 (short 11) graph {} {} {} {})
                bytes (.build builder loaded-input)]
            (Files/write tmp bytes (make-array OpenOption 0))
            (let [loaded (BinarySnapshotLoader/loadSnapshot tmp arena)
                  dom (.getDomain loaded "Account")]
              (is (some? dom))
              (is (.isNullable (.getAttribute dom "notional")))
              (is (= 0x0D (bit-and 0xFF (.get (.getValiditySegment dom "notional") ValueLayout/JAVA_BYTE 0)))))))
        (imp/with-snapshot [snap tmp]
          (is (= (nth balances 0) (imp/domain-decimal64 snap :Account :balance 0 10 2)))
          (is (= (nth balances 3) (imp/domain-decimal64 snap :Account :balance 3 10 2)))
          (is (= (nth notionals 0) (imp/domain-decimal64 snap :Account :notional 0 18 2)))
          (is (= (nth notionals 3) (imp/domain-decimal64 snap :Account :notional 3 18 2)))
          (is (= #{1} (-> (imp/traverse snap :Account 0)
                          (imp/out "transfer")
                          imp/to-set))))
        (finally
          (Files/deleteIfExists tmp))))))
