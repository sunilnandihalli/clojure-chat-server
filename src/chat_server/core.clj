(ns chat-server.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alt! alts!! go]]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import [java.net InetAddress ServerSocket Socket SocketException InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels AsynchronousSocketChannel AsynchronousServerSocketChannel CompletionHandler]))

(defn scaffold [iface]
  "this code is from Christophe Grand .. but very usefull.. so I chose to include.."
  (doseq [[iface methods] (->> iface .getMethods
                               (map #(vector (.getName (.getDeclaringClass %))
                                             (symbol (.getName %))
                                             (count (.getParameterTypes %))))
                               (group-by first))]
    (println (str "  " iface))
    (doseq [[_ name argcount] methods]
      (println
       (str "    "
                (list name (into ['this] (take argcount (repeatedly gensym)))))))))

(defn- server-socket [server]
  (ServerSocket.
   (:port server)
   (:backlog server)
   (InetAddress/getByName (:host server))))

(defn tcp-server
  "Create a new TCP server. Takes the following keyword arguments:
    :host    - the host to bind to (defaults to 127.0.0.1)
    :port    - the port to bind to
    :handler - a function to handle incoming connections, expects a socket as
               an argument
    :backlog - the maximum backlog of connections to keep (defaults to 50)"
  [& {:as options}]
  {:pre [(:port options)
         (:handler options)]}
  (merge
   {:host "127.0.0.1"
    :backlog 50
    :socket (atom nil)
    :connections (agent {})}
   options))

(defn close-socket [server socket]
  (swap! (:connections server) disj socket)
  (when-not (.isClosed socket)
    (.close socket)))

(defn- open-server-socket [server]
  (reset! (:socket server)
          (server-socket server)))

(defn- accept-connection
  [{:keys [handler connections socket] :as server}]
  (let [conn (.accept @socket)]
    (go
      (try (handler conn connections)
           (finally (close-socket server conn))))))

(defn running?
  "True if the server is running."
  [server]
  (if-let [socket @(:socket server)]
    (not (.isClosed socket))))

(defn start
  "Start a TCP server going."
  [server]
  (open-server-socket server)
  (future
    (while (running? server)
      (try
        (accept-connection server)
        (catch SocketException _)))))

(defn stop
  "Stop the TCP server and close all open connections."
  [server]
  (doseq [socket @(:connections server)]
    (close-socket server socket))
  (.close @(:socket server)))
(defn read-int [str] (Integer/parseInt str))
(defn write-int "write a four byte zero padded ascii int" [i] (format "%04d" i))
(defn count-words [message]
  (count (s/split message #" +")))
(defn server-response-format [{:keys [to-name message]}]
  (let [resp (str (write-int (count-words message)) " " message)
        len (+ 1 (count resp))] (str "RCV " (write-int len) resp)))
(defn server-bye-format [] "BYE 0000")

(let [str-cmd {"SND" :send "RCV" :recieve "REG" :register "BYE" :bye}]
  (defn parse-cmd [cmd-str]
    (let [[cmd len] (s/split cmd-str)]
      [(str-cmd cmd) (read-int len)])))

(defn tokenize
  ([s]
     (s/split str #" +" 2))
  ([s n]
     (s/split str #" +" n)))

(defn parse-name [str] (tokenize (s/triml str)))
(defn parse-data [cmd str]
  (assoc (case cmd
           :send (let [[name rest-of-str] (parse-name str)] {:to-name name :message rest-of-str})
           :register (let [[name] (parse-name str)] {:name name})
           :recieve (let [[name word-count message] (tokenize str 3)]
                      {:from-name name :word-count (read-int word-count) :message message})
           :bye {}) :cmd cmd))


(defn handler [conn connections]
  (go
   (with-open [input (.getInputStream conn)
               output (.getOutputStream conn)]
     (with-open [reader (io/reader input)
                 writer (io/writer output)]
       (let [to-chan (chan)
             msend (fn [to message]
                     (let [{to-chan :output-chan} (@connections to)]
                       (when to-chan
                         (>! to-chan (server-response-format to message)))))
             mregister (fn [name] (send connections assoc name {:conn conn :to-chan to-chan}))])))))

;; server functions


;; client functions
(let [permitted-alphabets "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*"
      rand-word #(apply str (repeatedly (rand-int 15) (rand-nth permitted-alphabets)))]
  (defn random-alpha-numeric-string []
    (s/join " " (repeatedly (rand-int 10) rand-word))))
(defn client-send-format [to-name message]
  (let [send-str (str to-name " " message)]
    (str "SND " (write-int (inc (count send-str))) " " send-str)))
(defn client-register-format [name]
  (str "REG " (write-int (inc (count name))) " " name))
(defn client-parse-message [])
(defn recieve [from message])
(defn server-connection-teardown [client])
(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [server (tcp-server :port 5000 :handler handler)]
    (start server)))
