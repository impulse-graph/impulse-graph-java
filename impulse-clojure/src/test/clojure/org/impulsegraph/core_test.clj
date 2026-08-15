(ns org.impulsegraph.core-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [org.impulsegraph.core :as imp]))

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
