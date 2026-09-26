(ns paren.stepper-test
  "The stepper on each supported form: the steps it takes (as captions) and
  the value it reaches."
  (:require [cljs.test :refer [deftest is testing]]
            [paren.stepper :as s]
            [paren.values :as v]))

(defn caps [src] (s/captions (s/run src)))
(defn result [src] (:result (s/run src)))
(defn status [src] (:status (s/run src)))
(defn message [src] (:message (s/run src)))

(deftest self-evaluating-data
  (testing "literals take no steps"
    (is (= ["done → 42"] (caps "42")))
    (is (= ["done → \"hi\""] (caps "\"hi\"")))
    (is (= ["done → :k"] (caps ":k")))
    (is (= ["done → nil"] (caps "nil")))
    (is (= ["done → true"] (caps "true")))
    (is (= ["done → [1 2 3]"] (caps "[1 2 3]")))
    (is (= ["done → {:a 1}"] (caps "{:a 1}")))
    (is (= ["done → ()"] (caps "()")))))

(deftest collections-with-subexpressions
  (is (= ["apply `+` to 1 1 → 2" "done → [1 2]"] (caps "[1 (+ 1 1)]")))
  (is (= [1 2] (result "[1 (+ 1 1)]")))
  (is (= {:a 2 :b [3]} (result "{:a (inc 1) :b [(inc 2)]}")))
  (testing "a vector of built-ins is already finished"
    (is (= 1 (count (caps "[inc dec]"))))))

(deftest arithmetic-and-comparison
  (is (= ["apply `+` to 1 2 3 → 6" "done → 6"] (caps "(+ 1 2 3)")))
  (is (= ["apply `+` to no arguments → 0" "done → 0"] (caps "(+)")))
  (is (= 0.5 (result "(/ 1 2)")))
  (is (= -3 (result "(- 3)")))
  (is (= 12 (result "(* 2 (+ 1 5))")))
  (is (= [true false true true] (result "[(< 1 2 3) (> 1 2) (<= 2 2) (= [1 2] [1 2])]")))
  (is (= [2 0] (result "[(inc 1) (dec 1)]")))
  (is (= [1 2 3] (result "[(mod 7 3) (quot 7 3) (max 1 3 2)]")))
  (testing "arguments are evaluated left to right, innermost first"
    (is (= ["apply `+` to 1 2 → 3" "apply `*` to 3 4 → 12" "apply `-` to 3 12 → -9" "done → -9"]
           (caps "(- (+ 1 2) (* 3 4))")))))

(deftest def-and-lookup
  (is (= ["define `x` = 5" "look up `x` → 5" "done → 5"] (caps "(def x 5) x")))
  (is (= ["apply `*` to 2 3 → 6" "define `x` = 6" "done → #'user/x"] (caps "(def x (* 2 3))")))
  (testing "a docstring is allowed"
    (is (= 1 (result "(def one \"the number one\" 1) one")))))

(deftest defn-and-call
  (is (= ["define `square` as a function"
          "enter `square` with x = 3"
          "look up `x` → 3"
          "look up `x` → 3"
          "apply `*` to 3 3 → 9"
          "`square` returns 9"
          "done → 9"]
         (caps "(defn square [x] (* x x)) (square 3)")))
  (is (= 9 (result "(defn square \"doc\" [x] (* x x)) (square 3)"))))

