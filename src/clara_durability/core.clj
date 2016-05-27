(ns clara-durability.core
  (:require [clara.rules.durability :as d]
            [clara.rules.accumulators :as acc]
            [clara.rules.dsl :as dsl]
            [clara.rules :as r]
            [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.test :refer :all])
  (:import [clara_durability.avro
            Temperature
            Cold]
           [org.apache.avro.generic
            GenericRecordBuilder]
           [org.apache.avro.specific
            SpecificRecord
            SpecificDatumReader
            SpecificDatumWriter]
           [org.apache.avro.file
            DataFileReader
            DataFileWriter]
           [org.apache.avro
            Schema
            Schema$Parser]))

(def ^:dynamic *fact-holder* nil)

(defn id->fact [id]
  (get *fact-holder* id))

(defmethod print-dup SpecificRecord [o ^java.io.Writer w]
  (let [c (count @*fact-holder*)]
    (vswap! *fact-holder* conj o)
    (.write w "#=(clara-durability.core/id->fact ")
    (print-dup c w)
    (.write w ")")))

(defn ^Schema mk-out-schema [facts]
  (let [record-schema (->> facts
                           (group-by class)
                           vals
                           (mapv #(-> %
                                      ^SpecificRecord first
                                      .getSchema
                                      str
                                      json/read-str)))
        ^String json-schema (json/write-str
                             {:type "record"
                              :namespace "clara_durability.core"
                              :name "MemoryData"
                              :fields [{:name "facts"
                                        :type {:type "array"
                                               :items record-schema}}]})]
    (.parse (Schema$Parser.)
            json-schema)))

(defn write-data [file facts]
  (let [schema (mk-out-schema facts)
        mem-data-rec (-> (GenericRecordBuilder. schema)
                         (.set "facts" facts)
                         .build)]
    (with-open [wtr (.create (DataFileWriter. (SpecificDatumWriter.))
                             schema
                             (jio/as-file file))]
      (.append wtr mem-data-rec))))

(defn read-data [file]
  (with-open [rdr (DataFileReader/openReader (jio/as-file file)
                                             (SpecificDatumReader.))]
    (-> rdr seq vec)))

(defrecord TemperatureHistory [temperatures])

(r/defrule all-colds
  [Temperature (= ?t temperature)]
  [?cs <- (acc/all) :from [Cold (< temperature ?t)]]
  =>
  (->> ?cs
       (into #{} (map #(.getTemperature ^Cold %)))
       ->TemperatureHistory
       r/insert!))

(r/defquery find-hist []
  [?his <- TemperatureHistory])

(let [t50 (-> (Temperature/newBuilder)
              (.setTemperature 50)
              (.setLocation "MCI")
              .build)
      c50 (-> (Cold/newBuilder)
              (.setTemperature 50)
              .build)
      c10 (-> (Cold/newBuilder)
              (.setTemperature 10)
              .build)
      c20 (-> (Cold/newBuilder)
              (.setTemperature 20)
              .build)
      session (-> (r/mk-session [all-colds find-hist] :cache false)
                  (r/insert t50
                            c50
                            c10
                            c20)
                  r/fire-rules)
      orig-results (frequencies (r/query session find-hist))

      tmp1 (doto (java.io.File/createTempFile "test-session-store-1" ".clj")
             .deleteOnExit)
      avro-tmp1 (doto (java.io.File/createTempFile "test-avro-data-store-1" ".avro")
                  .deleteOnExit)
      tmp2 (doto (java.io.File/createTempFile "test-session-store-2" ".clj")
             .deleteOnExit)
      avro-tmp2 (doto (java.io.File/createTempFile "test-avro-data-store-2" ".avro")
                  .deleteOnExit)]

  ;; Sanity check.
  (is (= (frequencies [{:?his (->TemperatureHistory #{10 20})}])
         orig-results))
  
  (testing ":store-rulebase? true"
    (let [fact-holder (volatile! [])]
      (binding [*fact-holder* fact-holder]
        (with-open [out (jio/output-stream tmp1)]
          (d/store-session-state-to session
                                    out
                                    {:with-rulebase? true})))
      (write-data avro-tmp1 @fact-holder))

    (binding [*fact-holder* (read-data avro-tmp1)]
      (with-open [in (jio/input-stream tmp1)]
        (let [restored (d/restore-session-state-from in {})]
          (is (= orig-results
                 (frequencies (r/query restored find-hist))))))))

  (testing ":store-rulebase? false"
    (let [fact-holder (volatile! [])]
      (binding [*fact-holder* fact-holder]
        (with-open [out (jio/output-stream tmp2)]
          (d/store-session-state-to session
                                    out
                                    {:with-rulebase? false})))
      (write-data avro-tmp2 @fact-holder))      

    (binding [*fact-holder* (read-data avro-tmp2)]
      (with-open [in (jio/input-stream tmp2)]
        (let [restored (d/restore-session-state-from in
                                                     {:base-session session})]
          (is (= orig-results
                 (frequencies (r/query restored find-hist))))))))

  (.delete tmp1)
  (.delete avro-tmp1)
  (.delete tmp2)
  (.delete avro-tmp2))
