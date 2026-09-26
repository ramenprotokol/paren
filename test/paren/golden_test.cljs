(ns paren.golden-test
  "Golden step sequences: the exact captions each preset produces, in order,
  and its final value. A change to evaluation order or wording shows up
  here as a diff you can read."
  (:require [cljs.test :refer [deftest is testing]]
            [paren.presets :as presets]
            [paren.stepper :as s]))

(def golden
  {"fib"
   {:result "3"
    :captions
    ["define `fib` as a function"
     "enter `fib` with n = 4"
     "look up `n` → 4"
     "apply `<` to 4 2 → false"
     "`if` test is false → take the else-branch"
     "look up `n` → 4"
     "apply `-` to 4 1 → 3"
     "enter `fib` with n = 3"
     "look up `n` → 3"
     "apply `<` to 3 2 → false"
     "`if` test is false → take the else-branch"
     "look up `n` → 3"
     "apply `-` to 3 1 → 2"
     "enter `fib` with n = 2"
     "look up `n` → 2"
     "apply `<` to 2 2 → false"
     "`if` test is false → take the else-branch"
     "look up `n` → 2"
     "apply `-` to 2 1 → 1"
     "enter `fib` with n = 1"
     "look up `n` → 1"
     "apply `<` to 1 2 → true"
     "`if` test is true → take the then-branch"
     "look up `n` → 1"
     "`fib` returns 1"
     "look up `n` → 2"
     "apply `-` to 2 2 → 0"
     "enter `fib` with n = 0"
     "look up `n` → 0"
     "apply `<` to 0 2 → true"
     "`if` test is true → take the then-branch"
     "look up `n` → 0"
     "`fib` returns 0"
     "apply `+` to 1 0 → 1"
     "`fib` returns 1"
     "look up `n` → 3"
     "apply `-` to 3 2 → 1"
     "enter `fib` with n = 1"
     "look up `n` → 1"
     "apply `<` to 1 2 → true"
     "`if` test is true → take the then-branch"
     "look up `n` → 1"
     "`fib` returns 1"
     "apply `+` to 1 1 → 2"
     "`fib` returns 2"
     "look up `n` → 4"
     "apply `-` to 4 2 → 2"
     "enter `fib` with n = 2"
     "look up `n` → 2"
     "apply `<` to 2 2 → false"
     "`if` test is false → take the else-branch"
     "look up `n` → 2"
     "apply `-` to 2 1 → 1"
     "enter `fib` with n = 1"
     "look up `n` → 1"
     "apply `<` to 1 2 → true"
     "`if` test is true → take the then-branch"
     "look up `n` → 1"
     "`fib` returns 1"
     "look up `n` → 2"
     "apply `-` to 2 2 → 0"
     "enter `fib` with n = 0"
     "look up `n` → 0"
     "apply `<` to 0 2 → true"
     "`if` test is true → take the then-branch"
     "look up `n` → 0"
     "`fib` returns 0"
     "apply `+` to 1 0 → 1"
     "`fib` returns 1"
     "apply `+` to 2 1 → 3"
     "`fib` returns 3"
     "done → 3"]}

   "reduce"
   {:result "9"
    :captions
    ["`map` calls `inc` on each item of [1 2 3]"
     "apply `inc` to 1 → 2"
     "apply `inc` to 2 → 3"
     "apply `inc` to 3 → 4"
     "`reduce` calls `+` on 2 and 3"
     "apply `+` to 2 3 → 5"
     "`reduce` calls `+` on 5 and 4"
     "apply `+` to 5 4 → 9"
     "`reduce` has nothing left → 9"
     "done → 9"]}

   "let"
   {:result "12"
    :captions
    ["bind `x` = 1"
     "look up `x` → 1"
     "apply `+` to 1 1 → 2"
     "bind `y` = 2"
     "bind `x` = 10"
     "look up `x` → 10"
     "look up `y` → 2"
     "apply `+` to 10 2 → 12"
     "`let` returns 12"
     "`let` returns 12"
     "done → 12"]}

   "closure"
   {:result "15"
    :captions
    ["define `make-adder` as a function"
     "enter `make-adder` with n = 5"
     "make the function `‹fn [x]›`, remembering n = 5"
     "`make-adder` returns ‹fn [x]›"
     "define `add5` = ‹fn [x]›"
     "enter `add5` with x = 10"
     "look up `x` → 10"
     "look up `n` → 5"
     "apply `+` to 10 5 → 15"
     "`add5` returns 15"
     "done → 15"]}

   "cond"
   {:result "(:negative :zero :positive)"
    :captions
    ["define `sign` as a function"
     "look up `sign` → ‹fn sign›"
     "`map` calls `sign` on each item of [-5 0 7]"
     "enter `sign` with x = -5"
     "look up `x` → -5"
     "apply `<` to -5 0 → true"
     "`cond` test is true → take this branch"
     "`sign` returns :negative"
     "enter `sign` with x = 0"
     "look up `x` → 0"
     "apply `<` to 0 0 → false"
     "`cond` test is false → try the next clause"
     "look up `x` → 0"
     "apply `=` to 0 0 → true"
     "`cond` test is true → take this branch"
     "`sign` returns :zero"
     "enter `sign` with x = 7"
     "look up `x` → 7"
     "apply `<` to 7 0 → false"
     "`cond` test is false → try the next clause"
     "look up `x` → 7"
     "apply `=` to 7 0 → false"
     "`cond` test is false → try the next clause"
     "`cond` test is :else, which is truthy → take this branch"
     "`sign` returns :positive"
     "done → (:negative :zero :positive)"]}})

(deftest preset-step-sequences
  (doseq [{:keys [id src]} presets/presets]
    (testing id
      (let [t (s/run src)
            {:keys [captions]} (get golden id)]
        (is (= :done (:status t)))
        (is (= captions (s/captions t)))))))

(deftest preset-final-values
  (is (= 3 (:result (s/run (:src (presets/by-id "fib"))))))
  (is (= 9 (:result (s/run (:src (presets/by-id "reduce"))))))
  (is (= 12 (:result (s/run (:src (presets/by-id "let"))))))
  (is (= 15 (:result (s/run (:src (presets/by-id "closure"))))))
  (is (= '(:negative :zero :positive) (:result (s/run (:src (presets/by-id "cond")))))))

(deftest golden-results-match-printed-values
  (doseq [{:keys [id src]} presets/presets]
    (is (= (str "done → " (get-in golden [id :result]))
           (peek (s/captions (s/run src)))))))
