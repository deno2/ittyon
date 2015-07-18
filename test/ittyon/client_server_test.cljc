(ns ittyon.client-server-test
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  (:require #?(:clj  [clojure.test :refer :all]
               :cljs [cemerick.cljs.test :as t :refer-macros [is deftest testing done]])
            #?(:clj  [clojure.core.async :as a :refer [go <! >! <!! >!!]]
               :cljs [cljs.core.async :as a :refer [<! >!]])
            #?(:clj  [intentions.core :refer [defconduct]]
               :cljs [intentions.core :refer-macros [defconduct]])
            [clojure.set :as set]
            [ittyon.core :as i]
            [ittyon.client :as client]
            [ittyon.server :as server]
            [chord.channels :refer [bidi-ch]]))

(i/derive ::name      ::i/aspect ::i/singular)
(i/derive ::email     ::i/aspect ::i/singular)
(i/derive ::clock     ::i/aspect ::i/singular)
(i/derive ::selected? ::i/aspect ::i/singular)

(def entity (i/uuid))

(def init-state
  (i/state [[entity ::i/live? true (i/time)]
            [entity ::name "alice" (i/time)]
            [entity ::email "alice@example.com" (i/time)]
            [entity ::clock 0 (i/time)]]))

(defn connect-client! [server]
  (let [a-ch   (a/chan)
        b-ch   (a/chan)
        client (client/connect! (bidi-ch a-ch b-ch))]
    (server/accept! server (bidi-ch b-ch a-ch))
    client))

