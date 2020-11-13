(ns glimt.core
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [re-frame.core :as f]
            [statecharts.core :as fsm]
            [statecharts.integrations.re-frame :as fsm.rf]))

(def config-schema [:and
                    [:fn {:error/message "Should contain either path or on-success, and not both"}
                     (fn [{:keys [path on-success]}]
                       (->> [path on-success]
                            (filter identity)
                            count
                            (= 1)))]
                    [:map
                     [:id simple-keyword?]
                     [:max-retries {:optional true
                                    :default  0} :int]
                     [:retry-delay {:optional      true
                                    :default       2000}
                      [:or
                       [:fn {:error/message "Should be a function of the number of retries"} fn?]
                       :int]]
                     [:on-success {:optional true} vector?]
                     [:on-loading {:optional true} vector?]
                     [:on-error {:optional true} vector?]
                     [:on-failure {:optional true} vector?]
                     [:path {:optional true} vector?]
                     [:http-xhrio :map]]])

(defn fsm? [fsm]
  (m/validate config-schema fsm))

(defn update-retries [state & _]
  (update state :retries inc))

(defn reset-retries [state & _]
  (assoc state :retries 0))

(defn more-retries? [max-retries {:keys [retries]} _]
  (< retries max-retries))

(defn store-error [state event]
  (assoc state :error (:data event)))

(defn http-fsm [{:keys [id init-event transition-event max-retries retry-delay on-loading on-error on-failure] :as config}]
  (let [retry-delay (if (fn? retry-delay)
                      (comp retry-delay :retries)
                      retry-delay)]
    {:id           id
     :initial      ::loading
     :states       {::loading {:entry (fn [state event]
                                        (f/dispatch [::load config])
                                        (when on-loading
                                          (f/dispatch (vec (concat on-loading [state event transition-event])))))
                               :on    {::error   ::error
                                       ::success ::loaded}}
                    ::error   {:initial (if (< 0 max-retries)
                                          ::retrying
                                          ::halted)
                               :entry   (fn [state event]
                                          (let [assign (fsm/assign store-error)]
                                            (when on-error
                                              (f/dispatch (vec (concat on-error [state event transition-event]))))
                                            (assign state event)))
                               :states  {::retrying {:initial ::waiting
                                                     :entry   (fsm/assign reset-retries)
                                                     :states  {::loading {:entry [(fsm/assign update-retries)
                                                                                  #(f/dispatch [::load config])]
                                                                          :on    {::error   [{:guard  (partial more-retries? max-retries)
                                                                                              :target ::waiting}
                                                                                             [:> ::error ::halted]]
                                                                                  ::success [:> ::loaded]}}
                                                               ::waiting {:after [{:delay  retry-delay
                                                                                   :target ::loading}]}}}
                                         ::halted   {:entry (fn [state event]
                                                              (when on-failure
                                                                (f/dispatch (vec (concat on-failure [state event transition-event])))))}}}
                    ::loaded  {}}
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
    (when-let [errors (m/explain config-schema config)]
      (throw (ex-info "Bad HTTP config" {:humanized (me/humanize errors)
                                         :data      errors})))
    (let [init-event       (ns-key id "init")
          transition-event (ns-key id "transition")]
      (-> (m/decode config-schema config mt/default-value-transformer)
          (merge {:init-event       init-event
                  :transition-event transition-event})
          http-fsm
          fsm/machine
          fsm.rf/integrate)
      (f/dispatch [init-event]))))

(f/reg-event-fx ::restart
  (fn [_ [_ id]]
    (let [init-event (ns-key id "init")]
      {:dispatch [init-event]})))

(f/reg-event-fx ::start
  ;; Starts the interceptor for the given fsm.
  (fn [_ [_ fsm]]
    {::start fsm}))

(f/reg-sub ::state
  (fn [db [_ id]]
    (get-in db [::fsm-state id])))