(ns build
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'glimt/glimt)
;; if you want a version of MAJOR.MINOR.COMMITS:
(def version "0.2.1")

(defn jar [opts]
      (-> opts
          (assoc :lib lib :version version)
          (bb/jar)))

(defn install [opts]
      (-> opts
          jar
          (bb/install)))

(defn deploy [opts]
      (-> opts
          jar
          (bb/deploy)))
