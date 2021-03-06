(ns web.decks
  (:require
    ;; external
    [clojure.string :as str]
    [monger.collection :as mc]
    [monger.result :refer [acknowledged?]]
    [crypto.password.pbkdf2 :as pbkdf2]
    ;; internal
    [web.mongodb :refer [object-id]]
    [web.nrdb :as nrdb]
    [web.utils :refer [response]]
    [web.ws :as ws]
    [jinteki.cards :refer [all-cards]]
    [jinteki.utils :refer [slugify]]
    [jinteki.validator :refer [calculate-deck-status]])
  (:import org.bson.types.ObjectId))


(defn decks-handler
  [{db :system/db
    {:keys [username]} :user}]
  (if username
    (response 200 (mc/find-maps db "decks" {:username username}))
    (response 200 (mc/find-maps db "decks" {:username "__demo__"}))))

(defn update-card
  [card]
  (update card :card @all-cards))

(defn update-deck
  [deck]
  (-> deck
      (update :cards #(map update-card %))
      (update :identity #(@all-cards (:title %)))))

(defn prepare-deck-for-db
  [deck username status deck-hash]
  (-> deck
      (update :cards (fn [cards] (mapv #(select-keys % [:qty :card :id :art]) cards)))
      (assoc :username username
             :status status
             :hash deck-hash)))

(defn make-salt
  [deck-name]
  (let [salt (byte-array (map byte (slugify deck-name)))]
    (if (empty? salt) (byte-array (map byte "default-salt")) salt)))

(defn hash-deck
  [deck]
  (let [check-deck (-> deck
                       (update :cards #(map update-card %))
                       (update :identity #(get @all-cards (:title %))))
        id (-> deck :identity :title)
        sorted-cards (sort-by #(:code (:card %)) (:cards check-deck))
        decklist (str/join (for [entry sorted-cards]
                           (str (:qty entry) (:code (:card entry)))))
        deckstr (str id decklist)
        salt (make-salt (:name deck))]
    (last (str/split (pbkdf2/encrypt deckstr 100000 "HMAC-SHA1" salt) #"\$"))))

(defn decks-create-handler
  [{db :system/db
    {username :username} :user
    deck                 :body}]
  (if (and username deck)
    (let [updated-deck (update-deck deck)
          status (calculate-deck-status updated-deck)
          deck-hash (hash-deck updated-deck)
          deck (prepare-deck-for-db deck username status deck-hash)]
      (response 200 (mc/insert-and-return db "decks" deck)))
    (response 401 {:message "Unauthorized"})))

(defn decks-save-handler
  [{db :system/db
    {username :username} :user
    deck                 :body}]
  (if (and username deck)
    (let [updated-deck (update-deck deck)
          status (calculate-deck-status updated-deck)
          deck-hash (hash-deck updated-deck)
          deck (prepare-deck-for-db deck username status deck-hash)]
      (if-let [deck-id (:_id deck)]
        (if (:identity deck)
          (do (mc/update db "decks"
                         {:_id (object-id deck-id) :username username}
                         {"$set" (dissoc deck :_id)})
              (response 200 {:message "OK" :_id (object-id deck-id)}))
          (response 409 {:message "Deck is missing identity"}))
        (response 409 {:message "Deck is missing _id"})))
    (response 401 {:message "Unauthorized"})))

(defn decks-delete-handler
  [{db :system/db
    {username :username} :user
    {id :id}             :params}]
  (try
    (if (and username id)
      (if (acknowledged? (mc/remove db "decks" {:_id (object-id id) :username username}))
        (response 200 {:message "Deleted"})
        (response 403 {:message "Forbidden"}))
      (response 401 {:message "Unauthorized"}))
    (catch Exception ex
      ;; Deleting a deck that was never saved throws an exception
      (response 409 {:message "Unknown deck id"}))))

(defmethod ws/-msg-handler :decks/import
  [{{{db :system/db
      username :username} :user} :ring-req
    client-id :client-id
    {:keys [input]} :?data}]
  (try
    (let [deck (nrdb/download-public-decklist db input)]
      (if (every? #(contains? deck %) [:name :identity :cards])
        (let [db-deck (assoc deck
                             :_id (ObjectId.)
                             :date (java.util.Date.)
                             :format "standard")
              updated-deck (update-deck db-deck)
              status (calculate-deck-status updated-deck)
              deck-hash (hash-deck updated-deck)
              deck (prepare-deck-for-db db-deck username status deck-hash)]
          (mc/insert db "decks" deck)
          (ws/broadcast-to! [client-id] :decks/import-success "Imported"))
        (ws/broadcast-to! [client-id] :decks/import-failure "Failed to parse imported deck.")))
    (catch Exception ex
      (ws/broadcast-to! [client-id] :decks/import-failure "Failed to import deck."))))
