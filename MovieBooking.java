import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class MovieBooking {
    private static BookingSystem bookingSystem = new BookingSystem();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Requests are handled by worker threads. Movie.bookSeat is synchronized.
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", MovieBooking::handleRequest);
        server.start();

        System.out.println("Movie booking is running at http://localhost:8080");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
            String page = Files.readString(Path.of("index.html"));
            send(exchange, 200, "text/html; charset=UTF-8", page);
        } else if (path.equals("/api/movies") && exchange.getRequestMethod().equals("GET")) {
            sendMovies(exchange);
        } else if (path.equals("/api/seats") && exchange.getRequestMethod().equals("GET")) {
            sendSeats(exchange);
        } else if (path.equals("/api/book") && exchange.getRequestMethod().equals("POST")) {
            bookSeat(exchange);
        } else {
            sendJson(exchange, 404, "{\"message\":\"Page not found\"}");
        }
    }

    private static void sendMovies(HttpExchange exchange) throws IOException {
        Movie[] movies = bookingSystem.getMovies();
        String json = "[";

        for (int i = 0; i < movies.length; i++) {
            Movie movie = movies[i];
            json += "{\"id\":" + movie.getId()
                    + ",\"title\":\"" + movie.getTitle()
                    + "\",\"time\":\"" + movie.getTime()
                    + "\",\"available\":" + movie.getAvailableSeats() + "}";

            if (i < movies.length - 1) {
                json += ",";
            }
        }

        json += "]";
        sendJson(exchange, 200, json);
    }

    private static void sendSeats(HttpExchange exchange) throws IOException {
        Map<String, String> query = readForm(exchange.getRequestURI().getRawQuery());
        int movieId = readNumber(query.get("movieId"));
        Movie movie = bookingSystem.getMovie(movieId);

        if (movie == null) {
            sendJson(exchange, 404, "{\"message\":\"Movie not found\"}");
            return;
        }

        sendJson(exchange, 200, movie.getSeatsJson());
    }

    private static void bookSeat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = readForm(body);

        int movieId = readNumber(form.get("movieId"));
        int seatNumber = getSeatNumber(form.get("seat"));
        String customerName = form.get("name");
        Movie movie = bookingSystem.getMovie(movieId);

        if (movie == null || seatNumber == -1 || customerName == null || customerName.trim().isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Enter your name and choose a valid seat\"}");
            return;
        }

        // This synchronized method checks and books the seat as one operation.
        if (movie.bookSeat(seatNumber, customerName.trim())) {
            sendJson(exchange, 200, "{\"message\":\"Seat " + form.get("seat") + " is booked. Enjoy the movie!\"}");
        } else {
            sendJson(exchange, 409, "{\"message\":\"Sorry, that seat was just booked by someone else\"}");
        }
    }

    private static int readNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int getSeatNumber(String seat) {
        if (seat == null || seat.length() < 2) {
            return -1;
        }

        char row = Character.toUpperCase(seat.charAt(0));
        int rowNumber = row - 'A';
        int seatInRow = readNumber(seat.substring(1)) - 1;

        if (rowNumber < 0 || rowNumber >= 5 || seatInRow < 0 || seatInRow >= 8) {
            return -1;
        }

        return rowNumber * 8 + seatInRow;
    }

    private static Map<String, String> readForm(String text) {
        Map<String, String> values = new HashMap<>();

        if (text == null || text.isEmpty()) {
            return values;
        }

        String[] parts = text.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                values.put(key, value);
            }
        }

        return values;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=UTF-8", json);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

class BookingSystem {
    private Movie[] movies = {
        new Movie(1, "After the Rain", "6:15 PM"),
        new Movie(2, "The Last Ticket", "8:30 PM"),
        new Movie(3, "Orbit, Somewhere", "10:00 PM")
    };

    Movie[] getMovies() {
        return movies;
    }

    Movie getMovie(int id) {
        for (Movie movie : movies) {
            if (movie.getId() == id) {
                return movie;
            }
        }
        return null;
    }
}

class Movie {
    private int id;
    private String title;
    private String time;
    private String[] bookedBy = new String[40];

    Movie(int id, String title, String time) {
        this.id = id;
        this.title = title;
        this.time = time;
    }

    int getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    String getTime() {
        return time;
    }

    synchronized boolean bookSeat(int seatNumber, String customerName) {
        if (seatNumber < 0 || seatNumber >= bookedBy.length || bookedBy[seatNumber] != null) {
            return false;
        }

        bookedBy[seatNumber] = customerName;
        return true;
    }

    synchronized int getAvailableSeats() {
        int available = 0;
        for (String customer : bookedBy) {
            if (customer == null) {
                available++;
            }
        }
        return available;
    }

    synchronized String getSeatsJson() {
        String json = "{\"seats\":[";

        for (int i = 0; i < bookedBy.length; i++) {
            char row = (char) ('A' + i / 8);
            int seatInRow = i % 8 + 1;
            String seatName = "" + row + seatInRow;

            json += "{\"id\":\"" + seatName + "\",\"booked\":" + (bookedBy[i] != null) + "}";
            if (i < bookedBy.length - 1) {
                json += ",";
            }
        }

        json += "]}";
        return json;
    }
}
