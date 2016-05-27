(defproject clara-durability "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.toomuchcode/clara-rules "0.12.0-SNAPSHOT"]
                 [org.apache.avro/avro "1.7.5"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.fressian "0.2.1"]]
  :source-paths ["src"
                 "target/generated-sources/avro"]
  :pom-plugins [[org.apache.avro/avro-maven-plugin "1.7.5"
                 {:configuration ([:fieldVisibility "PRIVATE"]
                                  [:stringType "String"]
                                  [:sourceDirectory "test/clara_durability/avro"])
                  :executions ([:execution
                                [:goals ([:goal "idl-protocol"])]
                                [:phase "generate-sources"]])}]])
