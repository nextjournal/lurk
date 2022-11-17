(ns user
  (:require [nextjournal.clerk :as clerk]))

(comment
  ;; start without file watcher, open browser when started
  (clerk/serve! {:browse? true :port 6677})

  ;; start with file watcher for these sub-directory paths
  (clerk/serve! {:watch-paths ["notebooks" "src"]}))