(deftest fn-values
  (is (= ["make the function `‹fn [x]›`"
          "enter `fn` with x = 3"
          "look up `x` → 3"
          "apply `inc` to 3 → 4"
          "`fn` returns 4"
          "done → 4"]
         (caps "((fn [x] (inc x)) 3)")))
  (testing "a named fn can call itself"
    (is (= 120 (result "((fn fact [n] (if (= n 0) 1 (* n (fact (dec n))))) 5)"))))
  (testing "rest parameters"
    (is (= '(2 3) (result "((fn [x & more] more) 1 2 3)")))
    (is (nil? (result "((fn [x & more] more) 1)"))))
  (testing "#(…) short functions read as fn, with % names"
    (is (= [2 4 6] (vec (result "(map #(* % 2) [1 2 3])"))))
    (is (some #(re-find #"‹fn \[%\]›" %) (caps "(map #(* % 2) [1])")))
    (is (= 5 (result "(#(+ %1 %2) 2 3)")))))

(deftest let-binds-in-order-and-shadows
  (is (= ["bind `a` = 1"
          "look up `a` → 1"
          "apply `inc` to 1 → 2"
          "bind `b` = 2"
          "look up `a` → 1"
          "look up `b` → 2"
          "apply `+` to 1 2 → 3"
          "`let` returns 3"
          "done → 3"]
         (caps "(let [a 1 b (inc a)] (+ a b))")))
  (is (= 2 (result "(let [x 1 x (inc x)] x)")))
  (is (= 1 (result "(let [x 1] (let [x 2] x) x)")))
  (is (nil? (result "(let [x 1])"))))

(deftest if-branches
  (is (= ["`if` test is true → take the then-branch" "done → 1"] (caps "(if true 1 2)")))
  (is (= ["`if` test is false → take the else-branch" "done → 2"] (caps "(if false 1 2)")))
  (is (= ["`if` test is nil, which is falsy; there is no else-branch → nil" "done → nil"]
         (caps "(if nil 1)")))
  (is (= ["`if` test is 0, which is truthy → take the then-branch" "done → :yes"]
         (caps "(if 0 :yes :no)")))
  (testing "only the chosen branch is evaluated"
    (is (= :ok (result "(if true :ok (undefined-thing))")))))

(deftest cond-clauses
  (is (= ["`cond` test is false → try the next clause"
          "`cond` test is :else, which is truthy → take this branch"
          "done → :b"]
         (caps "(cond false :a :else :b)")))
  (is (= ["`cond` test is false → try the next clause" "`cond` has no clause left → nil" "done → nil"]
         (caps "(cond false :a)")))
  (is (nil? (result "(cond)"))))

(deftest do-discards-all-but-the-last
  (is (= ["`do` discards 1 and moves on" "`do` returns 2" "done → 2"] (caps "(do 1 2)")))
  (is (nil? (result "(do)"))))

(deftest and-or-short-circuit
  (is (= ["`and`: 1 is truthy → keep going" "`and` stops at false, which is falsy" "done → false"]
         (caps "(and 1 false (boom))")))
  (is (= ["`or`: nil is falsy → keep going" "`or` stops at 2, which is truthy" "done → 2"]
         (caps "(or nil 2 (boom))")))
  (is (true? (result "(and)")))
  (is (nil? (result "(or)")))
  (is (= 3 (result "(and 1 2 3)")))
  (is (false? (result "(or nil false)"))))

(deftest quote-returns-data
  (is (= ["`quote` returns (1 2 3) without evaluating it" "done → (1 2 3)"] (caps "'(1 2 3)")))
  (is (= 6 (result "(reduce + '(1 2 3))"))))

(deftest map-filter-reduce
  (testing "map unfolds into one call per item"
    (is (= ["`map` calls `inc` on each item of [1 2]"
            "apply `inc` to 1 → 2"
            "apply `inc` to 2 → 3"
            "done → (2 3)"]
           (caps "(map inc [1 2])")))
    (is (= '(4 6) (result "(map + [1 2] [3 4 5])")))
    (is (= ["`map` over an empty collection → ()" "done → ()"] (caps "(map inc [])"))))
  (testing "filter tests each item, then keeps or drops it"
    (is (= ["`filter` tests each item of [1 2 3] with `odd?`"
            "apply `odd?` to 1 → true"
            "`filter` keeps 1"
            "apply `odd?` to 2 → false"
            "`filter` drops 2"
            "apply `odd?` to 3 → true"
            "`filter` keeps 3"
            "done → (1 3)"]
           (caps "(filter odd? [1 2 3])"))))
  (testing "reduce with a start value"
    (is (= ["`reduce` calls `+` on 10 and 1"
            "apply `+` to 10 1 → 11"
            "`reduce` calls `+` on 11 and 2"
            "apply `+` to 11 2 → 13"
            "`reduce` has nothing left → 13"
            "done → 13"]
           (caps "(reduce + 10 [1 2])"))))
  (testing "reduce edge cases follow Clojure"
    (is (= 0 (result "(reduce + [])")))
    (is (= 7 (result "(reduce + [7])")))
    (is (= 5 (result "(reduce + 5 [])"))))
  (testing "map and filter over maps and strings"
    (is (= '(:a :b) (result "(map first {:a 1 :b 2})")))
    (is (= '("a" "b") (result "(map str \"ab\")"))))
  (testing "user functions inside map run as visible calls"
    (is (= '(1 4 9) (result "(map (fn [x] (* x x)) [1 2 3])")))
    (is (= 3 (count (filter #(re-find #"^enter `fn`" %) (caps "(map (fn [x] (* x x)) [1 2 3])")))))))

(deftest recursion
  (is (= 120 (result "(defn fact [n] (if (= n 0) 1 (* n (fact (dec n))))) (fact 5)")))
  (is (= 55 (result "(defn sum [xs] (if (empty? xs) 0 (+ (first xs) (sum (rest xs))))) (sum (range 11))"))))

(deftest closures-remember-their-environment
  (let [src "(defn make-adder [n] (fn [x] (+ x n))) (def add5 (make-adder 5)) (add5 10)"]
    (is (= 15 (result src)))
    (is (some #{"make the function `‹fn [x]›`, remembering n = 5"} (caps src)))
    (testing "the closure's frame is still on the environment chain when called"
      (let [t (s/run src)
            frame (first (filter #(= "look up `n` → 5" (:caption %)) (:frames t)))
            labels (loop [e (:env frame) out []] (if e (recur (:parent e) (conj out (:label e))) out))]
        (is (= ["add5" "make-adder"] labels)))))
  (testing "a closure made in a let"
    (is (= 11 (result "(let [k 10 f (fn [x] (+ x k))] (f 1))")))))

(deftest collections-as-functions
  (is (= ["look up :a in {:a 1} → 1" "done → 1"] (caps "(:a {:a 1})")))
  (is (= :none (result "(:b {:a 1} :none)")))
  (is (= 2 (result "({:x 2} :x)")))
  (is (= ["take item 1 of [10 20] → 20" "done → 20"] (caps "([10 20] 1)"))))

(deftest collection-builtins
  (is (= 3 (result "(count [1 2 3])")))
  (is (= [1 2 3] (result "(conj [1 2] 3)")))
  (is (= '(0 1 2) (result "(cons 0 '(1 2))")))
  (is (= {:a 1 :b 2} (result "(assoc {:a 1} :b 2)")))
  (is (= 1 (result "(get {:a 1} :a)")))
  (is (= :z (result "(nth [1 2] 5 :z)")))
  (is (= "a1:b" (result "(str \"a\" 1 :b nil)")))
  (is (= '(0 2 4) (result "(range 0 6 2)"))))

(deftest environment-per-step
  (testing "each frame records the redex path and the environment around it"
    (let [t (s/run "(let [a 1] a)")
          lookup (second (:frames t))]
      (is (= "look up `a` → 1" (:caption lookup)))
      (is (= [:forms 0 :body 0] (:redex lookup)))
      (is (= [['a 1]] (:vars (:env lookup))))
      (is (= 'a (:sym (:lookup lookup))))))
  (testing "a finished trace ends with no redex"
    (is (nil? (:redex (peek (:frames (s/run "(+ 1 2)"))))))))

(deftest runtime-errors-have-clear-messages
  (testing "an unknown name"
    (let [t (s/run "(+ 1 nope)")]
      (is (= :error (:status t)))
      (is (= "Unable to resolve symbol `nope`: nothing by that name is defined here." (:message t)))
      (is (= [:forms 0 :args 1] (:redex (peek (:frames t)))))))
  (testing "an unknown function fails before its arguments run"
    (is (= ["Unable to resolve symbol `nope`: nothing by that name is defined here."]
           (caps "(nope (+ 1 2))"))))
  (is (= "`square` takes 1 argument, but got 2"
         (message "(defn square [x] (* x x)) (square 1 2)")))
  (is (= "`inc` takes 1 argument, but got 0" (message "(inc)")))
  (is (= "`+` works on numbers, but got :a (a keyword)" (message "(+ 1 :a)")))
  (is (= "3 is a number, not a function, so it can't be called" (message "(3 4)")))
  (is (= "Index 5 is out of bounds for [1 2]" (message "(nth [1 2] 5)")))
  (is (= "`map` needs a collection, but got 5 (a number)" (message "(map inc 5)")))
  (is (= "`even?` needs a whole number, but got 1.5" (message "(even? 1.5)"))))

(deftest printing-values
  (is (= "‹fn fib›" (v/show (result "(defn fib [n] n) fib"))))
  (is (= "#'user/x" (v/show (result "(def x 1)"))))
  (is (= "{:a [1 \"s\"], :b nil}" (v/show {:a [1 "s"] :b nil})))
  (is (= "##Inf" (v/show (result "(/ 1 0)"))))
  (is (= "[1 2 3 4 5 6 7 8 9 1…" (v/show (vec (range 1 20)) 20))))
