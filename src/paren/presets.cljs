(ns paren.presets
  "The five example programs offered on the page.")

(def presets
  [{:id "fib"
    :label "recursive fib"
    :src "(defn fib [n]
  (if (< n 2)
    n
    (+ (fib (- n 1))
       (fib (- n 2)))))

(fib 4)"}
   {:id "reduce"
    :label "reduce + map"
    :src "(reduce + (map inc [1 2 3]))"}
   {:id "let"
    :label "let scoping"
    :src "(let [x 1
      y (+ x 1)]
  (let [x 10]
    (+ x y)))"}
   {:id "closure"
    :label "a closure"
    :src "(defn make-adder [n]
  (fn [x] (+ x n)))

(def add5 (make-adder 5))

(add5 10)"}
   {:id "cond"
    :label "cond"
    :src "(defn sign [x]
  (cond
    (< x 0) :negative
    (= x 0) :zero
    :else   :positive))

(map sign [-5 0 7])"}])

(defn by-id [id]
  (first (filter #(= id (:id %)) presets)))
