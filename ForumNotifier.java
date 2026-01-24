// ForumNotifier.java

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import jakarta.mail.*;
import jakarta.mail.internet.*;

public class ForumNotifier {

    private static final String LAST_MESSAGE_FILE = "last.txt";
    private static final String THREADS_FILE = "threads.txt";
    private static final int PAGES_TO_SCAN = 3;
    private static final int MAX_STORED_MESSAGES = 5000;

    static class ThreadConfig {
        String title;
        String url;
        String messageColor;  // הודעה רגילה
        String replyColor;    // תגובה לציטוט
        String spoilerColor;  // ספוילר

        ThreadConfig(String title, String url, String messageColor, String replyColor, String spoilerColor) {
            this.title = title;
            this.url = url;
            this.messageColor = messageColor;
            this.replyColor = replyColor;
            this.spoilerColor = spoilerColor;
        }
    }

    public static void main(String[] args) {
        try {
            HttpClient client = HttpClient.newHttpClient();

            List<ThreadConfig> threads = readThreads();
            if (threads.isEmpty()) {
                sendEmail(Collections.singletonList(
                        "<div style='color: red; font-weight: bold;'>❌ הקובץ threads.txt ריק או לא תקין.</div>"
                ), "תצורת אשכולות");
                return;
            }

            for (ThreadConfig thread : threads) {

                List<String> allMessages = new ArrayList<>();
                List<String> newMessages;

                int lastPage = getLastPage(client, thread.url);
                if (lastPage == 1) {
                    sendEmail(Collections.singletonList(
                            "<div style='color: red; font-weight: bold;'>❌ לא הצלחתי לתפוס את מספר העמוד מהאשכול: "
                                    + thread.title + "</div>"
                    ), thread.title);
                    continue;
                }

                for (int i = lastPage - PAGES_TO_SCAN + 1; i <= lastPage; i++) {
                    String url = thread.url + "/page-" + i;
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(new URI(url))
                            .GET()
                            .build();

                    HttpResponse<String> response =
                            client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() / 100 == 3) {
                        String newUrl = response.headers().firstValue("Location").orElse(null);
                        if (newUrl != null) {
                            request = HttpRequest.newBuilder()
                                    .uri(new URI(newUrl))
                                    .GET()
                                    .build();
                            response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        }
                    }

                    Document doc = Jsoup.parse(response.body());
                    Elements wrappers = doc.select("div.bbWrapper");

                    for (Element wrapper : wrappers) {
                        
                        Element messageArticle = wrapper.closest("article");
                        if (messageArticle == null ||
                                messageArticle.selectFirst("div.js-selectToQuoteEnd") == null) {
                            continue;
                        }


//                        // ✅ סינון מס' 1
//                        Element parent = wrapper.parent();
//                        if (parent == null || !parent.is("article.message-body.js-selectToQuote")) {
//                            continue;
//                        }

                        // ✅ סינון מס' 2
                        if (wrapper.selectFirst("aside.message-signature") != null ||
                                wrapper.closest("aside.message-signature") != null) {
                            continue;
                        }

                        // ✅ סינון מס' 3
                        if (wrapper.text().contains("כללים למשתתפים באשכול עדכונים זה")) {
                            continue;
                        }

                        // ✅ סינון מס' 4
                        if (!wrapper.select(".perek").isEmpty()) {
                            continue;
                        }

                        Element quote = wrapper.selectFirst("blockquote.bbCodeBlock--quote");
                        Element replyExpand = wrapper.selectFirst("div.bbCodeBlock-expandLink");
                        boolean hasQuote = quote != null && replyExpand != null;

                        Elements spoilers = wrapper.select("div.bbCodeBlock.bbCodeBlock--spoiler");
                        StringBuilder messageBuilder = new StringBuilder();

                        if (hasQuote) {
                            String quoteAuthor = quote.attr("data-quote");
                            Element quoteContent = quote.selectFirst(".bbCodeBlock-content");
                            String quoteText = quoteContent != null ? quoteContent.text().trim() : "";

                            messageBuilder.append("<div style='border: 1px solid #99d6ff; border-radius: 10px; padding: 10px; margin-bottom: 10px; background: ")
                                    .append(thread.replyColor)
                                    .append(";'>")
                                    .append("🌟 <b>ציטוט מאת</b> ")
                                    .append(quoteAuthor)
                                    .append(":<br>")
                                    .append("<i>")
                                    .append(quoteText.replaceAll("\\n", "<br>"))
                                    .append("</i>")
                                    .append("</div>");

                            quote.remove();
                            replyExpand.remove();
                            for (Element spoiler : spoilers) spoiler.remove();

                            String replyText = wrapper.text().trim();
                            if (!replyText.isEmpty()) {
                                messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: ")
                                        .append(thread.messageColor)
                                        .append(";'>")
                                        .append("🗨️ <b>תגובה:</b><br>")
                                        .append(replyText.replaceAll("\\n", "<br>"))
                                        .append("</div>");
                            }

                        } else {
                            for (Element spoiler : spoilers) spoiler.remove();

                            String text = wrapper.text().trim();
                            if (!text.isEmpty()) {
                                messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: ")
                                        .append(thread.messageColor)
                                        .append(";'>")
                                        .append(text.replaceAll("\\n", "<br>"))
                                        .append("</div>");
                            }
                        }

                        for (Element spoiler : spoilers) {
                            Element spoilerTitle = spoiler.selectFirst(".bbCodeBlock-title");
                            Element spoilerContent = spoiler.selectFirst(".bbCodeBlock-content");

                            String title = spoilerTitle != null ? spoilerTitle.text().trim() : "ספוילר";
                            String content = spoilerContent != null ? spoilerContent.text().trim() : "";

                            if (!content.isEmpty()) {
                                messageBuilder.append("<div style='margin-top: 10px; background: ")
                                        .append(thread.spoilerColor)
                                        .append("; border: 1px solid #f5b7b1; padding: 10px; border-radius: 10px;'>")
                                        .append("🤐 <b>")
                                        .append(title)
                                        .append(":</b><br>")
                                        .append("<span style='color: #333;'>")
                                        .append(content.replaceAll("\\n", "<br>"))
                                        .append("</span>")
                                        .append("</div>");
                            }
                        }

                        if (messageBuilder.length() > 0) {
                            allMessages.add(messageBuilder.toString());
                        }
                    }
                }

                newMessages = getNewMessages(allMessages);

                if (!newMessages.isEmpty()) {
                    writeLatestMessages(allMessages);
                    sendEmail(newMessages, thread.title);
                    sendNtfy(newMessages, thread.title); // ← תוספת בלבד
                    System.out.println("✅ המייל נשלח בהצלחה!");

                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ===================== NTFTY – תוספת (משודרג) ===================== */

    private static void sendNtfy(List<String> messages, String threadTitle) {
        try {
            String topic = "forum";
            String url = "https://ntfy.sh/" + topic;

            StringBuilder body = new StringBuilder();

            // כותרת עליונה ברורה כמו "נושא מייל"
            body.append("📬 **הודעות חדשות באשכול:** ").append(threadTitle).append("\n");
            body.append("---\n\n");

            int idx = 1;
            for (String msgHtml : messages) {

                // הפקת "מבנה" מתוך ה-HTML הקיים (בלי לשנות את ה-HTML למייל)
                String formatted = formatMessageForNtfy(msgHtml);

                body.append("### ").append(idx).append(") עדכון\n");
                body.append(formatted).append("\n\n");
                body.append("---\n\n");
                idx++;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Title", "New forum update")
                    .header("Priority", "4")
                    .header("Tags", "speech_balloon") // תג קטלוגי באפליקציה (אם נתמך)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());

        } catch (Exception e) {
            System.err.println("שגיאה בשליחת ntfy: " + e.getMessage());
        }
    }

    // מדמה "כרטיסים" של מייל בעזרת Markdown + סמלים.
    // שים לב: זה עובד על סמך הטקסטים/אמוג'ים שכבר הכנסת ל-HTML:
    // 🌟 ציטוט מאת..., 🗨️ תגובה:, 🤐 ...:
    private static String formatMessageForNtfy(String html) {
        try {
            // ממיר HTML לטקסט עם שימור שורות
            Document d = Jsoup.parse(html);

            // Jsoup כבר יוצר \n סביב block elements, אבל כדי להיות עקביים:
            d.outputSettings(new Document.OutputSettings().prettyPrint(false));
            String text = d.text();

            // אם רוצים לשמר קצת שורות, ננסה "לשחזר" אזורים לפי הסמלים המובנים שלך:
            // נייצר מבנה ידידותי:
            // - אם יש "ציטוט מאת" → נציג כבלוק ציטוט Markdown
            // - אם יש "תגובה:" → נציג כטקסט רגיל מסומן
            // - אם יש "🤐" → נציג כקטע נפרד

            StringBuilder out = new StringBuilder();

            // חלוקה גסה לפי הסמלים ששמת
            // זה לא משנה לוגיקה קיימת, רק מעצב את הפלט ל-ntfy.
            String raw = html.replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .replace("&nbsp;", " ")
                    .trim();

            // ננסה לזהות ציטוט
            if (raw.contains("🌟") && raw.contains("ציטוט מאת")) {
                // דוגמה לטקסט: "🌟 ציטוט מאת X: ... 🗨️ תגובה: ..."
                // נפריד סביב "🗨️ תגובה:"
                String[] parts = raw.split("🗨️\\s*תגובה:");
                String quotePart = parts[0].trim();
                String replyPart = parts.length > 1 ? parts[1].trim() : "";

                // ניקוי כותרת הציטוט
                // נשאיר את שם המצטט/ה כפי שמופיע בטקסט
                out.append("↩️ **תגובה לציטוט**\n\n");
                out.append("> ").append(quotePart.replace("\n", "\n> ")).append("\n\n");

                if (!replyPart.isEmpty()) {
                    out.append("🗨️ **תגובה:**\n");
                    out.append(replyPart).append("\n");
                }
            } else {
                // הודעה רגילה
                out.append("🗨️ **הודעה:**\n");
                out.append(raw).append("\n");
            }

            // זיהוי ספוילר/ים לפי "🤐"
            // אם יש ספוילר, נציג אותו כבלוק מודגש (בלי צבעים)
            if (raw.contains("🤐")) {
                out.append("\n🤐 **ספוילר:**\n");
                // אין לנו דרך להוציא בדיוק רק את התוכן בלי לשנות את המבנה המקורי,
                // אבל לפחות זה מסמן לקורא שיש שם ספוילר.
            }

            return out.toString().trim();

        } catch (Exception e) {
            // fallback: טקסט נקי
            String plain = html.replaceAll("<[^>]+>", "").trim();
            return "🗨️ **הודעה:**\n" + plain;
        }
    }

    /* ========================================================= */

    private static List<ThreadConfig> readThreads() {
        try {
            List<ThreadConfig> threads = new ArrayList<>();
            List<String> lines = Files.readAllLines(Path.of(THREADS_FILE));

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!trimmed.contains("|")) continue;

                String[] parts = trimmed.split("\\|");
                if (parts.length < 5) continue;

                if (parts[0].trim().startsWith("[PAUSED]")) {
                    continue; // מדלג על האשכול
                }

                threads.add(new ThreadConfig(
                        parts[0].trim(),
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[3].trim(),
                        parts[4].trim()
                ));
            }
            return threads;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> readPreviousMessages() {
        try {
            return Files.readAllLines(Path.of(LAST_MESSAGE_FILE));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> getNewMessages(List<String> allMessages) throws IOException {
        Set<String> previousMessages = new HashSet<>(readPreviousMessages());
        List<String> newMessages = new ArrayList<>();

        for (String message : allMessages) {
            String messageId = getMessageId(message);
            if (!previousMessages.contains(messageId)) {
                newMessages.add(message);
            }
        }
        return newMessages;
    }

    private static String getMessageId(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(message.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) hexString.append(String.format("%02x", b));
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 לא נתמך", e);
        }
    }

    private static void writeLatestMessages(List<String> messages) {
        try {
            List<String> existingIds = readPreviousMessages();
            List<String> newIds = new ArrayList<>();

            for (String message : messages) {
                String id = getMessageId(message);
                if (!existingIds.contains(id)) {
                    newIds.add(id);
                }
            }

            List<String> combined = new ArrayList<>(existingIds);
            combined.addAll(newIds);

            int start = Math.max(0, combined.size() - MAX_STORED_MESSAGES);
            Files.write(
                    Path.of(LAST_MESSAGE_FILE),
                    combined.subList(start, combined.size()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

        } catch (IOException e) {
            System.err.println("שגיאה בכתיבת last.txt: " + e.getMessage());
        }
    }

    private static void sendEmail(List<String> messages, String threadTitle) {
        String to = System.getenv("EMAIL_TO");
        String from = System.getenv("EMAIL_FROM");
        String password = System.getenv("EMAIL_PASSWORD");

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            message.setSubject("📬 הודעה מאשכול " + threadTitle);

            StringBuilder emailBody =
                    new StringBuilder("<html><body style='font-family: Arial; direction: rtl;'>");

            for (String msg : messages) {
                emailBody.append("<div style='border: 1px solid #ccc; border-radius: 10px; padding: 10px; margin-bottom: 15px;'>")
                        .append(msg)
                        .append("</div>");
            }
            emailBody.append("</body></html>");

            message.setContent(emailBody.toString(), "text/html; charset=UTF-8");
            Transport.send(message);

        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    private static int getLastPage(HttpClient client, String baseThreadUrl) throws Exception {
        String url = baseThreadUrl + "/page-9999";
        HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 == 3) {
            String newUrl = response.headers().firstValue("Location").orElse(null);
            if (newUrl != null) {
                String[] parts = newUrl.split("page-");
                return Integer.parseInt(parts[1].split("/")[0]);
            }
        }
        return 1;
    }
}
