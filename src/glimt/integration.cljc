(ns glimt.integration
  (:require
   [re-frame.core :as f]
   [statecharts.clock :as clock]
   [statecharts.core :as fsm]
   [statecharts.core :as fsm]
   [statecharts.delayed :as delayed]
   [statecharts.integrations.re-frame :as sc.rf]
   [statecharts.utils :as u]))

(defonce epochs (volatile! {}))

(defn new-epoch [id]
  (get (vswap! epochs update id sc.rf/safe-inc) id))

(f/reg-event-db
 ::init
 (fn [db [_ {:keys [id] :as machine} initialize-args]]
   (when-not (get-in db [:fsm id])
     (let [new-state (-> (fsm/initialize machine initialize-args)
                         (assoc :_epoch (new-epoch id)))]
       (assoc-in db [:fsm id] new-state)))))

(defn enrich
  [id fsm opts]
  (f/->interceptor
   :id id
   :before (fn enrich-before
             [context]
             (let [[event-id fsm-id & args] (f/get-coeffect context :event)]
               (if (and (= event-id :transition-fsm)
                        (= fsm-id id))
                 (f/assoc-coeffect context :event (vec (concat [event-id fsm opts] args)))
                 context)))))

(f/reg-event-db
 :transition-fsm
 (fn [db [_ {:keys [id epoch?] :as machine} opts fsm-event data :as args]]
   (when (get-in db [:fsm id])
     (let [fsm-event (u/ensure-event-map fsm-event)
           more-data (when (> (count args) 4)
                       (subvec args 3))]
       (if (and epoch?
                (sc.rf/should-discard fsm-event (get-in db [:fsm id :_epoch])))
         (do
           (sc.rf/log-discarded-event fsm-event)
           db)
         (update-in db [:fsm id]
                    (partial fsm/transition machine)
                    (cond-> (assoc fsm-event :data data)
                      (some? more-data)
                      (assoc :more-data more-data))
                    opts))))))


(deftype Scheduler [fsm-id ids clock]
  delayed/IScheduler
  (schedule [_ event delay]
    (let [id (clock/setTimeout clock #(f/dispatch [:transition-fsm fsm-id event]) delay)]
      (swap! ids assoc event id)))

  (unschedule [_ event]
    (when-let [id (get @ids event)]
      (clock/clearTimeout clock id)
      (swap! ids dissoc event))))

(defn integrate
  ([machine]
   (integrate machine sc.rf/default-opts))
  ([{:keys [id] :as machine} {:keys [clock] :as opts}]
   (let [clock   (or clock (clock/wall-clock))
         machine (assoc machine :scheduler (Scheduler. id (atom {}) clock))]
     (f/dispatch [::init machine])
     (f/reg-global-interceptor (enrich id machine (:transition-opts opts))))))
