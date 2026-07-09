import java.util.regex.*;
import java.util.*;
import java.util.stream.*;

public class TestRegex {
    public static void main(String[] args) {
        Map<String, String> hinglish = new HashMap<>();
        hinglish.put("kitna", "price");
        hinglish.put("hi", "greeting");
        hinglish.put("hello", "greeting");
        
        String combined = hinglish.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining(\"|\", \"\\\\b(\", \")\\\\b\"));
                
        Pattern pattern = Pattern.compile(combined);
        String text = "hi";
        Matcher matcher = pattern.matcher(text);
        
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String matchedWord = matcher.group(1);
            String replacement = hinglish.get(matchedWord);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        System.out.println("Mapped: " + sb.toString());
    }
}
