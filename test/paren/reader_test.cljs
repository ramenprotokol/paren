(ns paren.reader-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [paren.reader :as r]
            [paren.stepper :as s]))

(deftest reads-every-top-level-form
  (let [{:keys [forms error]} (r/read-program "(def x 1)\n; a comment\n(inc x) :k")]
    (is (nil? error))
    (is (= '[(def x 1) (inc x) :k] forms))))

(deftest code-is-data
  (testing "the reader returns ordinary Clojure data: lists, vectors, symbols"
    (let [[form] (:forms (r/read-program "(fn [x] [x {:a \"s\"}])"))]
      (is (seq? form))
      (is (= 'fn (first form)))
      (is (vector? (second form)))
      (is (= {:a "s"} (second (nth form 2)))))))

(deftest reader-error-path
  (testing "unbalanced parens give a reader error with a position"
    (let [{:keys [error]} (r/read-program "(+ 1 2))")]
      (is (= :read (:kind error)))
      (is (str/starts-with? (:message error) "Couldn't read that: "))
      (is (re-find #"Unmatched delimiter" (:message error)))
      (is (re-find #"line 1" (:message error)))))
  (testing "an unclosed list"
    (let [{:keys [error]} (r/read-program "(defn f [x]\n  (+ x 1)")]
      (is (= :read (:kind error)))
      (is (re-find #"EOF" (:message error)))))
  (testing "a bad token"
    (is (= :read (get-in (r/read-program "(+ 1 #)") [:error :kind]))))
  (testing "reader errors surface from run as :read errors with no frames"
    (let [t (s/run "(inc 1")]
      (is (= :error (:status t)))
      (is (= :read (:stage t)))
      (is (empty? (:frames t))))))

(deftest empty-and-oversized-input
  (is (= :empty (get-in (r/read-program "") [:error :kind])))
  (is (= :empty (get-in (r/read-program "   \n ") [:error :kind])))
  (is (= :empty (get-in (r/read-program "; only a comment") [:error :kind])))
  (let [big (apply str (repeat 2001 " "))
        {:keys [error]} (r/read-program (str big "1"))]
    (is (= :too-long (:kind error)))
    (is (re-find #"2,002 characters" (:message error)))
    (is (re-find #"up to 2,000" (:message error))))
  (testing "exactly the cap is fine"
    (is (nil? (:error (r/read-program (str (apply str (repeat 1999 " ")) "1")))))))

(deftest nesting-cap
  (is (= 3 (r/nesting "(a [b {c 1}])")))
  (is (= 1 (r/nesting "(str \"((((\" \\( ; ((((\n)")) "strings, char literals and comments don't count")
  (let [deep (str (apply str (repeat 51 "(")) (apply str (repeat 51 ")")))
        {:keys [error]} (r/read-program deep)]
    (is (= :too-deep (:kind error)))
    (is (= "That expression is nested 51 brackets deep; paren reads up to 50. Try a flatter expression."
           (:message error))))
  (testing "49 nested calls are read and run"
    (let [src (str (apply str (repeat 49 "(inc ")) "1" (apply str (repeat 49 ")")))]
      (is (= 50 (:result (s/run src)))))))

(deftest syntax-quote-is-named
  (let [{:keys [error]} (r/read-program "`(a b)")]
    (is (= :unsupported (:kind error)))
    (is (re-find #"syntax-quote" (:message error)))))

(deftest format-count
  (is (= "5" (r/format-count 5)))
  (is (= "2,000" (r/format-count 2000)))
  (is (= "1,234,567" (r/format-count 1234567))))
