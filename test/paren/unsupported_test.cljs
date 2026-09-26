(ns paren.unsupported-test
  "Anything outside the teaching subset is refused before a step runs, with
  a message that names it."
  (:require [cljs.test :refer [deftest is testing]]
            [paren.stepper :as s]))

(defn refusal [src]
  (let [t (s/run src)]
    (is (= :error (:status t)) src)
    (is (empty? (:frames t)) src)
    (select-keys t [:kind :message])))

(declare are-messages)

(deftest unsupported-forms-are-named
  (are-messages
   {"(loop [i 0] (if (< i 3) (recur (inc i)) i))"
    "`loop` isn't in paren's teaching subset (write it as plain recursion instead)."
    "(when true 1)" "`when` isn't in paren's teaching subset (write (if test (do …)) instead)."
    "(-> 1 inc)" "`->` isn't in paren's teaching subset (write the calls nested instead)."
    "(defmacro m [] 1)" "`defmacro` isn't in paren's teaching subset (macros are on paren's Next list)."
    "(println 1)" "`println` isn't in paren's teaching subset (paren shows values, not printed output)."
    "(take 2 [1 2 3])" "`take` isn't in paren's teaching subset."
    "(drop-while odd? [1 2])" "`drop-while` isn't in paren's teaching subset."
    "(re-seq x y)" "`re-seq` isn't in paren's teaching subset."
    "(iterate inc 0)" "`iterate` isn't in paren's teaching subset (lazy sequences are on paren's Next list)."
    "@a" "`deref` isn't in paren's teaching subset (the subset has no mutable state)."
    "#{1 2}" "Sets (#{…}) aren't in paren's teaching subset."
    "#\"a+\"" "Regular expressions (#\"…\") aren't in paren's teaching subset."
    "(str/join [1 2])" "Namespaced symbols such as `str/join` aren't in paren's teaching subset."
    "'x" "Quoted symbols ('x) aren't in paren's teaching subset; quote data such as '(1 2 3) instead."
    "(let [[a b] [1 2]] a)"
    "Destructuring ([a b]) in `let` isn't in paren's teaching subset; bind a single name instead."
    "(fn [{:keys [a]}] a)"
    "Destructuring ({:keys [a]}) in parameters isn't in paren's teaching subset; bind a single name instead."
    "(defn f ([x] x) ([x y] y))"
    "Multi-arity functions (`defn` with several parameter lists) aren't in paren's teaching subset."}))

(defn are-messages [m]
  (doseq [[src msg] m]
    (let [{:keys [kind message]} (refusal src)]
      (is (= :unsupported kind) src)
      (is (= msg message) src))))

(deftest a-program-may-define-a-name-paren-lacks
  (is (= 2 (:result (s/run "(defn second [xs] (first (rest xs))) (second [1 2 3])")))))

(deftest malformed-special-forms
  (doseq [[src msg] {"(if)" "`if` needs a test and a then-branch, like (if test then else)."
                     "(if 1 2 3 4)" "`if` takes a test, a then-branch and an optional else-branch, but got 4 parts."
                     "(let [x] x)" "`let` bindings come in pairs: a name, then its value."
                     "(let x 1)" "`let` needs a vector of bindings, like (let [x 1] (* x 2))."
                     "(cond true)" "`cond` needs test/result pairs, but it has an odd number of forms."
                     "(def)" "`def` needs a name first, like (def x 1)."
                     "(def x)" "`def` needs a value too, like (def x 1)."
                     "(defn f x)" "`defn f` needs a parameter vector, like (defn f [x] …)."
                     "(fn [1] 1)" "Parameters must be names, but found 1."
                     "(fn [x &] x)" "`&` in a parameter list must be followed by exactly one name."
                     "(quote 1 2)" "`quote` takes exactly one form."
                     "(map if [1])" "`if` is a special form, so it only works at the start of a list, like (if …)."}]
    (let [t (s/run src)]
      (is (= :syntax (:kind t)) src)
      (is (= msg (:message t)) src))))