#?(:clj
   (deftest test-async
     (let [server (server/server init-state)
           client (<!! (connect-client! server))]

       (testing "identity of client"
         (i/uuid? (:id client)))
       
       (testing "initial state transferred to client"
         (is (= (-> client :state deref :snapshot keys set)
                (-> server :state deref :snapshot keys set))))

       (testing "connected client stored in state"
         (let [facts (-> server :state deref :snapshot keys set)]
           (is (contains? facts [(:id client) ::i/live? true]))
           (is (contains? facts [(:id client) ::client/connected? true]))))

       (testing "client events relayed to server"
         (client/transact! client [[:assert entity ::name "bob"]
                                   [:assert entity ::email "bob@example.com"]])
         (Thread/sleep 25)
         (is (= (-> client :state deref :snapshot keys set)
                (-> server :state deref :snapshot keys set)
                #{[(:id client) ::i/live? true]
                  [(:id client) ::client/connected? true]
                  [entity ::i/live? true]
                  [entity ::name "bob"]
                  [entity ::email "bob@example.com"]
                  [entity ::clock 0]})))

       (testing "local events not relayed to server"
         (client/transact! client [^:local [:assert entity ::selected? true]])
         (Thread/sleep 25)
         (is (= (set/difference
                 (-> client :state deref :snapshot keys set)
                 (-> server :state deref :snapshot keys set))
                #{[entity ::selected? true]})))

       (testing "manual transition times"
         (reset! (:time-offset client) 0)
         (client/transact! client [[:assert entity ::clock 1 1234567890]])
         (Thread/sleep 25)
         (is (= (-> client :state deref :snapshot (get [entity ::clock 1]) first)
                1234567890)))))

   :cljs
   (deftest ^:async test-async
     (go (let [server (server/server init-state)
               client (<! (connect-client! server))]

           (testing "identity of client"
             (i/uuid? (:id client)))

           (testing "initial state transferred to client"
             (is (= (-> client :state deref :snapshot keys set)
                    (-> server :state deref :snapshot keys set))))

           (testing "connected client stored in state"
             (let [facts (-> server :state deref :snapshot keys set)]
               (is (contains? facts [(:id client) ::i/live? true]))
               (is (contains? facts [(:id client) ::client/connected? true]))))

           (testing "client events relayed to server"
             (client/transact! client [[:assert entity ::name "bob"]
                                       [:assert entity ::email "bob@example.com"]])
             (<! (a/timeout 25))
             (is (= (-> client :state deref :snapshot keys set)
                    (-> server :state deref :snapshot keys set)
                    #{[(:id client) ::i/live? true]
                      [(:id client) ::client/connected? true]
                      [entity ::i/live? true]
                      [entity ::name "bob"]
                      [entity ::email "bob@example.com"]
                      [entity ::clock 0]})))

           (testing "local events not relayed to server"
             ;; go loops in cljs.core.async erroneously eat bare metadata.
             ;; Until this bug is fixed, we need to use with-meta instead.
             (client/transact! client [(with-meta
                                         [:assert entity ::selected? true]
                                         {:local true})])
             (<! (a/timeout 25))
             (is (= (set/difference
                     (-> client :state deref :snapshot keys set)
                     (-> server :state deref :snapshot keys set))
                    #{[entity ::selected? true]})))

           (testing "manual transition times"
             (reset! (:time-offset client) 0)
             (client/transact! client [[:assert entity ::clock 1 1234567890]])
             (<! (a/timeout 25))
             (is (= (-> client :state deref :snapshot (get [entity ::clock 1]) first)
                    1234567890)))

           (done)))))

#?(:clj
   (deftest test-ping
     (testing "client"
       (let [ch     (a/chan)
             client (client/connect! ch)]
         (>!! ch [:init {:id (i/uuid) :time (i/time) :reset #{}}])
         (let [time-offset (:time-offset (<!! client))]
           (is (<= 0 @time-offset 25))

           (>!! ch [:time (+ (i/time) 1000)])
           (Thread/sleep 25)
           (is (<= -1000 @time-offset -975))

           (>!! ch [:time (- (i/time) 1000)])
           (Thread/sleep 25)
           (is (<= 1000 @time-offset 1025)))))

     (testing "server"
       (let [server (-> (server/server init-state)
                        (assoc :ping-delay 25))
             ch     (a/chan)]
         (server/accept! server ch)
         (is (= (first (<!! ch)) :init))
         (Thread/sleep 50)
         (is (= (first (<!! ch)) :time))
         (Thread/sleep 50)
         (is (= (first (<!! ch)) :time)))))

   :cljs
   (deftest ^:async test-ping
     (go (testing "client"
           (let [ch     (a/chan)
                 client (client/connect! ch)]
             (>! ch [:init {:id (i/uuid) :time (i/time) :reset #{}}])
             (let [time-offset (:time-offset (<! client))]
               (is (<= 0 @time-offset 25))

               (>! ch [:time (+ (i/time) 1000)])
               (<! (a/timeout 25))
               (is (<= -1000 @time-offset -975))

               (>! ch [:time (- (i/time) 1000)])
               (<! (a/timeout 25))
               (is (<= 1000 @time-offset 1025)))))

         (testing "server"
           (let [server (-> (server/server init-state)
                            (assoc :ping-delay 25))
                 ch     (a/chan)]
             (server/accept! server ch)
             (is (= (first (<! ch)) :init))
             (<! (a/timeout 50))
             (is (= (first (<! ch)) :time))
             (<! (a/timeout 50))
             (is (= (first (<! ch)) :time))
             (done))))))

#?(:clj
   (deftest test-invalid
     (let [server      (server/server init-state)
           client      (<!! (connect-client! server))
           dead-entity (i/uuid)]

       (testing "invalid transitions from client"
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"Invalid transition for state"
              (client/transact! client [[:assert dead-entity ::name "invalid"]]))))

       (testing "invalid transitions from server"
         (let [invalid-entity (i/uuid)]
           (client/send! client [:transact [[:assert dead-entity ::name "invalid"]]])
           (Thread/sleep 25)
           (let [facts (-> server :state deref :snapshot keys set)]
             (is (not (contains? facts [dead-entity ::name "invalid"]))))))))

   :cljs
   (deftest ^:async test-invalid
     (go (let [server      (server/server init-state)
               client      (<! (connect-client! server))
               dead-entity (i/uuid)]
           (testing "invalid transitions from client"
             (let [invalid-entity (i/uuid)]
               (is (thrown-with-msg?
                    cljs.core.ExceptionInfo #"Invalid transition for state"
                    (client/transact! client [[:assert invalid-entity ::name "invalid"]])))))

           (testing "invalid transitions"
             (let [invalid-entity (i/uuid)]
               (client/send! client [:transact [[:assert invalid-entity ::name "invalid"]]])
               (<! (a/timeout 25))
               (let [facts (-> server :state deref :snapshot keys set)]
                 (is (not (contains? facts [invalid-entity ::name "invalid"]))))))

           (done)))))

(i/derive ::dice ::i/aspect ::i/singular)
(i/derive ::roll ::i/aspect ::i/singular)

(defconduct i/-react [:assert ::dice] [s [_ e a v t]]
  [^:impure [:assert e ::roll (rand-int v) t]])

(defn- get-value [s e a]
  (-> s (get-in [:index :eavt e a]) keys first))

#?(:clj
   (deftest test-impure
     (let [server  (server/server init-state)
           client1 (<!! (connect-client! server))
           client2 (<!! (connect-client! server))
           entity  (i/uuid)]
       (client/transact! client1 [[:assert entity ::i/live? true]
                                  [:assert entity ::dice 1000]])
       (Thread/sleep 100)
       (is (integer? (-> server :state deref (get-value entity ::roll))))
       (is (not= 1000 (-> server :state deref (get-value entity ::roll))))
       (is (= (-> server :state deref (get-value entity ::roll))
              (-> client1 :state deref (get-value entity ::roll))
              (-> client2 :state deref (get-value entity ::roll))))))

   :cljs
   (deftest ^:async test-impure
     (go (let [server  (server/server init-state)
               client1 (<! (connect-client! server))
               client2 (<! (connect-client! server))
               entity  (i/uuid)]
           (client/transact! client1 [[:assert entity ::i/live? true]
                                      [:assert entity ::dice 1000]])
           (<! (a/timeout 25))
           (is (integer? (-> server :state deref (get-value entity ::roll))))
           (is (not= 1000 (-> server :state deref (get-value entity ::roll))))
           (is (= (-> server :state deref (get-value entity ::roll))
                  (-> client1 :state deref (get-value entity ::roll))
                  (-> client2 :state deref (get-value entity ::roll))))
           (done)))))
