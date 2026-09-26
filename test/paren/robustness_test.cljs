(ns paren.robustness-test
  "Odd and hostile inputs: every one ends in a clear status, never an
  internal error."
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [paren.stepper :as s]))

(def odd-inputs
  ["(fn)" "(let)" "(def 1 2)" "(defn)" "(defn f \"doc\")" "(quote)" "{:a}" "(1 2 3)"
   "(\"abc\" 1)" "(nil)" "(#(%) 1)" "(str)" "(max)" "(=)" "(get)" "(assoc [1] 5 2)"
   "(conj nil 1)" "(first 5)" "(count {:a 1})" "(nth \"abc\" 1)" "(rest nil)" "(cons 1 nil)"
   "(let [a 1] (def b a) b)" "(* 99999999999 99999999999)" "(/ 0 0)" "(= ##NaN ##NaN)"
   "(map + [1] [2] [3])" "(reduce (fn [a b] (+ a b)) [1 2 3])" "{:a nope}" "[1 (nope)]"
   "(def m {:a (inc 1)}) (:a m)" "(str \"`\" :x)" "(conj {:a 1} 5)" "(filter 5 [1])"
   "(map (fn [x y] x) [1 2])" "((fn [& xs] (count xs)))" "(let [inc dec] (inc 5))"
   "(defn inc [x] x) (inc 1)" "(if)" ")" "(" "#" "#_ 1" "\\a" "(:a)" "([1] 1 2)"
   "(reduce + 1)" "(range)" "(and (or) (and))" "(cond :else)" "(do (def x 1) (def x 2) x)"
   "(fn [x x] x)" "((fn [x x] x) 1 2)" "'(1 (2 [3 {:a 4}]))" "(#{1} 1)" "#inst \"2020\""
   "::kw" "(defn f [n] (if (> n 0) (f (- n 1)) :done)) (f 99)"
   ;; Found in review: each once hung the tab, reached the runtime, or read oddly.
   "(range 0 10 ##NaN)" "(range 0 10 (/ 0 0))" "(range 100000000000000000 100000000000000400 0.5)"
   "(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) (= (g [] 40) (g [] 40))"
   "(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) {(g [] 40) 1}"
   "`(1 2)" "(.toUpperCase \"a\")" "(String. \"a\")" "(. \"a\" toUpperCase)" "(Math/abs -1)"
   "{(+ 1 1) 1 2 3}" "(def g dec) (g (do (def g inc) 1))" "(str (range 1000) (range 1000) (range 1000))"])

(deftest odd-inputs-never-hit-an-internal-error
  (doseq [src odd-inputs]
    (let [t0 (js/Date.now)
          t (s/run src)]
      (is (< (- (js/Date.now) t0) 3000) (str "finishes promptly: " src))
      (is (#{:done :error :step-cap :depth-cap :size-cap} (:status t)) src)
      (is (not (str/includes? (str (:message t)) "internal error")) src)
      (when (= :error (:status t))
        (is (seq (:message t)) src)))))

(deftest some-odd-inputs-have-exact-answers
  (is (= 4 (:result (s/run "(let [inc dec] (inc 5))"))))
  (is (= 1 (:result (s/run "(defn inc [x] x) (inc 1)"))))
  (is (= 2 (:result (s/run "((fn [x x] x) 1 2)"))))
  (is (= 0 (:result (s/run "((fn [& xs] (count xs)))"))))
  (is (= :done (:result (s/run "(defn f [n] (if (> n 0) (f (- n 1)) :done)) (f 99)"))))
  (is (= :depth-cap (:status (s/run "(defn f [n] (if (> n 0) (f (- n 1)) :done)) (f 100)"))))
  (is (some #(str/includes? % "\"ˋ\"") (s/captions (s/run "(str \"`\" :x)")))
      "a backtick inside a value can't break caption formatting"))
