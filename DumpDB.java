import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DumpDB {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://10.118.165.16:5432/chatcrmdb";
        String user = "u0_a425";
        String password = "Root@123";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            System.out.println("=== Tenant Info ===");
            ResultSet rs = stmt.executeQuery("SELECT id, business_name FROM tenants WHERE id = 'a0c988c1-3f27-42ae-99bd-ce07eb13b68f'");
            while (rs.next()) {
                System.out.printf("id=%s, business_name='%s'%n",
                        rs.getString("id"), rs.getString("business_name"));
            }
            rs.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
