import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class MovieBooking {
    private static BookingSystem bookingSystem = new BookingSystem();
    private static AccountAuth auth = new AccountAuth("theatre_accounts", "theatre_sessions", "theatre_session");

    public static void main(String[] args) throws IOException, SQLException {
        bookingSystem.initialize();
        auth.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", MovieBooking::handleRequest);
        server.start();

        System.out.println("Theatre booking is running at http://localhost:8080");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "text/html; charset=UTF-8", Files.readString(Path.of("index.html")));
            } else if (path.equals("/api/register") && exchange.getRequestMethod().equals("POST")) {
                authenticate(exchange, true);
            } else if (path.equals("/api/login") && exchange.getRequestMethod().equals("POST")) {
                authenticate(exchange, false);
            } else if (path.equals("/api/session") && exchange.getRequestMethod().equals("GET")) {
                sendSession(exchange);
            } else if (path.equals("/api/logout") && exchange.getRequestMethod().equals("POST")) {
                auth.logout(exchange);
                sendJson(exchange, 200, "{\"message\":\"Signed out\"}");
            } else if (path.startsWith("/api/")) {
                AccountAuth.User user = auth.currentUser(exchange);
                if (user == null) {
                    sendJson(exchange, 401, "{\"message\":\"Please sign in to continue.\"}");
                } else if (path.equals("/api/movies") && exchange.getRequestMethod().equals("GET")) {
                    sendMovies(exchange);
                } else if (path.equals("/api/seats") && exchange.getRequestMethod().equals("GET")) {
                    sendSeats(exchange);
                } else if (path.equals("/api/book") && exchange.getRequestMethod().equals("POST")) {
                    bookSeat(exchange, user);
                } else {
                    sendJson(exchange, 404, "{\"message\":\"Page not found\"}");
                }
            } else {
                sendJson(exchange, 404, "{\"message\":\"Page not found\"}");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Database error. Check the MySQL settings.\"}");
        }
    }

    private static void authenticate(HttpExchange exchange, boolean register) throws IOException, SQLException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = readForm(body);
        try {
            AccountAuth.Login login = register
                    ? auth.register(form.get("username"), form.get("password"))
                    : auth.login(form.get("username"), form.get("password"));
            auth.setLoginCookie(exchange, login.token);
            sendJson(exchange, register ? 201 : 200, "{\"authenticated\":true,\"username\":\""
                    + login.user.username + "\"}");
        } catch (AccountAuth.AuthException e) {
            sendJson(exchange, e.status, "{\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private static void sendSession(HttpExchange exchange) throws IOException, SQLException {
        AccountAuth.User user = auth.currentUser(exchange);
        if (user == null) {
            sendJson(exchange, 200, "{\"authenticated\":false}");
        } else {
            sendJson(exchange, 200, "{\"authenticated\":true,\"username\":\"" + user.username + "\"}");
        }
    }

    private static void sendMovies(HttpExchange exchange) throws IOException, SQLException {
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

    private static void sendSeats(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> query = readForm(exchange.getRequestURI().getRawQuery());
        int movieId = readNumber(query.get("movieId"));
        Movie movie = bookingSystem.getMovie(movieId);

        if (movie == null) {
            sendJson(exchange, 404, "{\"message\":\"Movie not found\"}");
            return;
        }

        sendJson(exchange, 200, movie.getSeatsJson());
    }

    private static void bookSeat(HttpExchange exchange, AccountAuth.User user) throws IOException, SQLException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = readForm(body);

        int movieId = readNumber(form.get("movieId"));
        int seatIndex = getSeatNumber(form.get("seat"));
        String customerName = form.get("name");
        Movie movie = bookingSystem.getMovie(movieId);

        if (movie == null || seatIndex == -1 || customerName == null || customerName.trim().isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Enter your name and choose a valid seat\"}");
            return;
        }

        if (movie.bookSeat(seatIndex, customerName.trim(), user.id)) {
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
        new Movie(1, "After the Rain", "6:15 PM", 40),
        new Movie(2, "The Last Ticket", "8:30 PM", 40),
        new Movie(3, "Orbit, Somewhere", "10:00 PM", 40)
    };

    void initialize() throws SQLException {
        try (Connection connection = Database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS theatre_movies ("
                    + "id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, "
                    + "show_time VARCHAR(30) NOT NULL, total_seats INT NOT NULL)");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT IGNORE INTO theatre_movies (id, title, show_time, total_seats) VALUES (?, ?, ?, ?)")) {
                for (Movie movie : movies) {
                    insert.setInt(1, movie.getId());
                    insert.setString(2, movie.getTitle());
                    insert.setString(3, movie.getTime());
                    insert.setInt(4, movie.getTotalSeats());
                    insert.executeUpdate();
                }
            }

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS theatre_bookings ("
                    + "movie_id INT NOT NULL, seat_index INT NOT NULL, customer_name VARCHAR(100) NOT NULL, "
                    + "account_id INT NULL, booked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (movie_id, seat_index), "
                    + "FOREIGN KEY (movie_id) REFERENCES theatre_movies(id))");

            try {
                statement.executeUpdate("ALTER TABLE theatre_bookings ADD COLUMN account_id INT NULL");
            } catch (SQLException e) {
                if (e.getErrorCode() != 1060) {
                    throw e;
                }
            }
        }
    }

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
    private int totalSeats;

    Movie(int id, String title, String time, int totalSeats) {
        this.id = id;
        this.title = title;
        this.time = time;
        this.totalSeats = totalSeats;
    }

    int getId() { return id; }
    String getTitle() { return title; }
    String getTime() { return time; }
    int getTotalSeats() { return totalSeats; }

    synchronized boolean bookSeat(int seatIndex, String customerName, int accountId) throws SQLException {
        if (seatIndex < 0 || seatIndex >= totalSeats) {
            return false;
        }

        try (Connection connection = Database.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO theatre_bookings (movie_id, seat_index, customer_name, account_id) VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setInt(2, seatIndex);
            insert.setString(3, customerName);
            insert.setInt(4, accountId);
            insert.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw e;
        }
    }

    synchronized int getAvailableSeats() throws SQLException {
        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM theatre_bookings WHERE movie_id = ?")) {
            query.setInt(1, id);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return totalSeats - result.getInt(1);
            }
        }
    }

    synchronized String getSeatsJson() throws SQLException {
        boolean[] booked = new boolean[totalSeats];

        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT seat_index FROM theatre_bookings WHERE movie_id = ?")) {
            query.setInt(1, id);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    int seatIndex = result.getInt("seat_index");
                    if (seatIndex >= 0 && seatIndex < totalSeats) {
                        booked[seatIndex] = true;
                    }
                }
            }
        }

        String json = "{\"seats\":[";
        for (int i = 0; i < totalSeats; i++) {
            char row = (char) ('A' + i / 8);
            String seatName = "" + row + (i % 8 + 1);
            json += "{\"id\":\"" + seatName + "\",\"booked\":" + booked[i] + "}";
            if (i < totalSeats - 1) {
                json += ",";
            }
        }
        return json + "]}";
    }
}

class Database {
    static Connection getConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || url.isBlank()) {
            url = "jdbc:mysql://localhost:3306/booking_app?createDatabaseIfNotExist=true&serverTimezone=UTC";
        }
        if (user == null || user.isBlank()) {
            user = "root";
        }
        if (password == null) {
            throw new SQLException("Set the DB_PASSWORD environment variable before starting the app.");
        }

        return DriverManager.getConnection(url, user, password);
    }
}
