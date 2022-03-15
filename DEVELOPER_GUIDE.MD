
### Run IndependentPlugin

Run main script using `gradlew run`.

```
./gradlew run
```

This will execute the main script set within the root `build.gradle` file :

```
mainClassName = 'transportservice.RunPlugin'
```
Bound addresses will then be logged to the terminal :

```bash
[main] INFO  transportservice.TransportService - publish_address {127.0.0.1:3333}, bound_addresses {[::1]:3333}, {127.0.0.1:3333}
[main] INFO  transportservice.TransportService - profile [test]: publish_address {127.0.0.1:5555}, bound_addresses {[::1]:5555}, {127.0.0.1:5555}
```

### Run Tests

Run tests :
```
./gradlew clean build test
```

### Send Message using Telnet

To send a message, first run the IndependentPlugin :

```
./gradlew run
```
In another terminal, run : 
```
telnet localhost 5555
```
Once Telnet Client is connected, the terminal will print out :
```
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
```
The original terminal used to run the independent plugin will log the connection request :
```
[opensearch[NettySizeHeaderFrameDecoderTests][transport_worker][T#5]] TRACE transportservice.TcpTransport - Tcp transport channel accepted: Netty4TcpChannel{localAddress=/127.0.0.1:5555, remoteAddress=/127.0.0.1:57302}
[opensearch[NettySizeHeaderFrameDecoderTests][transport_worker][T#5]] TRACE transportservice.netty4.OpenSearchLoggingHandler - [id: 0x8c1cc239, L:/127.0.0.1:5555 - R:/127.0.0.1:57302] REGISTERED
[opensearch[NettySizeHeaderFrameDecoderTests][transport_worker][T#5]] TRACE transportservice.netty4.OpenSearchLoggingHandler - [id: 0x8c1cc239, L:/127.0.0.1:5555 - R:/127.0.0.1:57302] ACTIVE
```
Messages sent through the Telnet Client must begin with an 'ES', for example : 
```
ES1234SHDDF
```
The original terminal will then log the recieved message if format is validated :
```
                 +-------------------------------------------+
                  |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f  |
+----------+-------------------------------------------+----------------+
 |00000000| 45 53 31 32 33 34 53 48 44 44 46 0d 0a|ES1234SHDDF..|
+----------+-------------------------------------------+----------------+
MESSAGE RECEIVED:ES1234SHDDF

REFERENCE LENGTH 13 ES1234SHDDF
```


