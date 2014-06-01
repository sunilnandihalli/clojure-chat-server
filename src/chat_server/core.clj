(ns chat-server.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alt! alts!! go]]
            [clojure.tools.cli :as cli]
            [taoensso.timbre :as timbre]
            [beckon :as b]
            [potemkin :as pot]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [lamina.core :as l]
            [lamina.trace.probe :as probe]
            [lamina.viz :as lv]
            [aleph.tcp :as tcp]
            [aleph.http :as h]
            [gloss.core :as g]
            [gloss.io :as gio]))
(timbre/refer-timbre)

(def frame-codec (let [int-ascii (g/string-integer :ascii :length 4)
                       int-ascii-with-space-suffix (g/string-integer :ascii :length 4 :suffix \ )
                       space-delimited-string (g/string :ascii :delimiters [" "])
                       unbounded-string (g/string :ascii)
                       cmd-frame {:snd (g/ordered-map :to space-delimited-string
                                                      :message unbounded-string)
                                  :rcv (g/ordered-map :from space-delimited-string
                                                      :word-count int-ascii-with-space-suffix
                                                      :message unbounded-string)
                                  :reg (g/ordered-map :name unbounded-string)
                                  :bye unbounded-string}
                       trf-input-before-encoding (fn [[cmd data]]
                                                   [(s/upper-case (name cmd))
                                                    (cons \  (.array (gio/contiguous (gio/encode (cmd-frame cmd) data))))])
                       trf-output-after-decoding (fn [[cmd-str byte-seq]]
                                                   (let [cmd (keyword (s/lower-case cmd-str))
                                                         data-str (apply str (map char (drop 1 byte-seq)))]
                                                     [cmd (gio/decode (cmd-frame cmd) (gio/to-byte-buffer data-str))]))
                       proto-frame [(g/string :ascii :delimiters [" "]) (g/repeated :byte :prefix int-ascii)]]
                   (g/compile-frame proto-frame trf-input-before-encoding trf-output-after-decoding)))

(defnp start-chat-server [server-host server-port]
  (let [clients (atom {})
        count-words #(count (re-seq #"[^ ]+" %))
        server-handler (fn [ch client-info]
                         (l/receive-all ch
                                        (let [my-name (atom nil)]
                                          (fn [[cmd {:keys [to message name]}]]
                                            (case cmd
                                              :reg (when-not @my-name
                                                     (reset! my-name name)
                                                     (swap! clients assoc name ch))
                                              :snd (if-let [to-chan (@clients to)]
                                                     (l/enqueue to-chan [:rcv {:from @my-name :message message
                                                                               :word-count (count-words message)}])))))))]
    (println (str "starting server on port " server-port))
    (tcp/start-tcp-server server-handler {:host server-host :port server-port :frame frame-codec :name "Sunil's Clojure chat-server"})
    (reset! (b/signal-atom "INT") #{#(do (println :handling-sigint)
                                         (doseq [[name ch] @clients] (l/enqueue-and-close ch [:bye " "]))
                                         (b/raise! "TERM"))})))

(let [cli-options [["-p" "--port PORT" "Port number" :default 5000 :parse-fn #(Integer/parseInt %)
                    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
                   ["-H" "--host HOST" "hostname " :default "0.0.0.0"]
                   ["-h"    "--help" "show help"]]]
  (defn -main [& args]
    (let [{{:keys [host port help] :as options} :options summary :summary} (cli/parse-opts args cli-options)]
      (start-chat-server host port))))
