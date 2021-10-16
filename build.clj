(ns build
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'glimt/glimt)
;; if you want a version of MAJOR.MINOR.COMMITS:
(def version "0.1.1")

(defn install [opts]
      (-> opts
          (assoc :lib lib :version version)
          (bb/jar)
          (bb/install)))

(defn deploy [opts]
      (-> opts
          (assoc :lib lib :version version)
          (bb/jar)
          (bb/deploy)))