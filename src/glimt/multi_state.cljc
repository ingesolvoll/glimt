(ns glimt.multi-state
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [re-frame.core :as f]
            [statecharts.core :as sc]
            [statecharts.utils :as sc.utils]
            [mainej.statecharts.integrations.re-frame :as sc.rf]))

(def default-fsm-id ::fsm)
(def default-fsm-path [default-fsm-id])

(def request-schema-def
  [:map
   [:id [:or :keyword [:vector :any]]]
   ;; vector is preferred, but sc.rf/register will convert a keyword to a vector
   [:fsm-path {:default default-fsm-path} [:or :keyword [:vector :any]]]
   [:http-xhrio :map]

   [:max-retries {:default 0} :int]
   [:retry-delay {:default 2000}
    [:or
     [:fn {:error/message "Should be a function of the number of retries"} fn?]
     :int]]

   [:path {:optional true} vector?]
   [:on-success {:optional true} vector?]
   [:on-loading {:optional true} vector?]
   [:on-error {:optional true} vector?]
   [:on-failure {:optional true} vector?]])

(def on-success-schema-def
  [:fn {:error/message "Should contain either path or on-success, and not both"}
   (fn [{:keys [path on-success]}]
     (->> [path on-success]
          (filter identity)
          count
          (= 1)))])

(defn with-on-success-schema [s]
  [:and s on-success-schema-def])

(def request-schema (-> request-schema-def
                        with-on-success-schema
                        m/schema))
(def request-validation-schema (-> request-schema-def
                                   (mu/optional-keys [:fsm-path :max-retries :retry-delay])
                                   with-on-success-schema
                                   m/schema))

(def embedded-fsm-schema
  (m/schema
   [:map
    [:state-path [:vector :keyword]]
    [:failure-state {:optional true} [:vector :keyword]]
    [:success-state {:optional true} [:vector :keyword]]]))

(defn allow-retries? [{:keys [request]} _event]
  (>= (:max-retries request) 1))

(defn retry-delay [{:keys [request retries]} _event]
  (let [{:keys [retry-delay]} request]
    (retry-delay retries)))

(defn reset-retries [state _event]
  (assoc state :retries 0))

(defn update-retries [state _event]
  (update state :retries inc))

(defn more-retries? [{:keys [request retries]} _event]
  (< retries (:max-retries request)))

(defn store-error [state event]
  (assoc state :error (:data event)))

(defn- dispatch-callback [event-vector-name]
  (sc.rf/dispatch-callback [:request event-vector-name]))

(defn dispatch-xhrio [{:keys [request]} _event]
  (sc.rf/call-fx (select-keys request [:http-xhrio])))

(defn- dispatch-success [{:keys [request]} {:keys [data]}]
  (f/dispatch (conj (:on-success request) data)))

(defn embedded-fsm
  "Create an embedded machine as described by `config`. Will dispatch requests,
  retrying on error if so configured, and store progress on the app-db. See
  `::state` for details on fetching the progress."
  [{:keys [state-path failure-state success-state] :as config}]
  (when-let [errors (m/explain embedded-fsm-schema config)]
    (throw (ex-info "Invalid embedded HTTP FSM"
                    {:humanized (me/humanize errors)
                     :data      errors})))
  {:initial ::loading
   :states  {::loading {:entry [dispatch-xhrio
                                (dispatch-callback :on-loading)]
                        :on    {::error   ::error
                                ::success [{:actions dispatch-success
                                            :target  (or success-state ::loaded)}]}}
             ::error   {:initial ::retrying
                        :entry   [(sc/assign store-error)]
                        :states  {::retrying {:always  [{:guard   (complement allow-retries?)
                                                         :target  (or failure-state ::halted)
                                                         :actions (dispatch-callback :on-failure)}]
                                              :initial ::waiting
                                              :entry   (sc/assign reset-retries)
                                              :states  {::waiting {:entry (dispatch-callback :on-error)
                                                                   :after [{:delay  retry-delay
                                                                            :target ::loading}]}
                                                        ::loading {:entry [(sc/assign update-retries)
                                                                           dispatch-xhrio]
                                                                   :on    {::error   [{:guard  more-retries?
                                                                                       :target ::waiting}
                                                                                      {:target  (or failure-state (vec (concat state-path [::error ::halted])))
                                                                                       :actions (dispatch-callback :on-failure)}]
                                                                           ::success [{:target  (or success-state (vec (concat state-path [::loaded])))
                                                                                       :actions dispatch-success}]}}}}
                                  ::halted   {}}}
             ::loaded  {}}})

;; Register the default FSM. If a request doesn't include an `:fsm-path`, this
;; FSM will be used.
(let [machine (assoc (embedded-fsm {:state-path [:>]}) :id default-fsm-id)]
  (f/dispatch [::sc.rf/register default-fsm-path (sc/machine machine)]))

(defn request-state-path [request-id]
  (into [::requests] (sc.utils/ensure-vector request-id)))

(defn- request-transition-data [{:keys [id fsm-path]}]
  {:fsm-path   (or fsm-path default-fsm-path)
   :state-path (request-state-path id)})

;; Helper for requests that specify a `:path` instead of an `:on-success`.
(f/reg-event-db ::save-to-path (fn [db [_ path data]] (assoc-in db path data)))

;; Start a request, storing it and its state in the DB.
(f/reg-event-fx
 ::start
 (fn [_ [_ request]]
   (if-let [errors (m/explain request-validation-schema request)]
     {::sc.rf/log [:error "Invalid HTTP request" (str (me/humanize errors)) errors]}
     (let [transition-data (request-transition-data request)
           request         (-> (m/decode request-schema request mt/default-value-transformer)
                               ;; retry-delay is a function (of how many retries have been
                               ;; performed) or an int. Coerce it to be a function.
                               (update :retry-delay
                                       (fn [retry-delay]
                                         (if (int? retry-delay)
                                           (constantly retry-delay)
                                           retry-delay)))
                               ;; we are guaranteed to have :path or :on-success
                               (update :on-success #(or % [::save-to-path (:path request)]))
                               (update :http-xhrio assoc
                                       :on-success [::sc.rf/transition ::success transition-data]
                                       :on-failure [::sc.rf/transition ::error transition-data]))]
       {:dispatch [::sc.rf/initialize transition-data {:id      (:state-path transition-data)
                                                       :context {:request request}}]}))))

;; Restart a request, which presumably finished either at [::error ::halted] or
;; [::loaded]. Note that the entire request isn't needed, only the `:id` and,
;; optionally, the `:fsm-path`. If you want to change any parameters of the
;; request, dispatch `::start` instead.
(f/reg-event-fx
 ::restart
 (fn [{:keys [db]} [_ request]]
   (let [transition-data (request-transition-data request)
         state-path      (:state-path transition-data)]
     (when-let [existing-context (get-in db state-path)]
       {:dispatch [::sc.rf/initialize transition-data {:id      state-path
                                                       :context existing-context}]}))))

;; Removes the request identified by `request-id` from the app db. Does not
;; attempt to halt an in-flight request, though any transitions for the request
;; will be dropped.
(f/reg-event-fx
 ::discard
 (fn [_ [_ request-id]]
   {:dispatch [::sc.rf/discard-state (request-state-path request-id)]}))

;; Get the progress of the request identified by `request-id`. Should be one of:
;; [] ; <- not yet started
;; [::loading]
;; [::error ::retrying ::waiting]
;; [::error ::retrying ::loading]
;; [::error ::halted]
;; [::loaded]
;; If part of an embedded machine, the state will be prepended by states defined
;; in the parent machine.
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
