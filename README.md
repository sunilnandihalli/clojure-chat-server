# chat-server

A simple chat-server written to test the performance of clojure using aleph,lamina and gloss. But it looks like, due to my ignorance,
it is delivering no-where near it's maximum capacity

#Protocol 


REG *ascii 4 bytes*[ *name*]

SND *ascii 4 bytes*[ *name of recipient*][ *msg*]

RCV *ascii 4 bytes*[ *name of sender*][ *ascii 4 bytes word count*][ *msg*]


BYE 0000

Examples

Client -> Server

REG 0008 CHIRAYU

SND 0018 VENKY HELLO WORLD

Server -> Client

RCV 0025 CHIRAYU 0002 HELLO WORLD

BYE 0000


## How to build

lein uberjar

## How to test

### start the server 
    $ java -jar target/chat-server-0.1.0-SNAPSHOT-standalone.jar 
    This starts a chat-server which listens on port 5000
### in a different screen run the testing code written in golang via the following shell-script (you will need golang installed )
    # this opens a number of connections and sends messages to the server running on port 5000
    ./test-chat-server

#### sample test output

The following output says that the tester is going to make 100 connections to the server on localhost:5000 start 100 clients and then send about 100 messages to the server every 100 milli-seconds and then wait for 1000 ms to see how many of the messages come-back and report the loss in number of messages and finally wait for 10 seconds to see how many messages successfully made a round trip in 10 seconds. The following output is saying that of the 58919 messages sent only about 366 made it back within 1 second and only about 27625 messages made it back in 10 seconds. This clearly can't be what clojure can deliver! .. I think there is some problem with the way I am doing things.. 

go run ./chirayu-tester.go -clients=100 -burst_interval=100 -burst=100 -port=5000 -server=localhost -duration=1000 -wait=10000

2014/06/02 02:02:42 ---------------

2014/06/02 02:02:42 server: localhost

2014/06/02 02:02:42 port: 5000

2014/06/02 02:02:42 clients: 100

2014/06/02 02:02:42 duration: 1000

2014/06/02 02:02:42 final wait: 10000

2014/06/02 02:02:42 burst: 100

2014/06/02 02:02:42 burst interval: 100

2014/06/02 02:02:42 ---------------

2014/06/02 02:02:43 Started Testlets 100

2014/06/02 02:02:44 Tx 58919 in 1000 ms

2014/06/02 02:02:44 Rx 366 in 1000 ms

2014/06/02 02:02:44 ---------------

2014/06/02 02:02:44 Waiting to check for message loss 10000 ms

2014/06/02 02:02:54 Final Tx 58920

2014/06/02 02:02:54 Final Rx 27625

2014/06/02 02:02:54 Message Loss 31295


## License

Copyright © 2014 Sunil S Nandihalli

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
