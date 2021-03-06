(ns ittyon.core
  "An in-memory immutable database designed to manage game state."
  (:refer-clojure :exclude [time derive update uuid])
  #?(:clj  (:require [clojure.core :as core]
                     [medley.core :refer [dissoc-in map-keys map-vals]]
                     [intentions.core :as int :refer [defintent defconduct]])
     :cljs (:require [cljs.core :as core]
                     [medley.core :refer [dissoc-in map-keys map-vals]]
                     [intentions.core :as int :refer-macros [defintent defconduct]])))

(defn time
  "Return the current system time in milliseconds."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn uuid?
  "Return true if x is a UUID."
  [x]
  #?(:clj  (instance? java.util.UUID x)
     :cljs (instance? cljs.core.UUID x)))

(defn uuid
  "Return a random UUID."
  []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn periodically
  "Periodically evaluate a zero-argument function a specified number of times a
  second."
  [freq func]
  (let [ideal (/ 1000 freq)
        stop? (atom false)]
    #?(:clj
       (future
         (loop []
           (when-not @stop?
             (let [start (time)]
               (func)
               (let [duration (- (time) start)]
                 (Thread/sleep (max 0 (- ideal duration)))
                 (recur))))))
       :cljs
       (letfn [(callback []
                 (when-not @stop?
                   (let [start (time)]
                     (func)
                     (let [duration (- (time) start)]
                       (js/setTimeout callback (max 0 (- ideal duration)))))))]
         (callback)))
    #(reset! stop? true)))

(defn derive
  "Operates the same as clojure.core/derive, except that multiple parent
  arguments may be specified."
  [h? tag & parents]
  (if (map? h?)
    (reduce #(core/derive %1 tag %2) h? parents)
    (doseq [p (cons tag parents)] (core/derive h? p))))

(defn- transition-key [state [o e a v t]] [o a])

(derive :ittyon/indexed :ittyon/eavt :ittyon/aevt :ittyon/avet)
(derive :ittyon/aspect  :ittyon/indexed)
(derive :ittyon/live?   :ittyon/indexed)

(defintent -index
  "An intention to update the supplied index with a transition. Expected to
  return a function that updates the index. Dispatches off the transition op
  and the aspect. Composes the returned functions."
  {:arglists '([state transition])}
  :dispatch transition-key
  :combine comp)

(defconduct -index :default [_ _] identity)

(defconduct -index [:assert :ittyon/eavt] [_ [_ e a v t]]
  #(assoc-in % [:eavt e a v] t))

(defconduct -index [:assert :ittyon/aevt] [_ [_ e a v t]]
  #(assoc-in % [:aevt a e v] t))

(defconduct -index [:assert :ittyon/avet] [_ [_ e a v t]]
  #(assoc-in % [:avet a v e] t))

(defconduct -index [:revoke :ittyon/eavt] [_ [_ e a v _]]
  #(dissoc-in % [:eavt e a v]))

(defconduct -index [:revoke :ittyon/aevt] [_ [_ e a v _]]
  #(dissoc-in % [:aevt a e v]))

(defconduct -index [:revoke :ittyon/avet] [_ [_ e a v _]]
  #(dissoc-in % [:avet a v e]))

(defn index
  "Update the state's indexes for a single transition. Extend using the
  [[-index]] intention."
  [state transition]
  (core/update state :index (-index state transition)))

(defn update-snapshot
  "Update the state's snapshot for a single transition."
  {:arglist '([state transition])}
  [{n :count :as state} [o e a v t]]
  (case o
    :assert (core/update state :snapshot assoc [e a v] [t n])
    :revoke (core/update state :snapshot dissoc [e a v])))

(defn update
  "Update a state with a single transition and return the new state. Combines
  [[update-snapshot]] with [[index]]."
  [state transition]
  (-> state
      (update-snapshot transition)
      (core/update :log conj transition)
      (core/update :count inc)
      (index transition)))

(defn prune
  "Reset the log of a state back to an empty list."
  [state]
  (assoc state :log ()))

(defn state
  "Return a new state, either empty or prepopulated with a collection of facts."
  ([]
   {:snapshot {}, :log (), :index {}, :count 0})
  ([facts]
   (reduce update (state) (for [[e a v t] facts] [:assert e a v t]))))

(defn facts
  "Return an ordered collection of facts held by the supplied state."
  [state]
  (->> (:snapshot state)
       (sort-by (fn [[_ [_ n]]] n))
       (map (fn [[[e a v] [t _]]] [e a v t]))))

(defintent -valid?
  "An intention to determine whether a transition is valid for a particular
  state. Dispatches off the transition op and the aspect. Combines results of
  inherited keys with logical AND."
  {:arglists '([state transition])}
  :dispatch transition-key
  :combine #(and %1 %2))

(defconduct -valid? :default [_ _] false)

(defconduct -valid? [:assert :ittyon/live?] [_ [o e a v t]]
  (and (uuid? e) (integer? t) (true? v)))

(defconduct -valid? [:assert :ittyon/aspect] [s [o e a v t]]
  (and (uuid? e) (some? v) (integer? t) (-> s :index :eavt (get e) :ittyon/live?)))

(defconduct -valid? [:assert :ittyon/ref] [s [o e a v t]]
  (-> s :index :eavt (get v)))

(defconduct -valid? [:revoke :ittyon/live?] [_ [o e a v t]]
  (integer? t))

(defconduct -valid? [:revoke :ittyon/aspect] [_ [o e a v t]]
  (integer? t))

(defintent -react
  "An intention that returns an ordered collection of reaction transitions,
  given a state and a valid transition that is going to be applied to that
  state. Dispatches off the transition op and the aspect. Concatenates the
  results of inherited keys."
  {:arglists '([state transition])}
  :dispatch transition-key
  :combine concat)

(defconduct -react :default [_ _] '())

(defn- revoke-aspects [s e t]
  (for [[a vt] (-> s :index :eavt (get e))
        [v _]  vt
        :when  (not= a :ittyon/live?)]
    [:revoke e a v t]))

(defn- revoke-refs [s v t]
  (for [a     (cons :ittyon/ref (descendants :ittyon/ref))
        [e _] (-> s :index :avet (get a) (get v))
        :when e]
    [:revoke e a v t]))

(defconduct -react [:revoke :ittyon/live?] [s [o e a v t]]
  (concat (revoke-aspects s e t)
          (revoke-refs s e t)))

(defconduct -react [:revoke :ittyon/aspect] [s [o e a v t]]
  (if (nil? v)
    (for [v* (-> s :index :eavt (get e) (get a) keys)]
      [:revoke e a v* t])))

(defconduct -react [:assert :ittyon/singular] [s [o e a v t]]
  (for [v* (keys (-> s :index :eavt (get e) (get a))) :when (not= v v*)]
    [:revoke e a v* t]))

(defn transition?
  "Return true if x is a transition. A transition is a vector of five values:
  operation, entity, aspect, value and time. These are commonly abbreviated to
  `[o e a v t]`. The operation, o, is either `:assert` or `:revoke`. The aspect,
  a, must be a keyword."
  [x]
  (and (sequential? x)
       (= (count x) 5)
       (let [[o e a v t] x]
         (and (#{:assert :revoke} o) (keyword? a)))))

(defn valid?
  "Return true if the transition is a valid transition for the given state.
  Extend using the [[-valid?]] intention."
  [state transition]
  (and (transition? transition) (-valid? state transition)))

(defn react
  "Return a seq of reaction transitions, or nil, for a given state and
  transition. Extend using the [[-react]] intention."
  [state transition]
  (seq (-react state transition)))

(defn commit
  "Takes a state and a transition, and if the transition is valid, returns
  a new state with the transition and any reactions applied. If the transition
  is not valid for the state, an ExceptionInfo is thrown with the failing
  transition and state as keys. A transducer for transforming the reactions
  of the transition may be supplied as an optional third argument."
  ([state transition]
   (commit state transition identity))
  ([state transition xform]
   (if (valid? state transition)
     (transduce xform
                (completing #(commit %1 %2 xform))
                (update state transition)
                (react state transition))
     (throw (ex-info "Invalid transition for state"
                     {:state state
                      :transition transition})))))

(defn- tick-aspects [conducts]
  (into #{}
        (comp (filter vector?) (keep (fn [[o a]] (if (= o :tick) a))))
        (keys conducts)))

(defn- tick-reactor [state time aspects]
  (fn [[[e a _] _]] (if (aspects a) (react state [:tick e a time]))))

(defn tick
  "Update a state by moving the clock forward to a new time. This may generate
  reactions that alter the state."
  [state time]
  (let [aspects (tick-aspects (int/conducts -react))
        xform   (mapcat (tick-reactor state time aspects))]
    (-> (transduce xform (completing commit) state (:snapshot state))
        (assoc :last-tick time))))

(defn transact
  "Takes a state and an ordered collection of transitions, and returns a new
  state with the transitions committed in order. Also adds a `:last-transact`
  key to the resulting state that contains the committed transitions. If any
  of the transitions fail, an ExceptionInfo is thrown. A transducer for
  transforming the reactions of each transition may be supplied as an optional
  third argument."
  ([state transitions]
   (transact state transitions identity))
  ([state transitions xform]
   (-> (reduce #(commit %1 %2 xform) (prune state) transitions)
       (assoc :last-transact transitions))))
