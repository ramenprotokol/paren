(ns paren.stepper
  "The small-step evaluator. A program is a tree of nodes (see
  paren.syntax). Each step finds the redex, the one sub-expression whose
  turn it is to reduce under Clojure's left-to-right, call-by-value order,
  and replaces it with what it reduces to: a value, a chosen branch, or a
  function body wrapped in a :scope that carries the call's bindings.

  Environments are chains of frames:
    {:fid id :label \"fib\" :vars [[sym value] …] :self [name closure] :parent frame}
  A nil frame means the global environment, which lives in the state."
  (:require [clojure.string :as str]
            [paren.builtins :as b]
            [paren.reader :as reader]
            [paren.syntax :as syntax]
            [paren.values :as v]))

(def max-steps
  "Evaluation stops after this many steps, so a runaway program can't
  freeze the page."
  5000)

(def max-depth
  "Most function calls that may be in progress inside one another."
  100)

(def max-nodes
  "Largest the expression tree may grow, counted in boxes."
  2500)

(defn val-node [x] {:t :val :v x})

(defn- call-node [f args]
  {:t :call :f (val-node f) :args (mapv val-node args)})

;; ---------------------------------------------------------------------------
;; Tree helpers

(defn children [n]
  (case (:t n)
    (:val :sym :quote) nil
    :call (cons (:f n) (:args n))
    :if (remove nil? [(:test n) (:then n) (:else n)])
    :cond (mapcat identity (:clauses n))
    (:and :or :vec :seq) (:items n)
    :map (mapcat identity (:entries n))
    :let (concat (map second (:bindings n)) (:body n))
    (:do :scope :fn) (:body n)
    :def [(:init n)]
    :pick [(:test n)]
    :reduce [(:f n) (:acc n)]
    :top (:forms n)))

(defn size
  "Number of boxes in a tree."
  [n]
  (reduce + 1 (map size (children n))))

(defn- remove-at [xs i]
  (into (subvec xs 0 i) (subvec xs (inc i))))

;; ---------------------------------------------------------------------------
;; Environments

(defn- find-var [vars s]
  (loop [i (dec (count vars))]
    (when (>= i 0)
      (let [[k x] (nth vars i)]
        (if (= k s) [x] (recur (dec i)))))))

(defn lookup-local
  "Finds s in the local frames. Returns {:v :fid :where} or nil."
  [env s]
  (loop [e env]
    (when e
      (if-let [[x] (find-var (:vars e) s)]
        {:v x :fid (:fid e) :where :local}
        (if (= s (first (:self e)))
          {:v (second (:self e)) :fid (:fid e) :where :self}
          (recur (:parent e)))))))

(defn resolve-sym
  "Local frames first, then globals (def), then built-ins."
  [st env s]
  (or (lookup-local env s)
      (when (contains? (:globals st) s)
        {:v (get (:globals st) s) :fid :global :where :global})
      (when-let [bv (b/lookup s)]
        {:v bv :where :builtin})))

(defn- builtin-sym? [st env s]
  (and (nil? (lookup-local env s))
       (not (contains? (:globals st) s))
       (some? (b/lookup s))))

(defn- let-env [n env]
  {:fid (:fid n)
   :label "let"
   :vars (mapv (fn [[s x]] [s (:v x)]) (take (:n-bound n) (:bindings n)))
   :parent env})

(defn- scope-env [n]
  {:fid (:fid n) :label (:label n) :vars (:frame n) :self (:self n) :parent (:env n)})

;; ---------------------------------------------------------------------------
;; Values inside the tree

