# Request state machines for re-frame

**Wandering off the happy path**

Reduces boilerplate from your re-frame HTTP requests, while helping you handle errors and track the state of your request. Using an FSM makes dealing with errors and edge cases a breeze.

# Project status (Oct 11th, 2021)
Still early, expecting minor bugs and possibly some breaking APIs in the short term.

[![Clojars Project](https://img.shields.io/clojars/v/glimt.svg)](https://clojars.org/glimt)

 
# Basic API example

```clojure
(require '[re-frame.core :as rf])
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
(def state (rf/subscribe [::http/state :customer-loader]))

@state
;; => [:glimt.core/loading]

;; First request failed, because of transient error, trying again
@state
;; => [:glimt.core/error :glimt.core/retrying :glimt.core/loading]

;; Request succeeded, machine reached it success end state
@state
;; => [:glimt.core/loaded]

;; Or, if after trying again 5 times, FSM reaches its failure end state
@(rf/subscribe [::http/state-full :customer-loader])
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

;; At your leisure, remove FSM from app db
(rf/dispatch [::http/discard :customer-loader])

@state
;; => nil
```

# FSM configuration

**Everything is optional, unless specified otherwise.**

`:id` **Required** The globally unique keyword identifying this FSM

`:http-xhrio` **Required** Request map as defined by https://github.com/day8/re-frame-http-fx

`:max-retries` Number of times the FSM will retry a failing request. Default 0.

`:retry-delay` Number of milliseconds between each retry. Alternatively, you can pass in a function that accepts the current number of retries and returns a number of milliseconds. Default 2000.

`:on-success` A re-frame event vector to dispatch to with the data from a successful response.

`:path` An alternative to `on-success`, where the response data is inserted into the app DB under the given path.

**You need to specify either `:on-success` or `:path`. Not both.**

`:on-loading` Re-frame event vector to dispatch when request starts

`:on-error` Re-frame event vector to dispatch when an error occurs

`:on-failure` Re-frame event vector to dispatch when final failure state is reached

# API

Request FSM is started with either an event, like this:

`(rf/dispatch [::http/start fsm-configuration])`

Or by returning an effect from an event handler, like this:
```clojure
{:db          (assoc db :some :prop)
 :dispatch    [::some-dispatch]
 ::http/start fsm-configuration}
```

FSM state is exposed through re-frame subscriptions:

```
(rf/subscribe [::http/state fsm-id]) 
     => [::http/loading]
```

```clojure
(rf/subscribe [::http/state-full fsm-id]) 
     => {:_state  [::http/error ::http/retrying ::http/waiting]
         :retries 1}
```

You can also embed an HTTP FSM inside another FSM. Like this:

```clojure
{:id               :polling-fsm
 :transition-event :transition-my-fsm
 :initial          ::running
 :states           {::running {:initial ::loading
                               :states  {::waiting {:after [{:delay  10000
                                                             :target ::loading}]}
                                         ::loading (http/embedded-fsm
                                                    {:transition-event :transition-my-fsm
                                                     :http-xhrio       "url"
                                                     :path             [:store :data :here]
                                                     :state-path       [:> ::running ::loading]
                                                     :success-state    [:> ::running ::waiting]})}}}}
```

In this context, we get a few more settings that need to be considered.

`:transition-event`: **Required** The embedded machine needs access to the transition event of the container.

`:state-path`: **Required** The path within the containing FSM where this embedded one resides. Needed for computing
state transitions relative to the embedded machine.

Also, we may want to transition to some arbitrary state when the HTTP FSM fails or succeeds:

`:success-state`: Transition to this state when request succeeds

`:failure-state`: Transition to this state when request fails.


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
