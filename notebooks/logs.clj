(ns logs
  {:nextjournal.clerk/visibility {:code :hide :result :hide}}
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [msync.lucene :as lucene]
            [msync.lucene.analyzers :as analyzers]
            [msync.lucene.document :as ld]
            [msync.lucene.search]
            [msync.lucene.query :as query]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.viewer :as v])
  (:import [java.time Instant ZoneId ZoneOffset]
           [java.time.format DateTimeFormatter]
           [org.apache.commons.io.input Tailer TailerListener]
           [org.apache.lucene.document DateTools DateTools$Resolution]
           [org.apache.lucene.search BooleanQuery$Builder BooleanClause$Occur TermRangeQuery]))

#_(clerk/clear-cache!)

(defn ^:private tailer [file delay-ms from-end? line-callback]
  (Tailer/create file
                 (reify TailerListener
                   (init [_this _tailer])
                   (fileNotFound [_this])
                   (fileRotated [_this])
                   (^void handle [_this ^String line] (line-callback line))
                   (^void handle [_this ^Exception e] (throw e)))
                 delay-ms
                 from-end?))

(defn parse-timestamp [datetime-str]
  (Instant/from (.parse
                  (.withZone
                    (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss[.SSS][.SS][.S]z")
                    (ZoneId/from ZoneOffset/UTC))
                  datetime-str)))

(defn logline->edn [logline]
  ;; we can't use `edn/read-string` because it doesn't handle `#'some-var`
  ;; https://clojurians-log.clojureverse.org/clojure/2018-11-22/1542917788.910600
  (binding [*default-data-reader-fn* tagged-literal]
    (cond-> logline
      (:ductile logline)
      (update :ductile read-string)

      (str/starts-with? (:logger_name logline) "ductile")
      (update :message read-string))))

(defn log-analyzer []
  (let [keyword-analyzer (analyzers/keyword-analyzer)]
    (analyzers/per-field-analyzer (analyzers/standard-analyzer)
                                  {:level   keyword-analyzer
                                   :version keyword-analyzer})))

(defn log-index []
  (lucene/create-index!
    :type :memory
    :analyzer (log-analyzer)))

(defn index-line! [index data]
  (lucene/index! index
                 (update data :timestamp (fn instant->ms-str [inst]
                                           (DateTools/timeToString (.toEpochMilli inst) DateTools$Resolution/SECOND)))
                 {:stored-fields  [:level :timestamp :ductile :message :logger_name]
                  :suggest-fields [:logger_name]}))

(defn build-query [analyzer query-text query-timerange]
  (let [qb (BooleanQuery$Builder.)]
    (when (seq query-text)
      (.add qb
            (query/parse (edn/read-string query-text) {:analyzer analyzer})
            BooleanClause$Occur/MUST))
    (when query-timerange
      (.add qb
            (TermRangeQuery/newStringRange
              "timestamp" (first query-timerange) (second query-timerange) true true)
            BooleanClause$Occur/MUST))
    (.build qb)))

#_(build-query (log-analyzer)
             "{:message \"a thing\"}"
             ["20221116080730" "20221116080802"])

(defonce lucene-index (log-index))

(defn lucene-datetime->instant [lucene-datetime-str]
  (Instant/from (.parse (.withZone (DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
                                   (ZoneId/from ZoneOffset/UTC))
                        lucene-datetime-str)))

(defn search-lucene [{:keys [text timerange]}]
  (lucene/search lucene-index
                 (build-query (:analyzer lucene-index) text timerange)
                 {:results-per-page 200
                  :hit->doc         (comp #(update % :timestamp (comp str lucene-datetime->instant))
                                          logline->edn
                                          ld/document->map)}))

(defn parse-line [line]
  (-> line
      (json/parse-string true)
      (dissoc :level_value)
      (rename-keys {(keyword "@timestamp") :timestamp
                    (keyword "@version")   :version})
      (update :timestamp parse-timestamp)))

(defonce !log-lines (atom []))

(defonce follower
  (tailer
    (io/file "resources/example.log")
    1000
    false
    (fn [line]
      (try
        (let [entry (parse-line line)]
          (swap! !log-lines conj (logline->edn entry))
          (index-line! lucene-index entry))
        (catch Exception e
          (println e))))))

#_(lucene/search lucene-index
                 (build-query (:analyzer locking-index) "" ["20221116090906" "20221116090908"]))

(defn recompute-thread []
  (let [exit-chan (async/chan 1)]
    (async/thread
      (loop [queue-ch (async/timeout 20000)]
        (let [[_ chan] (async/alts!! [queue-ch exit-chan])]
          (when-not (= exit-chan chan)
            (clerk/recompute!)
            (recur (async/timeout 20000))))))
    (fn [] (async/>!! exit-chan :exit))))

