import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AccountAuth {
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final int SESSION_DAYS = 7;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{3,30}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String accountsTable;
    private final String sessionsTable;
    private final String cookieName;

    AccountAuth(String accountsTable, String sessionsTable, String cookieName) {
        this.accountsTable = accountsTable;
        this.sessionsTable = sessionsTable;
        this.cookieName = cookieName;
    }

    void initialize() throws SQLException {
        try (Connection connection = Database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + accountsTable + " ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(30) NOT NULL UNIQUE, "
                    + "password_salt VARCHAR(32) NOT NULL, "
                    + "password_hash VARCHAR(64) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + sessionsTable + " ("
                    + "token_hash CHAR(64) NOT NULL PRIMARY KEY, "
                    + "account_id INT NOT NULL, "
                    + "expires_at TIMESTAMP NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "INDEX " + sessionsTable + "_account_idx (account_id), "
                    + "INDEX " + sessionsTable + "_expiry_idx (expires_at), "
                    + "FOREIGN KEY (account_id) REFERENCES " + accountsTable + "(id) ON DELETE CASCADE)");
        }
    }

    User currentUser(HttpExchange exchange) throws SQLException {
        String token = readCookie(exchange);
        if (token == null) {
            return null;
        }

        String sql = "SELECT a.id, a.username FROM " + sessionsTable + " s JOIN " + accountsTable
                + " a ON a.id = s.account_id WHERE s.token_hash = ? AND s.expires_at > CURRENT_TIMESTAMP";
        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, sha256(token));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new User(result.getInt("id"), result.getString("username"));
            }
        }
    }

    Login register(String rawUsername, String password) throws AuthException, SQLException {
        String username = normalizeUsername(rawUsername);
        validatePassword(password);

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String encodedSalt = Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
        String encodedHash = hashPassword(password, salt);

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + accountsTable + " (username, password_salt, password_hash) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, username);
                insert.setString(2, encodedSalt);
                insert.setString(3, encodedHash);
                insert.executeUpdate();

                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Could not create the account.");
                    }
                    User user = new User(keys.getInt(1), username);
                    Login login = createLogin(connection, user);
                    connection.commit();
                    return login;
                }
            } catch (SQLException e) {
                connection.rollback();
                if (e.getErrorCode() == 1062) {
                    throw new AuthException(409, "That username is already in use.");
                }
                throw e;
            }
        }
    }

    Login login(String rawUsername, String password) throws AuthException, SQLException {
        String username = normalizeUsername(rawUsername);
        if (password == null || password.isEmpty() || password.length() > 128) {
            throw new AuthException(401, "Username or password is incorrect.");
        }

        String sql = "SELECT id, username, password_salt, password_hash FROM " + accountsTable + " WHERE username = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, username);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new AuthException(401, "Username or password is incorrect.");
                }

                String storedSalt = result.getString("password_salt");
                String storedHash = result.getString("password_hash");
                byte[] salt = Base64.getUrlDecoder().decode(storedSalt);
                String candidate = hashPassword(password, salt);
                if (!MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII))) {
                    throw new AuthException(401, "Username or password is incorrect.");
                }

                User user = new User(result.getInt("id"), result.getString("username"));
                return createLogin(connection, user);
            }
        }
    }

    void logout(HttpExchange exchange) throws SQLException {
        String token = readCookie(exchange);
        if (token != null) {
            try (Connection connection = Database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM " + sessionsTable + " WHERE token_hash = ?")) {
                delete.setString(1, sha256(token));
                delete.executeUpdate();
            }
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookieHeader("", "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT", exchange));
    }

    void setLoginCookie(HttpExchange exchange, String token) {
        String expires = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().plus(SESSION_DAYS, ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC));
        String attributes = "; Max-Age=" + (SESSION_DAYS * 24 * 60 * 60) + "; Expires=" + expires;
        exchange.getResponseHeaders().add("Set-Cookie", cookieHeader(token, attributes, exchange));
    }

    private Login createLogin(Connection connection, User user) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String sql = "INSERT INTO " + sessionsTable + " (token_hash, account_id, expires_at) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, sha256(token));
            insert.setInt(2, user.id);
            insert.setTimestamp(3, Timestamp.from(Instant.now().plus(SESSION_DAYS, ChronoUnit.DAYS)));
            insert.executeUpdate();
        }
        return new Login(user, token);
    }

    private String readCookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) {
            return null;
        }
        for (String item : header.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(cookieName)
                    && pair[1].matches("[A-Za-z0-9_-]{40,50}")) {
                return pair[1];
            }
        }
        return null;
    }

    private String cookieHeader(String token, String extraAttributes, HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String secure = "https".equalsIgnoreCase(forwarded) ? "; Secure" : "";
        return cookieName + "=" + token + "; Path=/; HttpOnly; SameSite=Lax" + extraAttributes + secure;
    }

    private static String normalizeUsername(String rawUsername) throws AuthException {
        if (rawUsername == null) {
            throw new AuthException(400, "Enter a username and password.");
        }
        String username = rawUsername.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new AuthException(400, "Use 3–30 letters, numbers, dots, dashes, or underscores for your username.");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) throws AuthException {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new AuthException(400, "Choose a password between 8 and 128 characters.");
        }
    }

    private static String hashPassword(String password, byte[] salt) throws SQLException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new SQLException("Password hashing is unavailable.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String sha256(String value) throws SQLException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new SQLException("Session hashing is unavailable.", e);
        }
    }

    static final class User {
        final int id;
        final String username;

        User(int id, String username) {
            this.id = id;
            this.username = username;
        }
    }

    static final class Login {
        final User user;
        final String token;

        Login(User user, String token) {
            this.user = user;
            this.token = token;
        }
    }

    static final class AuthException extends Exception {
        final int status;

        AuthException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
