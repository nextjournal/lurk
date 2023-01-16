(ns example-service.main
  (:require [io.pedestal.log :as log]))

(def logger-names
  ["example-service.logic"
   "example-service.controller"
   "example-service.adapter"])

(defn log-data []
  (rand-nth
    [{:telluric-currents (rand)
      :actor "Casabuon"}
     {:actor "Belbo"
      :foucaults-pendulum (rand)}
     {:subject "Knights Templar"
      :input :abulafia}]))

(def log-level [:info :warn :error])


(defn -main [& _args]
  (while 1
    (log/log
      (assoc (log-data)
             :io.pedestal.log/logger-name
             (rand-nth logger-names))
      (rand-nth log-level))
    (Thread/sleep (int (+ 500 (rand 2000))))))
