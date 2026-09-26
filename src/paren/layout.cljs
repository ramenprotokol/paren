(ns paren.layout
  "Turns an expression tree into a display tree of paper cards, and decides
  line breaks like a Clojure pretty-printer: a card stays on one line when
  it fits the width it is given (in `ch`, the width of one monospace
  character), and otherwise breaks the way Clojure code is usually
  indented. Pure data in, pure data out; paren.app turns it into DOM."
  (:require [clojure.string :as str]
            [paren.stepper :as s]
            [paren.values :as v]))

(def pad
  "Width, in ch, that a card's padding and border add around its content."
  1.2)

(def max-atom
  "Longest printed value shown in full inside the tree; longer ones are cut
  with an ellipsis (the full text is in the tooltip)."
  60)

(def min-break
  "Cards this narrow (in ch) never break: (- n\n1) reads worse than a stage
  that scrolls sideways a little."
  14)

(def max-depth-shade
  "Cards deeper than this reuse the darkest shade."
  7)

;; ---------------------------------------------------------------------------
;; Building the display tree

(defn- flat-value?
  "True for nodes that print as a single value (a finished vector or list
  prints as [1 2 3], not as a card of cards)."
  [n]
  (case (:t n)
    :val true
    (:vec :seq) (every? flat-value? (:items n))
    :map (every? (fn [[k x]] (and (flat-value? k) (flat-value? x))) (:entries n))
    false))

(defn- value-class [x]
  (cond
    (number? x) "num"
    (string? x) "str"
    (keyword? x) "kw"
    (nil? x) "nil"
    (boolean? x) "bool"
    (v/builtin? x) "builtin"
    (v/closure? x) "fnval"
    (v/def-var? x) "var"
    :else "coll"))

(defn text-atom [text cls path]
  {:k :atom :text text :cls cls :path path :fw (count text)})

(defn- value-atom [x path]
  (let [full (v/show x 2000)
        text (v/show x max-atom)]
    (cond-> (text-atom text (str "val " (value-class x)) path)
      (not= full text) (assoc :title full))))

(defn- head-word [text] (text-atom text "head" nil))

(defn- flat-width [els]
  (+ (reduce + (map :fw els)) (max 0 (dec (count els)))))

(def tab-scale
  "Tab text is set at this fraction of the card's font size."
  0.74)

(defn tab-width
  "Width in ch (of the card's font) that a tab needs."
  [{:keys [name note]}]
  (+ 2 (* tab-scale (+ (count name) 1 (count note)))))

(defn- card
  [path depth cls open close head items style & {:as extra}]
  (let [head (vec head)
        items (vec items)
        content (+ pad (count open) (count close) (flat-width (into head items)))
        tab (:tab extra)]
    (merge {:k :card :path path :depth (min depth max-depth-shade)
            :cls (if tab (str cls " tabbed") cls)
            :open open :close close :head head :items items :style style
            :fw (if tab (max content (tab-width tab)) content)
            :min-ch (when tab (tab-width tab))}
           extra)))

(defn- group [open close items style]
  {:k :group :open open :close close :head [] :items (vec items) :style style
   :fw (+ (count open) (count close) (flat-width items))})

(defn- params-text [params rest]
  (str "[" (str/join " " (cond-> (mapv str params) rest (conj "&" (str rest)))) "]"))

(declare build)

(defn- build-all [nodes path depth]
  (vec (map-indexed (fn [i n] (build n (conj path i) depth)) nodes)))

