(ns paren.layout-test
  "The display tree: line breaking, redex paths and environment cards."
  (:require [cljs.test :refer [deftest is testing]]
            [paren.layout :as layout]
            [paren.presets :as presets]
            [paren.stepper :as s]))

(defn- elements [el]
  (tree-seq map? #(concat (:items %) (mapcat :els (:lines %))) el))

(defn- frame [src i] (nth (:frames (s/run src)) i))

(defn- form [display] (first (:items display)))

(deftest fits-on-one-line-when-there-is-room
  (let [f (frame "(+ (* 2 3) (- 5 1))" 0)
        d (layout/display (:tree (:st f)) 80)]
    (is (false? (:broken? (form d))))
    (is (= 1 (count (:lines (form d)))))))

(deftest breaks-like-clojure-when-narrow
  (let [f (frame (:src (presets/by-id "fib")) 0)
        wide (form (layout/display (:tree (:st f)) 200))
        narrow (form (layout/display (:tree (:st f)) 30))]
    (is (false? (:broken? wide)))
    (is (true? (:broken? narrow)))
    (testing "defn keeps its name and parameters on the first line and indents the body by 2"
      (is (= ["(" "defn" "fib" "[n]"] (map :text (:els (first (:lines narrow))))))
      (is (= 2 (:indent (second (:lines narrow))))))
    (testing "the closing paren of a broken child drops to its last line"
      (let [brs (filter #(= :br (:k %)) (elements narrow))]
        (is (some #(re-find #"tail" (:cls %)) brs))))))

(deftest cond-pairs-always-break
  (let [f (frame "(cond false 1 :else 2)" 0)
        c (form (layout/display (:tree (:st f)) 200))]
    (is (:broken? c))
    (is (= 3 (count (:lines c))) "the head, then one line per test/result pair")))

(deftest the-redex-is-in-the-display-tree
  (doseq [{:keys [id src]} presets/presets]
    (let [t (s/run src)]
      (doseq [f (butlast (:frames t))]
        (let [paths (set (keep :path (elements (layout/display (:tree (:st f)) 60))))]
          (is (contains? paths (:redex f)) (str id " step " (:index f))))))))

(deftest error-frames-draw-with-their-redex
  (testing "the step that fails (a size cap, a duplicate key) is drawn and highlighted"
    (doseq [src ["{(+ 1 1) 1 2 3}"
                 "(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) (= (g [] 40) (g [] 40))"
                 "(range 0 10 ##NaN)"
                 "(let [inc dec] (inc 5))"]]
      (let [t (s/run src)
            f (peek (:frames t))
            d (layout/display (:tree (:st f)) 60)]
        (when (:redex f)
          (is (contains? (set (keep :path (elements d))) (:redex f)) src))
        (is (seq (layout/env-cards f)) src)
        (is (number? (layout/widest-form (:tree (:st f)))) src))))
  (testing "a map with a key twice stays a card of entries, not one misleading value"
    (let [f (peek (:frames (s/run "{(+ 1 1) 1 2 3}")))
          m (form (layout/display (:tree (:st f)) 60))]
      (is (= :card (:k m)))
      (is (= ["2" "1" "2" "3"] (map :text (:items m)))))))

(deftest scope-cards-carry-a-tab
  (let [f (frame "(defn sq [x] (* x x)) (sq 4)" 2)
        scope (first (filter #(= "scope tabbed" (:cls %)) (elements (layout/display (:tree (:st f)) 80))))]
    (is (= {:name "sq" :note "x = 4"} (select-keys (:tab scope) [:name :note])))
    (is (>= (:fw scope) (layout/tab-width (:tab scope))))))

(deftest environment-cards
  (testing "a closure's remembered frame is marked"
    (let [t (s/run (:src (presets/by-id "closure")))
          f (first (filter #(= "look up `n` → 5" (:caption %)) (:frames t)))
          cards (layout/env-cards f)]
      (is (= ["add5" "make-adder" "global"] (map :label cards)))
      (is (= [false true] (map :remembered? (take 2 cards))))
      (is (= [{:sym "n" :value "5"}] (map #(select-keys % [:sym :value]) (:rows (second cards)))))
      (is (:found? (first (:rows (second cards)))) "the binding about to be looked up is marked")
      (is (= ["make-adder" "add5"] (map :sym (:rows (last cards)))))))
  (testing "inner lets come first, and a rebinding shadows the earlier one"
    (let [f (frame "(let [x 1 x 2] (let [y 3] (+ x y)))" 4)
          cards (layout/env-cards f)]
      (is (= ["let" "let" "global"] (map :label cards)))
      (is (= [true false] (map :shadowed? (:rows (second cards)))))))
  (testing "the global card is always last"
    (is (= "global" (:label (last (layout/env-cards (frame "1" 0))))))))

(deftest widest-form
  (is (= (+ layout/pad 7) (layout/widest-form (:tree (:st (frame "(inc 1)" 0)))))))
