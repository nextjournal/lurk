(ns user
  (:require [nextjournal.clerk :as clerk]))

;; start without file watcher, open browser when started
(clerk/serve! {:browse? true :port 6677})
(clerk/show! 'nextjournal.lurk)

(comment
  ;; start with file watcher for these sub-directory paths
  (clerk/serve! {:watch-paths ["notebooks" "src"]}))