(defn build
  "Expression node at `path` -> display element."
  [n path depth]
  (if (flat-value? n)
    (value-atom (s/value-of n) path)
    (let [d (inc depth)]
      (case (:t n)
        :sym (text-atom (str (:s n)) "sym" path)

        :call
        (card path depth "call" "(" ")"
              [(build (:f n) (conj path :f) d)]
              (build-all (:args n) (conj path :args) d)
              :align)

        :if
        (card path depth "form" "(" ")"
              [(head-word "if")]
              (cond-> [(build (:test n) (conj path :test) d)
                       (build (:then n) (conj path :then) d)]
                (:else n) (conj (build (:else n) (conj path :else) d)))
              :align)

        :cond
        (card path depth "form" "(" ")"
              [(head-word "cond")]
              (vec (mapcat (fn [i [t e]]
                             [(build t (conj path :clauses i 0) d)
                              (build e (conj path :clauses i 1) d)])
                           (range) (:clauses n)))
              :pairs
              ;; Test/result pairs read best one per line, as cond is written.
              :break? (> (count (:clauses n)) 1))

        (:and :or)
        (card path depth "form" "(" ")"
              [(head-word (name (:t n)))]
              (build-all (:items n) (conj path :items) d)
              :align)

        :do
        (card path depth "form" "(" ")" [(head-word "do")]
              (build-all (:body n) (conj path :body) d) :body)

        :let
        (let [k (:n-bound n)
              binds (group "[" "]"
                           (mapcat (fn [i [sym init]]
                                     [(text-atom (str sym) (if (< i k) "sym bound" "sym binding") nil)
                                      (build init (conj path :bindings i 1) d)])
                                   (range) (:bindings n))
                           :pairs)]
          (card path depth "form" "(" ")" [(head-word "let") binds]
                (build-all (:body n) (conj path :body) d) :body))

        :fn
        (card path depth "form fn" "(" ")"
              (cond-> [(head-word "fn")]
                (:name n) (conj (text-atom (str (:name n)) "sym name" nil))
                true (conj (text-atom (params-text (:params n) (:rest n)) "params" nil)))
              (build-all (:body n) (conj path :body) d)
              :body)

        :def
        (if (= :defn (:kind n))
          (let [f (:init n)]
            (card path depth "form" "(" ")"
                  [(head-word "defn") (text-atom (str (:name n)) "sym name" nil)
                   (text-atom (params-text (:params f) (:rest f)) "params" nil)]
                  (build-all (:body f) (conj path :init :body) d)
                  :body))
          (card path depth "form" "(" ")"
                [(head-word "def") (text-atom (str (:name n)) "sym name" nil)]
                [(build (:init n) (conj path :init) d)]
                :align))

        :quote (text-atom (str "'" (v/show (:v n) max-atom)) "val coll" path)

        :vec
        (card path depth "coll" "[" "]" []
              (build-all (:items n) (conj path :items) d) :stack)

        :map
        (card path depth "coll" "{" "}" []
              (vec (mapcat (fn [i [k x]]
                             [(build k (conj path :entries i 0) d)
                              (build x (conj path :entries i 1) d)])
                           (range) (:entries n)))
              :pairs)

        :seq
        (card path depth "seq" "(" ")" []
              (build-all (:items n) (conj path :items) d) :stack
              :tab {:name (:from n) :note "building a list"})

        :pick
        (card path depth "pick" "" ""
              [(value-atom (:item n) nil) (text-atom "if" "prose" nil)]
              [(build (:test n) (conj path :test) d)]
              :align)

        :reduce
        (card path depth "form" "(" ")"
              [(head-word "reduce")]
              [(build (:f n) (conj path :f) d)
               (build (:acc n) (conj path :acc) d)
               (value-atom (apply list (:rest n)) nil)]
              :align)

        :scope
        (card path depth "scope" "" "" []
              (build-all (:body n) (conj path :body) d) :plain
              :tab {:name (:label n)
                    :note (if (seq (:frame n))
                            (str/join ", " (map (fn [[sym x]] (str sym " = " (v/show x 24))) (:frame n)))
                            "no arguments")
                    :fid (:fid n)})

        :top
        {:k :top :items (build-all (:forms n) (conj path :forms) 0)}))))

;; ---------------------------------------------------------------------------
;; Fitting: choosing where lines break

(def ^:private br-open (fn [t] {:k :br :text t :cls "open" :fw (count t)}))
(def ^:private br-close (fn [t] {:k :br :text t :cls "close" :fw (count t)}))

(declare fit)

(defn- line [indent els] {:indent indent :els (vec els)})

