(ns paren.builtins
  "paren's built-in functions. Each one checks its arguments the way Clojure
  would complain, and throws `(fail ...)` with a plain-English message.

  `map`, `filter` and `reduce` are listed here, but the stepper runs them
  itself, so that each call they make is a visible step."
  (:require [paren.reader :as reader]
            [paren.values :as v]))

(def max-string
  "Longest string `str` may build. Doubling a string in a loop would
  otherwise exhaust memory long before the step cap."
  10000)

(def max-range
  "Most numbers `range` may produce (paren's range is eager, not lazy)."
  1000)

(def max-value
  "Most items one value may hold, counting everything nested inside it (a
  part that appears twice counts twice). Doubling a vector in a loop shares
  structure, so it costs little memory, but comparing, hashing or walking
  the result would take longer than the age of the universe."
  10000)

(defn fail
  "Throws an evaluation error with a message meant for the learner."
  [msg]
  (throw (ex-info msg {:paren/error true})))

(defn plain-map?
  "A Clojure map value (records such as DefVar are maps too, but not data)."
  [x]
  (and (map? x) (not (record? x))))

(defn seqable-value?
  [x]
  (or (nil? x) (string? x) (vector? x) (plain-map? x) (seq? x) (list? x)))

(defn- s [x] (v/show x 40))

(defonce ^:private sizes (js/WeakMap.))

(defn value-size
  "Items in x counted as a tree, x itself included: shared parts count
  each time they appear. Stops counting just past max-value, and remembers
  each collection's answer, so sizing a value built from sized parts is
  cheap."
  [x]
  (if (and (coll? x) (not (record? x)))
    (if-let [n (.get sizes x)]
      n
      (let [n (loop [xs (seq x) acc 1]
                (if (and xs (<= acc max-value))
                  (recur (next xs) (+ acc (value-size (first xs))))
                  acc))]
        (.set sizes x n)
        n))
    1))

(defn too-big?
  "True when x holds more than max-value items."
  [x]
  (> (value-size x) max-value))

(defn value-cap-message
  "`who` is a phrase such as \"This vector\"."
  [who]
  (str who " would hold more than " (reader/format-count max-value) " items, counting everything nested inside it,"
       " which is paren's cap for a single value"))

(defn- nums! [fname args]
  (doseq [a args]
    (when-not (number? a)
      (fail (str "`" fname "` works on numbers, but got " (s a) " (" (v/type-name a) ")"))))
  args)

(defn- int! [fname x]
  (nums! fname [x])
  (when-not (integer? x)
    (fail (str "`" fname "` needs a whole number, but got " (s x))))
  x)

(defn- coll! [fname x]
  (when-not (seqable-value? x)
    (fail (str "`" fname "` needs a collection, but got " (s x) " (" (v/type-name x) ")")))
  x)

(defn- compare-chain [op]
  (fn [fname args]
    (nums! fname args)
    (boolean (apply op args))))

(defn- str-part [x]
  (cond (nil? x) ""
        (string? x) x
        :else (v/show x (inc max-string))))

