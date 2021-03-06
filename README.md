# Request state machines for re-frame

**Wandering off the happy path**

Reduces boilerplate from your re-frame HTTP requests, while helping you handle errors and track the state of your request. Using an FSM makes dealing with errors and edge cases a breeze.

# Project status
Alpha. Works pretty well for my basic example. Breaking change likely in short term, hopefully I'll be able
to gather some feedback  soon to stabilize it.

[![Build Status](https://travis-ci.org/ingesolvoll/glimt.svg?branch=master)](https://travis-ci.org/ingesolvoll/glimt)

[![Clojars Project](https://img.shields.io/clojars/v/glimt.svg)](https://clojars.org/glimt)

 
# Basic API example

```clojure
(require '[glimt.core :as http])

;; First we shape our HTTP request the way we want it.
(def fsm {:id          :customer-loader
          :http-xhrio  {:uri             "http://example.com/customer/123"
                        :method          :get
                        :response-format (ajax/json-response-format {:keywords? true})}
          :max-retries 5
          :path        [::customers 123]})

;; Then, when we are ready to start contacting the server
(rf/dispatch [::http/start fsm])

;; The FSM provides you with a simple data representation of where we are in the process
(rf/subscribe [::http/state :customer-loader])

;; => [:glimt.core/loading]

;; First request failed, trying again
;; => [:glimt.core/error :glimt.core/retrying :glimt.core/loading]

;; Request succeeded, machine reached it success end state
;; => [:glimt.core/loaded]

(rf/subscribe [::http/state-full :customer-loader])
;; After trying again 5 times, FSM reaches its failure end state
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

# FSM configuration

**Everything is optional, unless specified otherwise.**

`:id` **Required** Needs to be a simple, non-qualified keyword for now. That requirement will change

`:http-xhrio` **Required** Request map as defined by https://github.com/day8/re-frame-http-fx

`:max-retries` Number of times the FSM will retry a failing request. Default 0.

`:retry-delay` Number of milliseconds between each retry. Alternatively, you can pass in a function that accepts the current number of retries and returns a number of milliseconds. Default 2000.

`:on-success` A re-frame event vector to dispatch to with the data from a successful response.

`:path` An alternative to `on-success`, where the response data is inserted into the app DB under the given path.

**You need to specify either `:on-success` or `:path`. Not both.**

`:on-loading` Re-frame event vector to dispatch when request starts

`:on-error` Re-frame event vector to dispatch when an error occurs

`:on-error` Re-frame event vector to dispatch when final failure state is reached

# API

Request FSM is started with either an event, like this:

`(rf/dispatch [::http/start fsm-configuration])`

Or by returning an effect from an event handler, like this:
```
{:db          (assoc db :some :prop)
 :dispatch    [::some-dispatch]
 ::http/start fsm-configuration}
```

FSM state is exposed through re-frame subscriptions:

```
(rf/subscribe [::http/state fsm-id]) 
     => [::http/loading]
```

```
(rf/subscribe [::http/state-full fsm-id]) 
     => {:_state  [::http/error ::http/retrying ::http/waiting]
         :retries 1}
```

# Events for entering states
`:on-loading`, `:on-error` and `:on-failure` are optional events that are called when entering the corresponding
states. Used for cases where you need to trigger additional side effects or store some state.

The full event vector dispatched is `[:on-loading state event transition-event]`, where `transition-event`
is the event that can be used for transitioning the FSM. This is rarely needed, but is included as a convenience.

Usage of `transition-event`: `(f/dispatch [transition-event :glimt.core/error])`
 
# How to structure your views

When the FSM handles all possible states, the UI code becomes a simple declaration of
recipies for each state.

```clojure
(defn http-loader-view [fsm-id body]
  (r/with-let [state (f/subscribe [::http/state fsm-id])]
    (let [[primary-state secondary-state] @state]
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
        body))))
```

# Credits
- https://github.com/lucywang000/clj-statecharts
- https://github.com/day8/re-frame-http-fx
- https://github.com/day8/re-frame-http-fx-alpha
