(ns chat-server.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alt! alts!! go]]
            [clojure.tools.cli :as cli]
            [beckon :as b]
            [clj-time.core :as t]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [lamina.core :as l]
            [lamina.viz :as lv]
            [aleph.tcp :as tcp]
            [aleph.http :as h]
            [gloss.core :as g]
            [gloss.io :as gio]))

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
(defn start-chat-server [server-host server-port]
  (let [clients (agent {})
        message-chan
        error-chan
        broadcast #(doseq [[name ch] @clients] (l/enqueue ch %))
        server-handler (fn [ch client-info]
                         (l/receive-all ch
                                        (let [my-name (atom nil)]
                                          (fn [[cmd {:keys [to message name]} :as data]]
                                            (case cmd
                                              :reg (if-not @my-name
                                                     (do
                                                       (l/enqueue message-chan [:registered name])
                                                       (reset! my-name name)
                                                       (send clients (fn [clients-map]
                                                                       (l/enqueue message-chan [:clients-map-updated
                                                                                                :clients-count (count clients-map) :name name])
                                                                       (assoc clients-map name ch))))
                                                     (l/enqueue error-chan [:my-name @my-name :reg-request-ignored name]))
                                              :snd (if-not @my-name (l/enqueue ch [:rcv {:from "chat-server" :word-count 4
                                                                                         :message "please register before messaging"}])
                                                           (if-let [to-chan (or (@clients to) (if (= my-name to) ch))]
                                                             (l/enqueue to-chan [:rcv {:from @my-name :message message
                                                                                       :word-count (count (keep seq (s/split message #" +")))}])
                                                             (l/enqueue error-chan [:from @my-name :user-not-registered to
                                                                                    :message-not-sent message]))))))))]
    (println (str "starting server on port " server-port))
    (tcp/start-tcp-server server-handler {:host server-host :port server-port :frame frame-codec :name "Sunil's Clojure chat-server"})
    (reset! (b/signal-atom "INT") #{#(do (println :handling-sigint)
                                         (broadcast [:bye " "])
                                         (b/raise! "TERM"))})))

(let [mname #(format "%014d" %)
      permitted-alphabets "abcdefghijklmnopqrstuvwxyz"
      rand-word #(apply str (repeatedly (rand-int 15) (fn [] (rand-nth permitted-alphabets))))
      random-alpha-numeric-string #(s/join " " (repeatedly (rand-int 10) rand-word))]
  (defn clients-simulation [server-host server-port num-clients num-test-messages]
    (println (str "starting client spawner pounding chat-server on " server-port))
    (let [print-chan (logging-channel "log/client.message.log")
          clients (vec (pmap #(tcp/tcp-client {:host server-host :port server-port
                                               :frame frame-codec :name (str (mname %) "'s client")})
                             (range num-clients)))
          message (fn [id1 id2 msg]
                    (let [ch1 (l/wait-for-result (clients id1))]
                      (l/enqueue ch1 [:snd {:to (mname id2) :message msg}])))]
      (println "parallel setup of tcp-connections complete !")
      (doseq [c (range num-clients)]
        (let [client (l/wait-for-result (clients c))
              my-name (mname c)]
          (l/receive-all client (fn [[cmd data :as whole]]
                                  (l/enqueue print-chan (str my-name " : " whole))
                                  (if (= cmd :bye) (l/close client))))
          (l/enqueue client [:reg {:name (mname c)}])))
      (println "sleeping for 3 seconds waiting for all the registrations to go through ")
      (Thread/sleep 3000)
      (println "registered " num-clients " users with the chat-server")
      (doseq [test-msg-id (range num-test-messages)]
        (message (rand-int num-clients) (rand-int num-clients) (random-alpha-numeric-string)))
      (println "sent " num-test-messages " random test messages")

      #_(close-logging-channels)
      (println "closing all logging channels"))))

(let [cli-options [["-p" "--port PORT" "Port number" :default 5000 :parse-fn #(Integer/parseInt %)
                    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
                   ["-H" "--host hostname" "host name on which the chat-server is running" :default "localhost"]
                   ["-n" "--num-clients number-of-clients" "number of clients" :default 2 :parse-fn #(Integer/parseInt %)]
                   ["-m" "--num-test-messages number-of-test-messages" :default 10 :parse-fn #(Integer/parseInt %)]
                   ["-s" "--server"] ["-c" "--client"] ["-h" "--help"]]]
  (defn -main [& args]
    (let [{{:keys [host port server client num-clients num-test-messages] :as options} :options help :summary} (cli/parse-opts args cli-options)]
      (cond
       server (start-chat-server host port)
       client (clients-simulation host port num-clients num-test-messages)
       :default (do (println help)
                    (println "Please indicate the mode in which you want to run this application"))))))
