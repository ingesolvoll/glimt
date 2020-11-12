# Wandering off the happy path

Reduces boilerplate from your re-frame HTTP requests, while helping you handle errors and track the state of your request. Using an FSM makes dealing with errors and edge cases a breeze.

# Basic API usage

```clojure
(require '[glimt.core :as http])

;; First we shape our HTTP request the way we want it.
(def fsm {:id          :customer-loader
          :http-xhrio  {:uri             "http://example.com/customer/123"
                        :method          :get
                        :response-format (ajax/json-response-format {:keywords? true})}
          :max-retries 5
          :on-success  [::customer-received]})

;; Then, when we are ready to start contacting the server
(rf/dispatch [::http/start fsm])

;; The FSM provides you with a simple data representation of where we are in the process
(rf/subscribe [::http/state :customer-loader])

;; => {:_state :glimt.core/loading}

;; First request failed, trying again
;; => {:_state [:glimt.core/error :glimt.core/retrying :glimt.core/loading]}

;; => {:_state :glimt.core/loaded}

;; After trying again 5 times, FSM reaches it failure end state
;; => {:_state [:glimt.core/error :glimt.core/halted] 
;;     :error  {:uri             "/error"
;;              :last-method     "GET"
;;              :last-error      "Service Unavailable [503]"
;;              :last-error-code 6
;;              :debug-message   "Http response at 400 or 500 level"
;;              :status          503
;;              :status-text     "Service Unavailable"
;;              :failure         :error
;;              :response        nil}]}
```

# How to structure your views

When the FSM handles all possible states, the UI code becomes a simple declaration of
recipies for each state.

```clojure
(defn http-loader-view [fsm-id content]
  (r/with-let [full-state (f/subscribe [::http/state fsm-id])]
    (let [state (:_state @full-state)
          [primary-state secondary-state] (if (keyword? state) [state] state)]
      (case primary-state
        nil
        [:div "Nothing much happened yet"]

        ::http/loading
        [:div "Loading..."]

        ::http/error
        [:div [:h3 "An error occurred"]
         (case secondary-state
           ::http/retrying
           [:div "Please wait, trying again"]

           ::http/halted
           [:div
            "Could not load data"
            [:div
             [:button {:on-click #(f/dispatch [::http/restart fsm-id])}
              "Click to try again"]]])]

        ::http/loaded
        content))))
```