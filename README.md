# glimt
Wandering off the happy path

Reduces boilerplate from your re-frame HTTP requests, while helping you handle errors and track the state of your request. Using an FSM makes dealing with errors and edge cases a breeze.

# Usage

```clojure
(def fsm {:id          :customer-loader
          :http-xhrio  {:uri             "http://example.com/customer/123"
                        :method          :get
                        :response-format (ajax/json-response-format {:keywords? true})}
          :max-retries 5
          :on-success  [::customer-received]})

(rf/dispatch [:glimt.core/start fsm])

(rf/subscribe [:glimt.core/state :customer-loader])

;; => {:_state :glimt.core/loading}

;; => {:_state [:glimt.core/error :glimt.core/retrying :glimt.core/loading]}

;; => {:_state :glimt.core/loaded}

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
