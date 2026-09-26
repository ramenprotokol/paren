(ns paren.caps-test
  "Runaway programs stop with a clear message instead of freezing the page."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [paren.stepper :as s]))

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
