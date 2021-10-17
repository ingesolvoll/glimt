(ns glimt.core
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [re-frame.core :as f]
            [statecharts.core :as sc]
            [statecharts.integrations.re-frame :as sc.rf]))

(def core-map-schema
  [:map
   [:transition-event {:optional true} :keyword]
   [:state-path {:optional true
                 :default  [:>]}
    [:vector :keyword]]
   [:error-state {:optional true} [:vector :keyword]]
   [:success-state {:optional true} [:vector :keyword]]
   [:id simple-keyword?]
   [:max-retries {:optional true
                  :default  0} :int]
   [:retry-delay {:optional true
                  :default  2000}
    [:or
     [:fn {:error/message "Should be a function of the number of retries"} fn?]
     :int]]
   [:on-success {:optional true} vector?]
   [:on-loading {:optional true} vector?]
   [:on-error {:optional true} vector?]
   [:on-failure {:optional true} vector?]
   [:path {:optional true} vector?]
   [:http-xhrio :map]])

(defn config-schema [map-schema]
  [:and
   [:fn {:error/message "Should contain either path or on-success, and not both"}
    (fn [{:keys [path on-success]}]
      (->> [path on-success]
           (filter identity)
           count
           (= 1)))]
   map-schema])

(def embedded-config-schema (-> core-map-schema
                                (mu/required-keys [:state-path :transition-event])
                                (mu/dissoc :id)
                                (config-schema)
                                (m/schema)))

(def full-config-schema (-> core-map-schema
                            (config-schema)
                            (m/schema)))

(defn fsm? [fsm]
  (m/validate full-config-schema fsm))

(defn update-retries [state & _]
  (update state :retries inc))

(defn reset-retries [state & _]
  (assoc state :retries 0))

(defn more-retries? [max-retries {:keys [retries]} _]
  (< retries max-retries))

(defn store-error [state event]
  (assoc state :error (:data event)))

(defn embedded-fsm [{:keys [transition-event state-path max-retries retry-delay on-loading on-error on-failure error-state success-state] :as config}]
  (when-let [errors (m/explain embedded-config-schema config)]
    (throw (ex-info "Invalid embedded HTTP FSM"
                    {:humanized (me/humanize errors)
                     :data      errors})))
  (let [retry-delay (if (fn? retry-delay)
                      (comp retry-delay :retries)
                      retry-delay)]
    {:initial ::loading
     :states  {::loading {:entry (fn [state event]
                                   (f/dispatch [::load config])
                                   (when on-loading
                                     (f/dispatch (vec (concat on-loading [state event transition-event])))))
                          :on    {::error   ::error
                                  ::success (or success-state ::loaded)}}
               ::error   {:initial ::retrying
                          :entry   (fn [state event]
                                     (let [assign (sc/assign store-error)]
                                       (when on-error
                                         (f/dispatch (vec (concat on-error [state event transition-event]))))
                                       (assign state event)))
                          :states  {::retrying {:always  [{:guard  (fn [] (< max-retries 1))
                                                           :target (or error-state ::halted)}]
                                                :initial ::waiting
                                                :entry   (sc/assign reset-retries)
                                                :states  {::loading {:entry [(sc/assign update-retries)
                                                                             #(f/dispatch [::load config])]
                                                                     :on    {::error   [{:guard  (partial more-retries? max-retries)
                                                                                         :target ::waiting}
                                                                                        (or error-state (vec (concat state-path [::error ::halted])))]
                                                                             ::success (or success-state (vec (concat state-path [::loaded])))}}
                                                          ::waiting {:after [{:delay  retry-delay
                                                                              :target ::loading}]}}}
                                    ::halted   {:entry (fn [state event]
                                                         (when on-failure
                                                           (f/dispatch (vec (concat on-failure [state event transition-event])))))}}}
               ::loaded  {}}}))

(defn fsm [{:keys [id init-event transition-event] :as config}]
  (merge (embedded-fsm config)
         {:id           id
          :integrations {:re-frame {:path             (f/path [::fsm-state id])
                                    :initialize-event init-event
                                    :transition-event transition-event}}}))

(defn ns-key [id v]
  (keyword (name id) v))

(f/reg-event-fx ::on-failure
                (fn [_ [_ transition-event error]]
                  {:dispatch [transition-event ::error error]}))

(f/reg-event-fx ::on-success
                (fn [{db :db} [_ {:keys [transition-event on-success path]} data]]
                  (merge
                   (when path
                     {:db (assoc-in db path data)})
                   {:dispatch-n [[transition-event ::success]
                                 (when on-success (conj on-success data))]})))

(f/reg-event-fx ::load
  (fn [_ [_ {:keys [transition-event http-xhrio] :as config}]]
    {:http-xhrio (merge http-xhrio
                        {:on-failure [::on-failure transition-event]
                         :on-success [::on-success config]})}))

(f/reg-fx ::start
  (fn [{:keys [id] :as config}]
    (when-let [errors (m/explain full-config-schema config)]
      (throw (ex-info "Invalid HTTP FSM"
                      {:humanized (me/humanize errors)
                       :data      errors})))
    (let [init-event       (ns-key id "init")
          transition-event (ns-key id "transition")]
      (-> {:init-event       init-event
           :transition-event transition-event}
          (merge (m/decode full-config-schema config mt/default-value-transformer))
          fsm
          sc/machine
          sc.rf/integrate)
      (f/dispatch [init-event]))))

(f/reg-event-fx ::restart
  (fn [_ [_ id]]
    (let [init-event (ns-key id "init")]
      {:dispatch [init-event]})))

(f/reg-event-fx ::start
  ;; Starts the interceptor for the given fsm.
  (fn [_ [_ fsm]]
    {::start fsm}))

(defn ->seq [x]
  (if (coll? x)
    x
    [x]))

(f/reg-sub ::state
  (fn [db [_ id]]
    (->seq (get-in db [::fsm-state id :_state]))))

(f/reg-sub ::state-full
  (fn [db [_ id]]
    (get-in db [::fsm-state id])))
