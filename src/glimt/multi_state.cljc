(ns glimt.multi-state
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [re-frame.core :as f]
            [statecharts.core :as sc]
            [statecharts.utils :as sc.utils]
            [statecharts.integrations.re-frame-multi :as sc.rf]))

(def request-map-schema
  (m/schema
   [:and
    [:map
     [:fsm/id :keyword]
     [:request/id vector?]
     [:http-xhrio :map]
     [:path {:optional true} vector?]
     [:on-success {:optional true} vector?]
     [:on-loading {:optional true} vector?]
     [:on-error {:optional true} vector?]
     [:on-failure {:optional true} vector?]]
    [:fn {:error/message "Should contain either path or on-success, and not both"}
     (fn [{:keys [path on-success]}]
       (->> [path on-success]
            (filter identity)
            count
            (= 1)))]]))

(def core-fsm-schema-def
  [:map
   [:max-retries {:default 0} :int]
   [:retry-delay {:default 2000}
    [:or
     [:fn {:error/message "Should be a function of the number of retries"} fn?]
     :int]]
   [:state-path {:default [:>]}
    [:vector :keyword]]])

(def fsm-schema
  (mu/merge
   core-fsm-schema-def
   [:map
    [:id :keyword]]))

(def fsm-validation-schema (mu/optional-keys fsm-schema [:max-retries :retry-delay :state-path]))

(def embedded-fsm-schema
  (mu/merge
   core-fsm-schema-def
   [:map
    [:failure-state {:optional true} [:vector :keyword]]
    [:success-state {:optional true} [:vector :keyword]]]))

(defn- assert-schema [schema config message]
  (when-let [errors (m/explain schema config)]
    #_(prn ::throwing-schema (me/humanize errors))
    (throw (ex-info message
                    {:humanized (me/humanize errors)
                     :data      errors}))))

(defn fsm? [fsm]
  (m/validate fsm-validation-schema fsm))

(defn update-retries [state & _]
  (update state :retries inc))

(defn reset-retries [state & _]
  (assoc state :retries 0))

(defn more-retries? [max-retries {:keys [retries]} _]
  (< retries max-retries))

(defn store-error [state event]
  (assoc state :error (:data event)))

(defn- dispatch-callback [event-vector-name]
  (sc.rf/dispatch-callback [:request event-vector-name]))

(defn dispatch-xhrio [state event]
  (sc.rf/call-fx {:http-xhrio (get-in state [:request :http-xhrio])}))

(defn embedded-fsm
  "Create an embedded machine as described by `config`. Will dispatch requests,
  retrying on error if so configured, and store progress on the app-db. See
  `::state` for details on fetching the progress."
  [{:keys [max-retries retry-delay
           state-path failure-state success-state]
    :as   config}]
  (assert-schema embedded-fsm-schema config "Invalid embedded HTTP FSM")
  (let [retry-delay (if (fn? retry-delay)
                      (comp retry-delay :retries)
                      retry-delay)]
    {:initial ::loading
     :states  {::loading {:entry [dispatch-xhrio
                                  (dispatch-callback :on-loading)]
                          :on    {::error   ::error
                                  ::success (or success-state ::loaded)}}
               ::error   {:initial ::retrying
                          :entry   [(sc/assign store-error)
                                    (dispatch-callback :on-error)]
                          :states  {::retrying {:always  [{:guard  (fn [] (< max-retries 1))
                                                           :target (or failure-state ::halted)}]
                                                :initial ::waiting
                                                :entry   (sc/assign reset-retries)
                                                :states  {::waiting {:after [{:delay  retry-delay
                                                                              :target ::loading}]}
                                                          ::loading {:entry [(sc/assign update-retries)
                                                                             dispatch-xhrio]
                                                                     :on    {::error   [{:guard  (partial more-retries? max-retries)
                                                                                         :target ::waiting}
                                                                                        (or failure-state (vec (concat state-path [::error ::halted])))]
                                                                             ::success (or success-state (vec (concat state-path [::loaded])))}}}}
                                    ::halted {:entry (dispatch-callback :on-failure)}}}
               ::loaded {:entry (fn [{{:keys [on-success]} :request} {:keys [data]}]
                                  (f/dispatch (conj on-success data)))}}}))

(defn fsm
  "Create a machine as described by `config`. Will dispatch requests, retrying
  on error if so configured, and store progress on the app-db. See `::state` for
  details on fetching the progress."
  [{:keys [id] :as config}]
  (assoc (embedded-fsm config) :id id))

(defn machine-path [fsm-id]
  (vec (concat [::fsm] (sc.utils/ensure-vector fsm-id))))

(defn request-state-path [request-id]
  (vec (concat [::fsm-state] (sc.utils/ensure-vector request-id))))

(defn expand-machine [config]
  (assert-schema fsm-validation-schema config "Invalid HTTP FSM")
  (-> (m/decode fsm-schema config mt/default-value-transformer)
      fsm
      sc/machine))

;; Register an FSM. After being registered, the `fsm` can be used to `::start`
;; requests.
(f/reg-event-fx
 ::register
 (fn [_ [_ fsm]]
   (let [machine (expand-machine fsm)]
     {:dispatch [::sc.rf/register (machine-path (:id machine)) machine]})))

(defn- request-transition-data [request]
  {:fsm-path   (machine-path (:fsm/id request))
   :state-path (request-state-path (:request/id request))})

;; Helper for requests that specify a `:path` instead of an `:on-success`.
(f/reg-event-db ::save-to-path (fn [db [_ path data]] (assoc-in db path data)))

(f/reg-fx
 ::start
 (fn [request]
   (assert-schema request-map-schema request "Invalid HTTP request")
   (let [transition-data (request-transition-data request)
         request         (-> request
                             ;; we are guaranteed to have :path or :on-success
                             (update :on-success #(or % [::save-to-path (:path request)]))
                             (update :http-xhrio assoc
                                     :on-success [::sc.rf/transition ::success transition-data]
                                     :on-failure [::sc.rf/transition ::error transition-data]))]
     (f/dispatch [::sc.rf/initialize transition-data {:context {:request request}}]))))

;; Start a request, storing it and its state in the DB.
(f/reg-event-fx ::start (fn [_ [_ request]] {::start request}))

;; Restart a request, which presumably finished either at [::error ::halted] or
;; [::loaded]. Note that the entire request isn't needed, only the `:request/id`
;; and `:fsm/id`. If you want to change any parameters of the request, dispatch
;; `::start` instead.
(f/reg-event-fx
 ::restart
 (fn [{:keys [db]} [_ request]]
   (let [transition-data (request-transition-data request)]
     (when-let [existing-context (get-in db (:state-path transition-data))]
       {:dispatch [::sc.rf/initialize transition-data {:context existing-context}]}))))

;; Removes the request identified by `request-id` from the app db. Does not
;; attempt to halt an in-flight request, though any transitions for the request
;; will be dropped.
(f/reg-event-fx
 ::discard
 (fn [_ [_ request-id]]
   {:dispatch [::sc.rf/discard-state (request-state-path request-id)]}))

;; Get the progress of the request identified by `request-id`. Should be one of:
;; [::loading]
;; [::error ::retrying ::waiting]
;; [::error ::retrying ::loading]
;; [::error ::halted]
;; [::loaded]
(f/reg-sub
 ::state
 (fn [db [_ request-id]]
   (sc.utils/ensure-vector (get-in db (conj (request-state-path request-id) :_state)))))

;; Get the full state of the request identified by `request-id`. Includes the
;; progress at :_state
(f/reg-sub
 ::state-full
 (fn [db [_ request-id]]
   (get-in db (request-state-path request-id))))
