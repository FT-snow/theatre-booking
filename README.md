# Reel Room — simple theatre booking

A small Java theatre-seat booking app with a plain HTML/CSS/JavaScript frontend. The Java server also serves the page, so no separate frontend server is needed.

## Run it

Use Java 11 or newer. This workspace has a user-local Java 21 install. In a new terminal, set it up once with:

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21/usr/lib/jvm/java-21-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"
```

From this folder, run:

```text
javac --add-modules jdk.httpserver MovieBooking.java
java --add-modules jdk.httpserver MovieBooking
```

Then open <http://localhost:8080>.

## Where synchronization is used

The server handles requests on multiple worker threads. `Movie.bookSeat` is synchronized, so checking whether a seat is free and booking it happen together. Two requests for the same seat cannot both succeed. The seat list and available-seat count are also read with synchronized methods.

Bookings are kept in memory, so they reset when the server stops. This is a small learning example, not a production booking system.
