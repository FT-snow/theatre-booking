# Reel Room — theatre booking

A small Java theatre-seat booking app with a plain HTML/CSS/JavaScript frontend and MySQL storage. Bookings persist after the server restarts. Theatre and flight bookings use separate tables in the shared `booking_app` database.

## Run it

Use Java 11+ and MySQL. From this folder, start the app; it securely prompts for the MySQL password if `DB_PASSWORD` is not already set:

```bash
./run.sh
```

The app creates the `booking_app` database and its theatre tables on first run. Open <http://localhost:8080>.

`Movie.bookSeat` is synchronized, and the database also has a unique key for each movie/seat pair to prevent duplicate bookings.