(defn evaluated?
  "Is node n finished? A symbol naming an unshadowed built-in (`+`, `inc`)
  counts as finished, so built-ins don't cost a lookup step each."
  [st env n]
  (case (:t n)
    :val true
    :sym (builtin-sym? st env (:s n))
    (:vec :seq) (every? #(evaluated? st env %) (:items n))
    :map (every? (fn [[k x]] (and (evaluated? st env k) (evaluated? st env x))) (:entries n))
    false))

(defn value-of
  "The value of a finished node."
  [n]
  (case (:t n)
    :val (:v n)
    :sym (b/lookup (:s n))
    :vec (mapv value-of (:items n))
    :seq (apply list (map value-of (:items n)))
    :map (into {} (map (fn [[k x]] [(value-of k) (value-of x)])) (:entries n))))

;; ---------------------------------------------------------------------------
;; Finding the redex

(declare find-redex)

(defn- redex-in [st nodes env path depth]
  (loop [i 0]
    (when (< i (count nodes))
      (or (find-redex st (nth nodes i) env (conj path i) depth)
          (recur (inc i))))))

(defn- body-redex [st body env path depth]
  (or (when-let [x (first body)]
        (find-redex st x env (conj path :body 0) depth))
      {:path path :env env :depth depth}))

(defn find-redex
  "The next redex in n, as {:path :env :depth}, or nil if n is finished.
  depth counts the function calls in progress around it."
  [st n env path depth]
  (let [here {:path path :env env :depth depth}]
    (case (:t n)
      :val nil
      :sym (when-not (evaluated? st env n) here)
      (:vec :seq) (redex-in st (:items n) env (conj path :items) depth)
      :map (let [es (:entries n)]
             (loop [i 0]
               (when (< i (count es))
                 (let [[k x] (nth es i)]
                   (or (find-redex st k env (conj path :entries i 0) depth)
                       (find-redex st x env (conj path :entries i 1) depth)
                       (recur (inc i)))))))
      :call (let [f (:f n)]
              (or (if (= :sym (:t f))
                    ;; A named function is looked up when it is called; an
                    ;; unknown name fails first, as in Clojure.
                    (when-not (resolve-sym st env (:s f))
                      {:path (conj path :f) :env env :depth depth})
                    (find-redex st f env (conj path :f) depth))
                  (redex-in st (:args n) env (conj path :args) depth)
                  here))
      :if (or (find-redex st (:test n) env (conj path :test) depth) here)
      :cond (if-let [[t] (first (:clauses n))]
              (or (find-redex st t env (conj path :clauses 0 0) depth) here)
              here)
      (:and :or) (if-let [x (first (:items n))]
                   (or (find-redex st x env (conj path :items 0) depth) here)
                   here)
      :let (let [env' (let-env n env)
                 k (:n-bound n)
                 bs (:bindings n)]
             (if (< k (count bs))
               (or (find-redex st (second (nth bs k)) env' (conj path :bindings k 1) depth)
                   (assoc here :env env'))
               (body-redex st (:body n) env' path depth)))
      :do (body-redex st (:body n) env path depth)
      :scope (body-redex st (:body n) (scope-env n) path (inc depth))
      :fn here
      :def (if (= :defn (:kind n))
             here
             (or (find-redex st (:init n) env (conj path :init) depth) here))
      :quote here
      :pick (or (find-redex st (:test n) env (conj path :test) depth) here)
      :reduce (or (find-redex st (:acc n) env (conj path :acc) depth) here)
      :top (redex-in st (:forms n) nil (conj path :forms) depth))))

;; ---------------------------------------------------------------------------
;; Captions

(defn show
  "A value as it appears in a caption."
  [x]
  (v/show x 48))

(defn- test-desc [x]
  (cond
    (boolean? x) (str x)
    (nil? x) "nil, which is falsy"
    :else (str (show x) ", which is truthy")))

(defn- args-desc [args]
  (if (empty? args)
    "no arguments"
    (str/join " " (map #(v/show % 24) args))))

(defn- bindings-desc [vars]
  (if (empty? vars)
    "no arguments"
    (str/join ", " (map (fn [[s x]] (str s " = " (v/show x 24))) vars))))

(defn fn-label
  "A short name for a function value: inc, fib, or ‹fn [x]›."
  [f]
  (cond
    (v/builtin? f) (:name f)
    (and (v/closure? f) (v/fn-name f)) (str (v/fn-name f))
    (v/closure? f) (v/closure-label f)
    :else (show f)))

(defn- colls-desc [colls]
  (str/join " and " (map show colls)))

(defn unresolved-message [s]
  (str "Unable to resolve symbol `" s "`: nothing by that name is defined here."))

;; ---------------------------------------------------------------------------
;; Free variables, for the "remembering n = 5" caption on closures

(defn- free-syms
  "Symbols used in the nodes that are not bound inside them."
  [nodes bound]
  (reduce
   (fn [acc n]
     (case (:t n)
       :sym (if (bound (:s n)) acc (conj acc (:s n)))
       :fn (into acc (free-syms (:body n) (cond-> (into bound (:params n))
                                            (:rest n) (conj (:rest n))
                                            (:name n) (conj (:name n)))))
       :let (let [[acc b] (reduce (fn [[acc b] [s init]]
                                    [(into acc (free-syms [init] b)) (conj b s)])
                                  [acc bound] (:bindings n))]
              (into acc (free-syms (:body n) b)))
       (into acc (free-syms (children n) bound))))
   []
   nodes))

(defn- remembered [n env]
  (let [own (cond-> (set (:params n)) (:rest n) (conj (:rest n)) (:name n) (conj (:name n)))]
    (->> (free-syms (:body n) own)
         distinct
         (keep (fn [s] (when-let [r (lookup-local env s)] [s (:v r)]))))))

;; ---------------------------------------------------------------------------
;; Reductions

(defn- depth-message [fname depth]
  (str "Stopped at the recursion cap: calling `" fname "` here would put "
       (inc depth) " calls in progress at once, and paren allows " max-depth
       ". Check that the recursion reaches its base case."))

(defn- coll! [fname x]
  (when-not (b/seqable-value? x)
    (b/fail (str "`" fname "` needs a collection, but got " (show x) " (" (v/type-name x) ")"))))

(defn- start-reduce [f acc x more]
  {:node {:t :reduce :f (val-node f) :acc (call-node f [acc x]) :rest (vec more)}
   :caption (str "`reduce` calls `" (fn-label f) "` on " (show acc) " and " (show x))})

(defn- unfold
  "map, filter and reduce turn into the calls they will make, so every call
  is a visible step. (Clojure's versions are lazy or chunked; these are eager.)"
  [b args]
  (b/check-arity! b args)
  (case (:name b)
    "map"
    (let [[f & colls] args]
      (doseq [c colls] (coll! "map" c))
      (let [rows (apply map vector colls)
            items (mapv #(call-node f %) rows)]
        {:node {:t :seq :from "map" :items items}
         :caption (if (empty? items)
                    "`map` over an empty collection → ()"
                    (str "`map` calls `" (fn-label f) "` on each item of " (colls-desc colls)))}))

    "filter"
    (let [[p c] args]
      (coll! "filter" c)
      (let [items (mapv (fn [x] {:t :pick :test (call-node p [x]) :item x}) c)]
        {:node {:t :seq :from "filter" :items items}
         :caption (if (empty? items)
                    "`filter` over an empty collection → ()"
                    (str "`filter` tests each item of " (show c) " with `" (fn-label p) "`"))}))

    "reduce"
    (let [[f a c] args
          [init? init coll] (if (= 3 (count args)) [true a c] [false nil a])
          _ (coll! "reduce" coll)
          xs (vec coll)]
      (cond
        (and init? (empty? xs))
        {:node (val-node init) :caption (str "`reduce` over an empty collection → its start value, " (show init))}
        init? (start-reduce f init (first xs) (rest xs))
        (empty? xs)
        {:node {:t :call :f (val-node f) :args []}
         :caption (str "`reduce` over an empty collection calls `" (fn-label f) "` with no arguments")}
        (= 1 (count xs))
        {:node (val-node (first xs)) :caption (str "`reduce` over a single item → " (show (first xs)))}
        :else (start-reduce f (first xs) (second xs) (drop 2 xs))))))

(defn- enter
  "Calling a closure: the call becomes the function's body, in a :scope that
  binds the parameters and remembers the closure's environment."
  [st c fname args depth]
  (let [ps (v/fn-params c)
        r (v/fn-rest c)
        n (count args)
        np (count ps)]
    (when (or (< n np) (and (nil? r) (> n np)))
      (b/fail (b/arity-message fname n np (when-not r np))))
    (when (>= depth max-depth)
      (throw (ex-info (depth-message fname depth) {:paren/cap :depth})))
    (let [vars (cond-> (mapv vector ps args)
                 r (conj [r (when (> n np) (apply list (drop np args)))]))]
      {:st (update st :next-fid inc)
       :node {:t :scope
              :fid (:next-fid st)
              :label fname
              :frame vars
              :self (when (v/fn-name c) [(v/fn-name c) c])
              :env (v/fn-env c)
              :body (v/fn-body c)}
       :caption (str "enter `" fname "` with " (bindings-desc vars))})))

(defn- lookup-in [coll k default]
  {:node (val-node (get coll k default))
   :caption (str "look up " (show k) " in " (show coll) " → " (show (get coll k default)))})

(defn- apply-call [st n env depth]
  (let [f (:f n)
        called-as (when (= :sym (:t f)) (:s f))
        fv (if called-as (:v (resolve-sym st env called-as)) (value-of f))
        fname (cond
                called-as (str called-as)
                (and (v/closure? fv) (nil? (v/fn-name fv))) "fn"
                :else (fn-label fv))
        args (mapv value-of (:args n))
        nargs (count args)]
    (cond
      (v/builtin? fv)
      (if (b/stepped? fv)
        (unfold fv args)
        (let [x (b/call fv args)]
          {:node (val-node x)
           :caption (str "apply `" fname "` to " (args-desc args) " → " (show x))}))

      (v/closure? fv) (enter st fv fname args depth)

      (or (keyword? fv) (b/plain-map? fv))
      (if (<= 1 nargs 2)
        (if (keyword? fv)
          (lookup-in (first args) fv (second args))
          (lookup-in fv (first args) (second args)))
        (b/fail (b/arity-message fname nargs 1 2)))

      (vector? fv)
      (let [[i] args]
        (cond
          (not= 1 nargs) (b/fail (b/arity-message fname nargs 1 1))
          (not (and (integer? i) (< -1 i (count fv))))
          (b/fail (str "Index " (show i) " is out of bounds for " (show fv)))
          :else {:node (val-node (nth fv i))
                 :caption (str "take item " i " of " (show fv) " → " (show (nth fv i)))}))

      :else
      (b/fail (str (show fv) " is " (v/type-name fv) ", not a function, so it can't be called")))))

(defn- body-contract [n who]
  (let [body (:body n)]
    (cond
      (empty? body)
      {:node (val-node nil) :caption (str who " has an empty body → nil")}
      (next body)
      {:node (update n :body #(vec (rest %)))
       :caption (str who " discards " (show (value-of (first body))) " and moves on")}
      :else
      (let [x (value-of (first body))]
        {:node (val-node x) :caption (str who " returns " (show x))}))))

(defn- make-closure [n env]
  (v/->Closure (:name n) (:params n) (:rest n) (:body n) env))

(defn- contract
  "Reduces the redex n. Returns {:node replacement} or {:remove true}, with
  a :caption and optionally a new :st. Throws on errors."
  [st n env depth]
  (case (:t n)
    :sym
    (let [s (:s n)]
      (if-let [r (resolve-sym st env s)]
        {:node (val-node (:v r))
         :caption (str "look up `" s "` → " (show (:v r)))
         :lookup (assoc r :sym s)}
        (b/fail (unresolved-message s))))

    :call (apply-call st n env depth)

    :if
    (let [t (value-of (:test n))]
      (cond
        (v/truthy? t) {:node (:then n) :caption (str "`if` test is " (test-desc t) " → take the then-branch")}
        (:else n) {:node (:else n) :caption (str "`if` test is " (test-desc t) " → take the else-branch")}
        :else {:node (val-node nil)
               :caption (str "`if` test is " (test-desc t) "; there is no else-branch → nil")}))

    :cond
    (if-let [[t e] (first (:clauses n))]
      (let [x (value-of t)]
        (if (v/truthy? x)
          {:node e :caption (str "`cond` test is " (test-desc x) " → take this branch")}
          {:node (update n :clauses #(vec (rest %)))
           :caption (str "`cond` test is " (test-desc x) " → try the next clause")}))
      {:node (val-node nil) :caption "`cond` has no clause left → nil"})

    :and
    (if-let [x (first (:items n))]
      (let [xv (value-of x)]
        (cond
          (not (next (:items n))) {:node (val-node xv) :caption (str "`and` returns its last value, " (show xv))}
          (not (v/truthy? xv)) {:node (val-node xv) :caption (str "`and` stops at " (show xv) ", which is falsy")}
          :else {:node (update n :items #(vec (rest %)))
                 :caption (str "`and`: " (show xv) " is truthy → keep going")}))
      {:node (val-node true) :caption "`and` with nothing to test → true"})

    :or
    (if-let [x (first (:items n))]
      (let [xv (value-of x)]
        (cond
          (not (next (:items n))) {:node (val-node xv) :caption (str "`or` returns its last value, " (show xv))}
          (v/truthy? xv) {:node (val-node xv) :caption (str "`or` stops at " (show xv) ", which is truthy")}
          :else {:node (update n :items #(vec (rest %)))
                 :caption (str "`or`: " (show xv) " is falsy → keep going")}))
      {:node (val-node nil) :caption "`or` with nothing to test → nil"})

    :let
    (let [k (:n-bound n)
          bs (:bindings n)]
      (if (< k (count bs))
        (let [[s init] (nth bs k)
              x (value-of init)]
          {:st (if (:fid n) st (update st :next-fid inc))
           :node (-> n
                     (assoc :fid (or (:fid n) (:next-fid st)) :n-bound (inc k))
                     (assoc-in [:bindings k 1] (val-node x)))
           :caption (str "bind `" s "` = " (show x))})
        (body-contract n "`let`")))

    :do (body-contract n "`do`")

    :scope (body-contract n (str "`" (:label n) "`"))

    :fn
    (let [c (make-closure n env)
          mem (remembered n env)]
      {:node (val-node c)
       :caption (str "make the function `" (v/closure-label c) "`"
                     (when (seq mem)
                       (str ", remembering " (bindings-desc mem))))})

    :def
    (let [s (:name n)
          defn? (= :defn (:kind n))
          x (if defn? (make-closure (:init n) env) (value-of (:init n)))]
      {:st (-> st
               (assoc-in [:globals s] x)
               (update :gorder #(if (some #{s} %) % (conj % s))))
       :node (val-node (v/->DefVar s))
       :caption (if defn?
                  (str "define `" s "` as a function")
                  (str "define `" s "` = " (show x)))})

    :quote
    {:node (val-node (:v n)) :caption (str "`quote` returns " (show (:v n)) " without evaluating it")}

    :pick
    (let [t (value-of (:test n))]
      (if (v/truthy? t)
        {:node (val-node (:item n)) :caption (str "`filter` keeps " (show (:item n)))}
        {:remove true :caption (str "`filter` drops " (show (:item n)))}))

    :reduce
    (let [acc (value-of (:acc n))
          f (value-of (:f n))
          xs (:rest n)]
      (if (empty? xs)
        {:node (val-node acc) :caption (str "`reduce` has nothing left → " (show acc))}
        (start-reduce f acc (first xs) (rest xs))))))

;; ---------------------------------------------------------------------------
;; Stepping and tracing

(defn init-state [tree]
  {:tree tree :globals {} :gorder [] :next-fid 1 :size (size tree)})

(defn size-message []
  (str "Stopped at the size cap: the expression grew past " (reader/format-count max-nodes)
       " boxes, which is more than paren can show step by step."))

(defn step
  "One small step from state st.
  Returns nil when evaluation is finished; otherwise the redex r
  ({:path :env :depth}) together with either
    {:state st' :caption c :anchor path :lookup …}   or
    {:error message :cap kind}."
  [st]
  (when-let [r (find-redex st (:tree st) nil [] 0)]
    (let [path (:path r)
          n (get-in (:tree st) path)]
      (try
        (let [out (contract st n (:env r) (:depth r))
              st1 (or (:st out) st)
              removed? (:remove out)
              tree (if removed?
                     (update-in (:tree st1) (pop path) remove-at (peek path))
                     (assoc-in (:tree st1) path (:node out)))
              new-size (+ (:size st) (- (if removed? 0 (size (:node out))) (size n)))]
          (if (> new-size max-nodes)
            {:redex r :error (size-message) :cap :size}
            {:redex r
             :caption (:caption out)
             :lookup (:lookup out)
             :removed? (boolean removed?)
             :anchor (if removed? (pop (pop path)) path)
             :state (assoc st1 :tree tree :size new-size)}))
        (catch :default e
          (if-let [d (ex-data e)]
            {:redex r :error (ex-message e) :cap (:paren/cap d)}
            {:redex r :error (str "paren hit an internal error: " (.-message e)) :cap :internal}))))))

(defn result-value [st]
  (value-of (peek (:forms (:tree st)))))

(defn step-cap-message []
  (str "Stopped at the step cap: " (reader/format-count max-steps)
       " steps ran without finishing. The program may never finish, or it is too big to watch one step at a time."))

(defn trace
  "Runs a parsed program to the end (or a cap) and records every state.
  frames[i] is the state before step i+1, with that step's redex and
  caption. The last frame is the finished (or stopped) state."
  [tree]
  (loop [st (init-state tree)
         frames (transient [])
         n 0]
    (let [r (step st)
          base {:st st :index n}]
      (cond
        (nil? r)
        (let [x (result-value st)]
          {:status :done
           :result x
           :frames (persistent! (conj! frames (assoc base :caption (str "done → " (show x)) :status :done)))})

        (>= n max-steps)
        {:status :step-cap
         :message (step-cap-message)
         :frames (persistent! (conj! frames (assoc base
                                                   :redex (:path (:redex r)) :env (:env (:redex r))
                                                   :caption (step-cap-message) :status :step-cap)))}

        (:error r)
        (let [status (case (:cap r) :depth :depth-cap :size :size-cap :error)]
          {:status status
           :message (:error r)
           :frames (persistent! (conj! frames (assoc base
                                                     :redex (:path (:redex r)) :env (:env (:redex r))
                                                     :caption (:error r) :status status)))})

        :else
        (recur (:state r)
               (conj! frames (assoc base
                                    :redex (:path (:redex r))
                                    :env (:env (:redex r))
                                    :caption (:caption r)
                                    :lookup (:lookup r)
                                    :anchor (:anchor r)
                                    :removed? (:removed? r)))
               (inc n))))))

(defn run
  "Source text -> a trace, or {:status :error :stage :read/:parse :message}."
  [src]
  (let [{:keys [forms error]} (reader/read-program src)]
    (if error
      {:status :error :stage :read :kind (:kind error) :message (:message error) :frames []}
      (let [{:keys [tree error]} (syntax/parse-program forms)]
        (if error
          {:status :error :stage :parse :kind (:kind error) :message (:message error) :frames []}
          (trace tree))))))

(defn captions
  "Just the captions of a trace, for tests and the golden sequences."
  [t]
  (mapv :caption (:frames t)))
