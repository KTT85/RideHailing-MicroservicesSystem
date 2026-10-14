import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.mindrot.jbcrypt.BCrypt;

/** Local seed tool, not an HTTP endpoint. Secrets come only from environment variables. */
class SeedLocalUsers {
    public static void main(String[] args) throws Exception {
        String password = required("SEED_USER_PASSWORD");
        if (password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Seed password must have at least 8 characters and at most 72 UTF-8 bytes");
        }
        String host = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
        String port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
        String username = System.getenv().getOrDefault("MYSQL_USERNAME", "user_app");
        String url = "jdbc:mysql://" + host + ":" + port
                + "/user_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&sslMode=DISABLED&allowPublicKeyRetrieval=true";
        String[][] accounts = {
            {"admin", "admin@ds03.test", "0900000001", "ADMIN"},
            {"Driver One", "driver1@ds03.test", "0900000002", "DRIVER"},
            {"Driver Two", "driver2@ds03.test", "0900000003", "DRIVER"},
            {"Driver Three", "driver3@ds03.test", "0900000004", "DRIVER"},
            {"Passenger One", "passenger1@ds03.test", "0900000005", "PASSENGER"},
            {"Passenger Two", "passenger2@ds03.test", "0900000006", "PASSENGER"}
        };
        String sql = "INSERT INTO users (id,name,email,phone,password_hash,role,status,created_at) "
                + "SELECT ?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6) "
                + "WHERE NOT EXISTS (SELECT 1 FROM users WHERE email=?)";
        int inserted = 0;
        try (Connection connection = DriverManager.getConnection(url, username, required("MYSQL_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (String[] account : accounts) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, account[0]);
                    statement.setString(3, account[1]);
                    statement.setString(4, account[2]);
                    statement.setString(5, BCrypt.hashpw(password, BCrypt.gensalt(10)));
                    statement.setString(6, account[3]);
                    statement.setString(7, account[1]);
                    inserted += statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw new IllegalStateException("Seed failed; transaction rolled back. Check DB connection, schema and unique phone constraints.");
            }
        }
        System.out.println("Inserted " + inserted + " accounts; existing emails were left unchanged.");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }
}