(defonce stop-recompute! (recompute-thread))

(comment
(do
  (stop-recompute!)
  (.stop follower)
  (reset! !log-lines []))
)

;; ## Using Lucene to search 🪵

^::clerk/sync
(defonce vega-selection (atom nil))

(defn vega-datetime-str [instant]
  (str (.format (.withZone
                  (java.time.format.DateTimeFormatter/ofPattern "dd MMM yyyy HH:mm:ss")
                  (ZoneId/from ZoneOffset/UTC))
                instant)
       " GMT"))

^{::clerk/visibility {:result :show}}
(clerk/vl
 {:width 650
  :height 200
  :encoding {"x" {"field" "logentry"
                  "timeUnit" {"utc" true
                              "unit" "yearmonthdatehoursminutesseconds"}
                  "type" "nominal"
                  "axis" {"labelAngle" 0}}
             "y" {"aggregate" "count"
                  "type" "quantitative"
                  "title" "count"}}
  :layer [{:data {:values (map (fn [{:keys [timestamp]}] {:logentry (vega-datetime-str timestamp)}) @!log-lines)}
           :mark "bar"}
          {:params [{:name "interval_selector"
                     :select {:type "interval"
                              :encodings ["x"]}}]
           :mark "area"}]
  :embed/callback (v/->viewer-eval
                   '(fn [embedded-vega]
                      (let [view (.-view embedded-vega)
                            !selection-state (atom nil)]
                        ;; on every selection change, store the selection
                        (.addSignalListener view
                                            "interval_selector"
                                            (fn [_signal selection]
                                              (reset! !selection-state
                                                      (js->clj (.-utcyearmonthdatehoursminutesseconds_logentry selection)))))
                        ;; mouse releases set the sync'd atom to the current
                        ;; selection, avoiding many updates to sync'd atom on
                        ;; every intermediate selection change
                        (.addEventListener view
                                           "mouseup"
                                           (fn [_event _item]
                                             (swap! logs/vega-selection (constantly (deref !selection-state)))
                                             (v/clerk-eval `(search!)))))
                      embedded-vega))
  :embed/opts {:actions false}})

(defonce !query-results (atom []))

(def editor-sync-viewer
  {:transform-fn (comp v/mark-presented
                       (v/update-val
                        (comp v/->viewer-eval symbol :nextjournal.clerk/var-from-def)))
   :render-fn
   '(fn [code-state _]
      [:div.bg-neutral-50
       [nextjournal.clerk.render.code/editor @code-state
        {:on-change (fn [text] (swap! code-state (constantly text)))
         :extensions (.concat (codemirror.view/lineNumbers)
                              (codemirror.view/highlightActiveLine)
                              nextjournal.clerk.render.code/paredit-keymap)}]
       [:button.absolute.right-2.text-xl.cursor-pointer
        {:class "top-1/2 -translate-y-1/2"
         :on-click #(v/clerk-eval `(search!))} "▶️"]])})
^{::clerk/sync true ::clerk/viewer editor-sync-viewer ::clerk/visibility {:result :show}}
(defonce !lucene-query (atom ""))


(defn search! []
  (reset! !query-results
          (if (or (seq @!lucene-query) (seq @vega-selection))
            (search-lucene
              {:timerange (when (seq @vega-selection)
                            [(DateTools/timeToString (.getTime (first @vega-selection))
                                                     DateTools$Resolution/SECOND)
                             (DateTools/timeToString (.getTime (last @vega-selection))
                                                     DateTools$Resolution/SECOND)])
               :text @!lucene-query})
            (map #(update % :timestamp str) (reverse @!log-lines)))))

(defn reset-state! []
  (reset! vega-selection nil)
  (reset! !lucene-query "")
  (search!))

^{::clerk/visibility {:result :show}
  ::clerk/viewer '(fn [_side]
                    (v/html [:div.text-center
                             [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded
                              {:on-click (fn [e]
                                           (v/clerk-eval '(reset-state!)))} "Clear ❌"]]))}
["Clear ❌"]


;; ## 🪵 query results

^{::clerk/visibility {:result :show}}
(v/html [:h3 (str (count @!query-results) " of " (count @!log-lines) " logs")])

^{::clerk/visibility {:result :show}
  ::clerk/width :full}
(let [ordering [:doc-id :score :level :logger_name :timestamp :message :ductile]]
  (clerk/table
    {:head ordering
     :rows (map #(-> %
                     (merge (:hit %))
                     (dissoc :hit)
                     ((apply juxt ordering)))
                @!query-results)}))
