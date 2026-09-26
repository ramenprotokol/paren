(ns paren.caps-test
  "Runaway programs stop with a clear message instead of freezing the page."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [paren.builtins :as b]
            [paren.stepper :as s]))

(defn- timed-run
  "Runs src; returns the trace with :ms, the wall-clock time it took."
  [src]
  (let [t0 (js/Date.now)
        t (s/run src)]
    (assoc t :ms (- (js/Date.now) t0))))

(deftest step-cap
  (testing "a long, shallow computation stops at 5,000 steps"
    ;; about six steps per item for a thousand items
    (let [t (s/run "(reduce (fn [acc x] (+ acc x)) 0 (range 1000))")
          last-frame (peek (:frames t))]
      (is (= :step-cap (:status t)))
      (is (= (inc s/max-steps) (count (:frames t))) "every step up to the cap is kept for scrubbing")
      (is (= :step-cap (:status last-frame)))
      (is (some? (:redex last-frame)) "the step that would have run next is still highlighted")
      (is (str/starts-with? (:message t) "Stopped at the step cap: 5,000 steps ran without finishing."))))
  (testing "just under the cap finishes"
    (is (= :done (:status (s/run "(reduce (fn [acc x] (+ acc x)) 0 (range 100))"))))))

(deftest depth-cap
  (testing "runaway recursion stops at 100 nested calls"
    (let [t (s/run "(defn down [n] (down (inc n))) (down 0)")]
      (is (= :depth-cap (:status t)))
      (is (= (str "Stopped at the recursion cap: calling `down` here would put 101 calls in progress "
                  "at once, and paren allows 100. Check that the recursion reaches its base case.")
             (:message t)))
      (is (= 100 (count (filter #(str/starts-with? (:caption %) "enter `down`") (:frames t)))))))
  (testing "deep but bounded recursion is fine"
    (is (= 90 (:result (s/run "(defn count-up [n] (if (= n 0) 0 (inc (count-up (dec n))))) (count-up 90)"))))))

(deftest size-cap
  (let [t (s/run "(map inc (range 1000))")]
    (is (= :size-cap (:status t)))
    (is (re-find #"grew past 2,500 boxes" (:message t)))))

(deftest value-caps
  (testing "str will not build enormous strings"
    (let [t (s/run "(defn grow [s n] (if (= n 0) s (grow (str s s) (dec n)))) (grow \"ab\" 20)")]
      (is (= :error (:status t)))
      (is (re-find #"longer than 10000 characters" (:message t)))))
  (testing "range is eager, so it is capped"
    (is (re-find #"more than 1000 numbers" (:message (s/run "(range 5000)"))))
    (is (re-find #"step of 0" (:message (s/run "(range 0 10 0)"))))))

(deftest range-rejects-non-finite-numbers
  (testing "a NaN step used to pass the zero-step and count checks and fill memory"
    (doseq [src ["(range 0 10 ##NaN)" "(range 0 10 (/ 0 0))" "(range ##NaN)" "(range 0 ##Inf)" "(range ##-Inf 0 1)"]]
      (let [t (timed-run src)]
        (is (= :error (:status t)) src)
        (is (re-find #"^`range` needs finite numbers, but got ##(NaN|Inf|-Inf)$" (:message t)) src)
        (is (< (:ms t) 1000) src))))
  (testing "a step too small to change the start can't loop forever"
    (let [t (timed-run "(range 100000000000000000 100000000000000400 0.5)")]
      (is (re-find #"more than 1000 numbers" (:message t)))
      (is (< (:ms t) 1000))))
  (testing "otherwise range matches ClojureScript's, number for number"
    (doseq [[args expected] [["5" (range 5)] ["2 5" (range 2 5)] ["0 1 0.1" (range 0 1 0.1)]
                             ["10 0 -3" (range 10 0 -3)] ["0 10 2.5" (range 0 10 2.5)]
                             ["5 5" (range 5 5)] ["0 -5" (range 0 -5)] ["-1 1 0.5" (range -1 1 0.5)]]]
      (is (= expected (:result (s/run (str "(range " args ")")))) args))))

(def doubling
  "The reviewer's repro: each call doubles a vector by sharing, so 40 calls
  make 2^40 leaves in 40 small objects, and comparing two of them walks
  every leaf."
  "(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) (= (g [] 40) (g [] 40))")

(deftest value-size-cap
  (testing "a value may hold at most 10,000 items, counting nested ones"
    (let [t (timed-run doubling)]
      (is (= :error (:status t)))
      (is (= (str "This vector would hold more than 10,000 items, counting everything nested inside it, "
                  "which is paren's cap for a single value")
             (:message t)))
      (is (< (:ms t) 2000) "it fails fast")
      (is (< (count (:frames t)) 300))))
  (testing "the same through built-ins"
    (doseq [[src who] [["(defn g [v n] (if (= n 0) v (g (vector v v) (dec n)))) (g [] 40)" "vector"]
                       ["(defn g [v n] (if (= n 0) v (g (list v v) (dec n)))) (g [] 40)" "list"]
                       ["(defn g [v n] (if (= n 0) v (g (conj v v) (dec n)))) (g [1] 40)" "conj"]
                       ["(defn g [v n] (if (= n 0) v (g (cons v (list v)) (dec n)))) (g [] 40)" "cons"]
                       ["(defn g [v n] (if (= n 0) v (g (assoc {} :a v :b v) (dec n)))) (g [] 40)" "assoc"]]]
      (let [t (timed-run src)]
        (is (= (str "The result of `" who "` would hold more than 10,000 items, counting everything "
                    "nested inside it, which is paren's cap for a single value")
               (:message t)) src)
        (is (< (:ms t) 2000) src))))
  (testing "map literals and lists built by map"
    (is (re-find #"^This map would hold more than 10,000 items"
                 (:message (s/run "(defn g [v n] (if (= n 0) v (g {:a v :b v} (dec n)))) (g [] 40)"))))
    (is (re-find #"^The list built by `map` would hold more than 10,000 items"
                 (:message (s/run "(def big (range 1000)) (map (fn [i] big) (range 11))")))))
  (testing "a rest parameter"
    (is (re-find #"^The rest argument `xs` would hold more than 10,000 items"
                 (:message (s/run "(def big (range 1000)) ((fn [& xs] xs) big big big big big big big big big big big)")))))
  (testing "values up to the cap are fine"
    (is (= :done (:status (s/run "(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) (count (g [] 12))"))))
    (is (= 9 (:result (s/run "(def xs (range 1000)) (count [xs xs xs xs xs xs xs xs xs])")))
        "9 × 1,001 + 1 = 9,010 items")
    (is (re-find #"^This vector would hold more than 10,000"
                 (:message (s/run "(def xs (range 1000)) (count [xs xs xs xs xs xs xs xs xs xs])")))
        "10 × 1,001 + 1 = 10,011 items")))

(deftest value-size-counts-shared-parts-each-time
  (let [v (reduce (fn [v _] [v v]) [] (range 40))]
    (is (> (b/value-size v) b/max-value) "2^40 leaves, sized without walking them all")
    (is (= 7 (b/value-size [[1 2] [3 4]])))
    (is (= 1 (b/value-size 5)))
    (is (= 4 (b/value-size {:a 1})) "a map counts itself, each entry, and the entry's key and value")))