(defn- build-str
  "`str`, stopping as soon as the result passes the string cap."
  [parts]
  (let [buf #js []]
    (loop [xs (seq parts) len 0]
      (cond
        (> len max-string)
        (fail (str "`str` built a string longer than " max-string
                   " characters, which is paren's cap for strings"))
        xs (let [p (str-part (first xs))]
             (.push buf p)
             (recur (next xs) (+ len (count p))))
        :else (.join buf "")))))

(defn- finite! [fname args]
  (doseq [a args]
    (when-not (js/isFinite a)
      (fail (str "`" fname "` needs finite numbers, but got " (s a)))))
  args)

(defn- bounded-range
  "Clojure's (range start end step), made eagerly, failing past max-range
  numbers. Whole numbers are counted up front, like cljs.core's
  IntegerRange; other numbers are added up one step at a time, like its
  Range, which could otherwise loop forever once `step` is too small to
  change `start`."
  [start end step]
  (let [cap #(fail (str "`range` here would make more than " max-range
                        " numbers, which is paren's cap for range"))
        more? (if (pos? step) #(< % end) #(> % end))]
    (if (and (integer? start) (integer? end) (integer? step))
      (let [n (max 0 (js/Math.ceil (/ (- end start) step)))]
        (when (> n max-range) (cap))
        (apply list (map #(+ start (* % step)) (range n))))
      (loop [out (transient []) x start]
        (cond
          (not (more? x)) (apply list (persistent! out))
          (>= (count out) max-range) (cap)
          :else (recur (conj! out x) (+ x step)))))))

;; name -> [min-args max-args-or-nil impl]
;; impl is (fn [fname args] result); arity is checked before it runs.
(def table
  {"+" [0 nil (fn [f a] (apply + (nums! f a)))]
   "-" [1 nil (fn [f a] (apply - (nums! f a)))]
   "*" [0 nil (fn [f a] (apply * (nums! f a)))]
   "/" [1 nil (fn [f a] (apply / (nums! f a)))]
   "inc" [1 1 (fn [f [x]] (inc (first (nums! f [x]))))]
   "dec" [1 1 (fn [f [x]] (dec (first (nums! f [x]))))]
   "mod" [2 2 (fn [f [a b]] (nums! f [a b]) (mod a b))]
   "rem" [2 2 (fn [f [a b]] (nums! f [a b]) (rem a b))]
   "quot" [2 2 (fn [f [a b]] (nums! f [a b]) (quot a b))]
   "max" [1 nil (fn [f a] (apply max (nums! f a)))]
   "min" [1 nil (fn [f a] (apply min (nums! f a)))]
   "=" [1 nil (fn [_ a] (apply = a))]
   "not=" [1 nil (fn [_ a] (apply not= a))]
   "<" [1 nil (compare-chain <)]
   ">" [1 nil (compare-chain >)]
   "<=" [1 nil (compare-chain <=)]
   ">=" [1 nil (compare-chain >=)]
   "zero?" [1 1 (fn [f [x]] (nums! f [x]) (zero? x))]
   "pos?" [1 1 (fn [f [x]] (nums! f [x]) (pos? x))]
   "neg?" [1 1 (fn [f [x]] (nums! f [x]) (neg? x))]
   "even?" [1 1 (fn [f [x]] (even? (int! f x)))]
   "odd?" [1 1 (fn [f [x]] (odd? (int! f x)))]
   "nil?" [1 1 (fn [_ [x]] (nil? x))]
   "not" [1 1 (fn [_ [x]] (not (v/truthy? x)))]
   "empty?" [1 1 (fn [f [x]] (empty? (coll! f x)))]
   "count" [1 1 (fn [f [x]] (count (coll! f x)))]
   "first" [1 1 (fn [f [x]] (first (coll! f x)))]
   "rest" [1 1 (fn [f [x]] (apply list (rest (coll! f x))))]
   "cons" [2 2 (fn [f [x c]] (apply list x (coll! f c)))]
   "conj" [1 nil (fn [f [c & xs]]
                   (when-not (or (nil? c) (vector? c) (plain-map? c) (seq? c) (list? c))
                     (fail (str "`conj` needs a collection first, but got " (s c))))
                   (when (plain-map? c)
                     (doseq [x xs]
                       (when-not (and (vector? x) (= 2 (count x)))
                         (fail (str "`conj` onto a map needs [key value] pairs, but got " (s x))))))
                   (apply conj c xs))]
   "get" [2 3 (fn [_ [m k default]] (if (or (plain-map? m) (vector? m) (string? m) (nil? m))
                                      (get m k default)
                                      default))]
   "assoc" [3 nil (fn [f [m & kvs]]
                    (when (odd? (count kvs))
                      (fail "`assoc` needs keys and values in pairs"))
                    (cond
                      (or (nil? m) (plain-map? m)) (apply assoc m kvs)
                      (vector? m)
                      (reduce (fn [acc [k x]]
                                (if (and (integer? k) (<= 0 k (count acc)))
                                  (assoc acc k x)
                                  (fail (str "`assoc` on a vector needs an index from 0 to "
                                             (count acc) ", but got " (s k)))))
                              m (partition 2 kvs))
                      :else (fail (str "`" f "` needs a map or a vector, but got " (s m)))))]
   "nth" [2 3 (fn [f [c i & more]]
                (int! f i)
                (when-not (or (vector? c) (seq? c) (list? c) (string? c) (nil? c))
                  (fail (str "`nth` needs a vector or list, but got " (s c))))
                (cond
                  (and (<= 0 i) (< i (count c))) (nth c i)
                  (seq more) (first more)
                  :else (fail (str "Index " i " is out of bounds for " (s c)))))]
   "vector" [0 nil (fn [_ a] (vec a))]
   "list" [0 nil (fn [_ a] (apply list a))]
   "range" [1 3 (fn [f args]
                  (finite! f (nums! f args))
                  (let [[a b c] args
                        [start end step] (case (count args) 1 [0 a 1] 2 [a b 1] [a b c])]
                    (when (zero? step)
                      (fail "`range` with a step of 0 would never end"))
                    (bounded-range start end step)))]
   "str" [0 nil (fn [_ a] (build-str a))]
   ;; Run by the stepper so their calls are visible; arity is still checked here.
   "map" [2 nil nil]
   "filter" [2 2 nil]
   "reduce" [2 3 nil]})

(def builtins
  "The Builtin value for each name, one shared instance per name."
  (into {} (map (fn [k] [k (v/->Builtin k)])) (keys table)))

(defn lookup
  "The Builtin value named by symbol `sym`, or nil."
  [sym]
  (get builtins (str sym)))

(defn stepped?
  "True for the built-ins the stepper unfolds itself (map, filter, reduce)."
  [b]
  (nil? (get-in table [(:name b) 2])))

(defn plural [n word]
  (str n " " word (when (not= n 1) "s")))

(defn arity-message [fname n lo hi]
  (str "`" fname "` takes "
       (cond (= lo hi) (plural lo "argument")
             (nil? hi) (str "at least " (plural lo "argument"))
             :else (str lo " to " hi " arguments"))
       ", but got " n))

(defn check-arity! [b args]
  (let [[lo hi] (get table (:name b))
        n (count args)]
    (when (or (< n lo) (and hi (> n hi)))
      (fail (arity-message (:name b) n lo hi)))))

(defn call
  "Applies an ordinary built-in to argument values. Throws on bad arguments."
  [b args]
  (check-arity! b args)
  (let [[_ _ impl] (get table (:name b))]
    (impl (:name b) args)))

(def names (sort (keys table)))
