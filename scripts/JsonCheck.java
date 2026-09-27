import java.lang.reflect.Method;

public class JsonCheck {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("org.colgram.core.ColgramSubscription");
        Method m = c.getDeclaredMethod("tinyJson", String.class);
        m.setAccessible(true);
        String json = "{\"v\":\"2\",\"add\":\"ru.example.org\",\"port\":\"443\",\"id\":\"abc\"}";
        System.out.println("result=" + m.invoke(null, json));
    }
}
