(ns org.impulsegraph.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.impulsegraph.core :as imp])
  (:import [org.impulsegraph.api.bitset OffHeapBitSet]
           [org.impulsegraph.storage.csr GraphSnapshot RelationSnapshot]
           [java.lang.foreign Arena MemorySegment ValueLayout]))

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
  (let [;; follows: 0 -> 1, 1 -> 2, 2 -> 3
        f-row-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 1 2 3 3]))
        f-col-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [1 2 3]))
        f-csc-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 0 1 2 3]))
        f-csc-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 1 2]))
        rel-follows (RelationSnapshot. arena 4 3 f-row-offsets f-col-targets f-csc-offsets f-csc-targets)

        ;; userToGroup: 0 -> 1, 1 -> 2
        u2g-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 1 2 2 2]))
        u2g-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [1 2]))
        u2g-csc-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 0 1 2 2]))
        u2g-csc-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 1]))
        rel-u2g (RelationSnapshot. arena 4 2 u2g-offsets u2g-targets u2g-csc-offsets u2g-csc-targets)

        ;; groupToRole: 1 -> 2, 2 -> 3
        g2r-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 0 1 2 2]))
        g2r-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [2 3]))
        g2r-csc-offsets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [0 0 0 1 2]))
        g2r-csc-targets (.allocateArray ^Arena arena ValueLayout/JAVA_INT (int-array [1 2]))
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
  (testing "Clojure S-expression threading traversal construction"
    (let [trav (-> (imp/traverse nil 14726)
                   (imp/out "DaG")
                   (imp/out "GpPW")
                   (imp/in "GpPW")
                   (imp/in-filtered "CbG" "edge.affinity <= $maxAffinity")
                   (imp/with-param "maxAffinity" 50.0))]
      (is (= 14726 (:start-node trav)))
      (is (= 4 (count (:steps trav))))
      (is (= {"maxAffinity" 50.0} (:params trav)))
      (is (= :out (:direction (first (:steps trav)))))
      (is (= :in (:direction (last (:steps trav))))))))

(deftest test-defquery-macro
  (testing "defquery macro expansion"
    (imp/defquery drug-repurposing [snap seed]
      (imp/out "DaG")
      (imp/out "GpPW")
      (imp/in "GpPW")
      (imp/in "CbG"))

    (let [q (drug-repurposing nil 14726)]
      (is (= 4 (count (:steps q))))
      (is (= "DaG" (:relation-name (first (:steps q)))))
      (is (= "CbG" (:relation-name (last (:steps q))))))))

(deftest test-ast-inspection-and-scm-generation
  (testing "to-ast and to-scm-string homoiconic inspection"
    (let [trav (-> (imp/traverse nil 0)
                   (imp/out "userToGroup")
                   (imp/filter-nodes "node.active == true")
                   (imp/project "node.score = 1.0"))
          ast (imp/to-ast trav)
          scm (imp/to-scm-string trav)]
      (is (some? ast))
      (is (string? scm))
      (is (.contains scm "csr-walk"))
      (is (.contains scm "userToGroup")))))

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
  (testing "SnapshotWrapper ILookup, Counted, and IImpulseSnapshot protocol"
    (with-open [arena (Arena/ofConfined)]
      (let [raw (create-synthetic-graph arena)
            wrapped (imp/->SnapshotWrapper raw)]
        (is (= 3 (count wrapped)))
        (is (= 3 (:follows wrapped)))
        (is (= 2 (:userToGroup wrapped)))
        (is (= 2 (:groupToRole wrapped)))
        (is (= 3 (get wrapped "follows")))
        (is (nil? (:nonExistent wrapped)))
        (is (= 3 (imp/edge-count wrapped :follows)))
        (is (= 2 (imp/edge-count wrapped :userToGroup)))
        (is (= 3 (imp/relation-count wrapped)))
        (is (contains? (imp/relation-names wrapped) "follows"))
        (is (contains? (imp/relation-names wrapped) "userToGroup"))
        (is (contains? (imp/relation-names wrapped) "groupToRole"))))))

(deftest test-synthetic-graph-query-execution
  (testing "Homogeneous follows, heterogeneous userToGroup->groupToRole, reverse walk, both, count-nodes"
    (with-open [arena (Arena/ofConfined)]
      (let [raw (create-synthetic-graph arena)
            wrapped (imp/->SnapshotWrapper raw)]
        ;; 1-hop: 0 -> 1 via follows
        (let [res (-> (imp/traverse wrapped "User" 0)
                      (imp/out "follows")
                      imp/to-set)]
          (is (= #{1} res)))

        ;; Homogeneous 2-hop: (User 0) -> (User 1) -> (User 2) via follows
        (let [res (-> (imp/traverse wrapped "User" 0)
                      (imp/out "follows")
                      (imp/out "follows")
                      imp/to-vec)]
          (is (= [2] res)))

        ;; Heterogeneous 2-hop: (User 0) -> (Group 1) -> (Role 2) via userToGroup then groupToRole
        (let [res (-> (imp/traverse wrapped "User" 0)
                      (imp/out "userToGroup")
                      (imp/out "groupToRole")
                      imp/to-vec)]
          (is (= [2] res)))

        ;; Reverse walk: 2 <- 1 via follows
        (let [res (-> (imp/traverse wrapped "User" 2)
                      (imp/in "follows")
                      imp/to-set)]
          (is (= #{1} res)))

        ;; Both forward and reverse: 1 -> {0, 2} via follows
        (let [res (-> (imp/traverse wrapped "User" 1)
                      (imp/both "follows")
                      imp/to-set)]
          (is (= #{0 2} res)))

        ;; Collection seed: [0 1] -> {1 2} via follows
        (let [res (-> (imp/traverse wrapped "User" [0 1])
                      (imp/out "follows")
                      imp/to-set)]
          (is (= #{1 2} res)))

        ;; Count nodes: 0 -> {1} via follows
        (let [cnt (-> (imp/traverse wrapped "User" 0)
                      (imp/out "follows")
                      imp/count-nodes)]
          (is (= 1 cnt)))))))