(defn- flat-lines [el]
  (let [els (map #(fit % (:fw %)) (into (:head el) (:items el)))]
    [(line 0 (cond->> els
               (seq (:open el)) (cons (br-open (:open el)))
               true vec
               (seq (:close el)) (#(conj % (br-close (:close el))))))]))

(defn- close-last
  "Adds the closing bracket to the end of the last line. When that line ends
  with a card that is itself broken over several lines, the bracket drops to
  the bottom of it, so closers stack at the end like ))) in written Lisp."
  [lines close]
  (if (seq close)
    (let [last-el (peek (:els (peek lines)))
          tail (when (:broken? last-el) (if (= :card (:k last-el)) " tail" " tail-group"))]
      (update lines (dec (count lines)) update :els conj
              (update (br-close close) :cls str tail)))
    lines))

(defn- open-first [lines open]
  (if (seq open)
    (update lines 0 update :els #(into [(br-open open)] %))
    lines))

(defn- pair-lines [items indent avail]
  (loop [xs (seq items) out []]
    (if-not xs
      out
      (let [[a b] xs
            room (- avail indent)]
        (if (nil? b)
          (conj out (line indent [(fit a room)]))
          (let [one-line (+ (:fw a) 1 (:fw b))]
            (recur (nnext xs)
                   (if (<= one-line room)
                     (conj out (line indent [(fit a room) (fit b room)]))
                     (conj out
                           (line indent [(fit a room)])
                           (line (+ indent 2) [(fit b (- room 2))]))))))))))

(defn- broken-lines [el avail]
  (let [open (:open el)
        ol (count open)
        head (vec (:head el))
        items (:items el)
        hw (flat-width head)]
    (case (:style el)
      :align
      (let [indent (+ ol hw 1)]
        (if (and (seq items) (seq head) (<= (* 2 indent) (max avail 12)))
          ;; (f first-arg
          ;;    other-args)   ← aligned under the first argument
          (let [room (- avail indent)
                [a & more] items]
            (into [(line 0 (conj (mapv #(fit % (:fw %)) head) (fit a room)))]
                  (map #(line indent [(fit % room)]) more)))
          (into [(line 0 (mapv #(fit % (- avail ol)) head))]
                (map #(line (+ ol 1) [(fit % (- avail ol 1))]) items))))

      :body
      (into [(line 0 (mapv #(fit % (- avail ol hw)) head))]
            (map #(line 2 [(fit % (- avail 2))]) items))

      :pairs
      (if (seq head)
        (into [(line 0 (mapv #(fit % (:fw %)) head))] (pair-lines items 2 avail))
        (let [ls (pair-lines items ol avail)]
          (if (seq ls) (assoc-in ls [0 :indent] 0) [(line 0 [])])))

      :stack
      (let [room (- avail ol)
            ls (mapv #(line ol [(fit % room)]) items)]
        (if (seq ls) (assoc-in ls [0 :indent] 0) [(line 0 [])]))

      :plain
      (mapv #(line 0 [(fit % avail)]) items))))

(defn fit
  "Chooses line breaks for element `el` given `avail` ch of width."
  [el avail]
  (case (:k el)
    (:atom :br) el
    :top (assoc el :items (mapv #(fit % avail) (:items el)))
    (:card :group)
    (if (and (or (<= (:fw el) avail) (<= (:fw el) min-break)) (not (:break? el)))
      (assoc el :broken? false :lines (flat-lines el))
      (let [inner (- avail (if (= :card (:k el)) pad 0))]
        (assoc el :broken? true
               :lines (-> (broken-lines el inner)
                          (open-first (:open el))
                          (close-last (:close el))))))))

(defn display
  "Expression tree -> fitted display tree for a stage `width` ch wide."
  [tree width]
  (fit (build tree [] 0) width))

(defn widest-form
  "Flat width, in ch, of the widest top-level form in a tree."
  [tree]
  (reduce max 0 (map #(:fw (build % [] 0)) (:forms tree))))

;; ---------------------------------------------------------------------------
;; Environment cards

(defn active-fids
  "Frame ids of the calls and lets that enclose `path` in `tree`."
  [tree path]
  (loop [x tree p (seq path) acc #{}]
    (let [acc (if (and (map? x) (#{:scope :let} (:t x)) (:fid x)) (conj acc (:fid x)) acc)]
      (if p (recur (get x (first p)) (next p) acc) acc))))

(defn env-cards
  "The environment at a step, innermost first, ending with the global card.
  Frames no longer on the call path are marked :remembered (a closure is
  keeping them alive)."
  [frame]
  (let [st (:st frame)
        active (if (:redex frame) (active-fids (:tree st) (:redex frame)) #{})
        lookup (:lookup frame)
        locals (loop [e (:env frame) out []]
                 (if e
                   (recur (:parent e)
                          (if (or (seq (:vars e)) (:self e))
                            (conj out {:fid (:fid e)
                                       :label (:label e)
                                       :vars (:vars e)
                                       :remembered? (not (contains? active (:fid e)))})
                            out))
                   out))
        found (fn [fid sym] (and lookup (= (:sym lookup) sym) (= (:fid lookup) fid)))]
    (conj (mapv (fn [c]
                  (let [last-idx (into {} (map-indexed (fn [i [sym]] [sym i])) (:vars c))]
                    (assoc c :rows (vec (map-indexed
                                         (fn [i [sym x]]
                                           {:sym (str sym) :value (v/show x 40) :title (v/show x 400)
                                            :found? (and (found (:fid c) sym) (= i (last-idx sym)))
                                            :shadowed? (not= i (last-idx sym))})
                                         (:vars c))))))
                (filter #(seq (:vars %)) locals))
          {:fid :global
           :label "global"
           :rows (mapv (fn [sym]
                         (let [x (get (:globals st) sym)]
                           {:sym (str sym) :value (v/show x 40) :title (v/show x 400)
                            :found? (boolean (found :global sym))}))
                       (:gorder st))})))
