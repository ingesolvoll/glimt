(ns glimt.core
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.util :as mu]
   [re-frame.core :as f]
   [re-statecharts.core :as rs]
   [statecharts.core :as sc]))

(def core-map-schema
  [:map
   [:state-path {:default [:>]}
    [:vector :keyword]]
   [:failure-state {:optional true} [:vector :keyword]]
   [:success-state {:optional true} [:vector :keyword]]
   [:id :keyword]
   [:max-retries {:default 0} :int]
   [:retry-delay {:default 2000}
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
                                (mu/required-keys [:state-path])
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

(defn fsm-body [{:keys [state-path max-retries retry-delay on-loading on-error on-failure failure-state success-state] :as config}]
  (let [retry-delay (if (fn? retry-delay)
                      (comp retry-delay :retries)
                      retry-delay)]
    {:initial ::loading
     :states  {::loading {:entry (fn [state event]
                                   (f/dispatch [::load config])
                                   (when on-loading
                                     (f/dispatch (vec (concat on-loading [state event])))))
                          :on    {::error   ::error
                                  ::success (or success-state ::loaded)}}
               ::error   {:initial ::retrying
                          :entry   (fn [state event]
                                     (let [assign (sc/assign store-error)]
                                       (when on-error
                                         (f/dispatch (vec (concat on-error [state event]))))
                                       (assign state event)))
                          :states  {::retrying {:always  [{:guard  (fn [] (< max-retries 1))
                                                           :target (or failure-state ::halted)}]
                                                :initial ::waiting
                                                :entry   (sc/assign reset-retries)
                                                :states  {::loading {:entry [(sc/assign update-retries)
                                                                             #(f/dispatch [::load config])]
                                                                     :on    {::error   [{:guard  (partial more-retries? max-retries)
                                                                                         :target ::waiting}
                                                                                        (or failure-state (vec (concat state-path [::error ::halted])))]
                                                                             ::success (or success-state (vec (concat state-path [::loaded])))}}
                                                          ::waiting {:after [{:delay  retry-delay
                                                                              :target ::loading}]}}}
                                    ::halted   {:entry (fn [state event]
                                                         (when on-failure
                                                           (f/dispatch (vec (concat on-failure [state event])))))}}}
               ::loaded  {}}}))

(defn embedded-fsm [config]
  (let [config-with-defaults (m/decode embedded-config-schema config mt/default-value-transformer)]
    (when-let [errors (m/explain embedded-config-schema config-with-defaults)]
      (throw (ex-info "Invalid embedded HTTP FSM"
                      {:humanized (me/humanize errors)
                       :data      errors})))
    (fsm-body config-with-defaults)))

(defn fsm [{:keys [id] :as config}]
  (merge (fsm-body config)
         {:id id}))

(f/reg-event-fx ::on-failure
  (fn [_ [_ {:keys [id]} error]]
    {:dispatch [:transition-fsm id ::error error]}))

(f/reg-event-fx ::on-success
  (fn [{db :db} [_ {:keys [id on-success path]} data]]
    (merge
     (when path
       {:db (assoc-in db path data)})
     {:dispatch-n [[:transition-fsm id ::success]
                   (when on-success (conj on-success data))]})))

(f/reg-event-fx ::load
  (fn [_ [_ {:keys [http-xhrio] :as config}]]
    {:http-xhrio (merge http-xhrio
                        {:on-failure [::on-failure config]
                         :on-success [::on-success config]})}))

(f/reg-fx ::start
  (fn [config]
    (let [config-with-defaults (m/decode full-config-schema config mt/default-value-transformer)]
      (when-let [errors (m/explain full-config-schema config-with-defaults)]
        (throw (ex-info "Invalid HTTP FSM"
                        {:humanized (me/humanize errors)
                         :data      errors})))
      (-> (fsm config-with-defaults)
          sc/machine
          rs/integrate))))

(f/reg-event-fx ::start
  ;; Starts the interceptor for the given fsm.
  (fn [_ [_ fsm]]
    {::start fsm}))
