(ns wealthpulse.web
  (:use compojure.core)
  (:require [clojure.string :as string]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :as json]
            [ring.util.response :as response]
            [wealthpulse.parser :as parser]
            [wealthpulse.query :as query]
            [wealthpulse.util :as util])
  (:import [java.text NumberFormat]))



; TODO: Is this the best place for this?
(defn calculate-period
  "Calculate period based on period, since, and upto parameters."
  [{:keys [period since upto]}]
  (let [first-non-nil (fn [coll] (some #(if (not (nil? %)) %) coll))
        date-parser (java.text.SimpleDateFormat. "yyyy/MM/dd")
        lowercase-period (if (not (nil? period)) (string/lower-case period))
        since-date (if (not (nil? since)) (.parse date-parser since))
        upto-date (if (not (nil? upto)) (.parse date-parser upto))
        [period-start, period-end] (cond (= lowercase-period "this month")
                                           (let [this-month (java.util.Calendar/getInstance)]
                                             [(util/get-first-of-month this-month)
                                              (util/get-last-of-month this-month)])
                                         (= lowercase-period "last month")
                                           (let [last-month (doto (java.util.Calendar/getInstance)
                                                              (.add  java.util.Calendar/MONTH -1))]
                                             [(util/get-first-of-month last-month)
                                              (util/get-last-of-month last-month)])
                                         :else [nil nil])]
    [(first-non-nil [period-start since-date])
     (first-non-nil [period-end upto-date])]))


; TODO: this is here temporarily and should be moved somewhere more appropriate
(defn annotate-balances
  [[balances total]]
  (let [padding-left-base 8
        indent-padding 20
        sorted-balances (sort-by first balances)
        get-account-display (fn [account]
                              (let [[parentage indent] (reduce (fn [[parentage indent] [other-account _]]
                                                               (if (and (.startsWith account other-account)
                                                                        (not= account other-account)
                                                                        (= (.charAt account (.length other-account)) \:))
                                                                   [other-account (inc indent)]
                                                                   [parentage indent]))
                                                             ["" 0]
                                                             sorted-balances)
                                    account-display (if (not (.isEmpty parentage))
                                                        (.substring account (inc (.length parentage)))
                                                        account)]
                                [account-display indent]))
        format-balance #(.format (NumberFormat/getCurrencyInstance) %)
        annotated-balances (vec (map (fn [[account balance]]
                                       (let [[account-display indent] (get-account-display account)
                                             padding (+ padding-left-base (* indent indent-padding))]
                                         {:key account
                                          :account account-display
                                          :balance (format-balance balance)
                                          :balanceClass (string/lower-case (first (string/split account #":")))
                                          :accountStyle {:padding-left (str padding "px")}}))
                                     sorted-balances))]
    (conj annotated-balances {:key "Total"
                              :account ""
                              :balance (format-balance total)
                              :rowClass "grand_total"})))


; TODO: I think eventually most of this should be moved to the front end (title, subtitle do not belong here)
(defn handle-balance
  "Handle Balance api request. Possible parameters:
    accountsWith
    excludeAccountsWith
    period
    since
    upto
    title"
  [journal params]
  (let [date-formatter (java.text.SimpleDateFormat. "MMMM d, yyyy")
        [period-start period-end] (calculate-period params)
        accounts-with (if (contains? params :accountsWith) (string/split (:accountsWith params) #" "))
        exclude-accounts-with (if (contains? params :excludeAccountsWith) (string/split (:excludeAccountsWith params) #" "))
        subtitle (cond (and period-start period-end) (str "For the period of " (.format date-formatter period-start) " to " (.format date-formatter period-end))
                       (not (nil? period-start)) (str "Since " (.format date-formatter period-start))
                       (not (nil? period-end)) (str "Up to " (.format date-formatter period-end))
                       :else (str "As of " (.format date-formatter (java.util.Date.))))]
    {:title (get params :title "Balance Sheet")
     :subtitle subtitle
     :balances (annotate-balances (query/balance journal {:accounts-with accounts-with
                                                          :exclude-accounts-with exclude-accounts-with
                                                          :period-start period-start
                                                          :period-end period-end}))}))


(defn handle-networth
  "Handle Networth api request. No possible parameters."
  [journal]
  (let [date-formatter (java.text.SimpleDateFormat. "dd-MMM-yyyy")
        month-formatter (java.text.SimpleDateFormat. "MMM yyyy")
        format-balance #(.format (NumberFormat/getCurrencyInstance) %)
        start-month (doto (java.util.Calendar/getInstance)
                      (.add java.util.Calendar/MONTH -26))
        add-month #(doto % (.add java.util.Calendar/MONTH 1))
        generate-period-balance (fn [calendar]
                                  (let [end-of-month (util/get-last-of-month calendar)
                                        parameters {:accounts-with ["assets" "liabilities"]
                                                    :exclude-accounts-with ["units"]
                                                    :period-start nil
                                                    :period-end end-of-month}
                                        [_ totalBalance] (query/balance journal parameters)]
                                    {:date (.format date-formatter (util/get-first-of-month calendar))
                                     :amount totalBalance
                                     :hover (str (.format month-formatter end-of-month) ": " (format-balance totalBalance))}))]
    {:title "Net Worth"
     :data (map (comp generate-period-balance add-month) (repeat 25 start-month))}))


(defn handle-nav
  "Handle Nav api request. No possible parameters."
  [journal]
  {:reports [{:key "Balance Sheet" :title "Balance Sheet" :report "balance" :query "accountsWith=assets+liabilities&excludeAccountsWith=units"}
             {:key "Net Worth" :title "Net Worth" :report "networth" :query ""}
             {:key "Income Statement - Current Month" :title "Income Statement - Current Month" :report "balance" :query "accountsWith=income+expenses&period=this+month&title=Income+Statement"}
             {:key "Income Statement - Previous Month" :title "Income Statement - Previous Month" :report "balance" :query "accountsWith=income+expenses&period=last+month&title=Income+Statement"}]
   :payees []})


(defn api-routes
  "Define API routes."
  [journal]
  (routes
    (GET "/nav" [] (response/response (handle-nav journal)))
    (GET "/balance" [& params] (response/response (handle-balance journal params)))
    (GET "/networth" [& params] (response/response (handle-networth journal)))))


(defn app-routes
  "Define application routes."
	[journal]
	(routes
    (context "/api" [] (-> (handler/api (api-routes journal))
                           (json/wrap-json-body)
                           (json/wrap-json-response)))
		(handler/site
      (routes
        (GET "/" [] (response/resource-response "index.html" {:root "public"}))
        (route/resources "/")
        (route/not-found "Not Found")))))


(def handler
  (let [ledger-file (.get (System/getenv) "LEDGER_FILE")]
    (app-routes (parser/parse-journal ledger-file))))
