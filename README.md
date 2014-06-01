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



## License

Copyright © 2014 Sunil S Nandihalli

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
