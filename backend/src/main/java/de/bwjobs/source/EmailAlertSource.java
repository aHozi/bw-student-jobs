package de.bwjobs.source;

import de.bwjobs.ai.AlertJobExtractor;
import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Crypto;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Imports jobs from the LinkedIn / StepStone / Indeed job-alert e-mails in a user's own mailbox (IMAP).
 * The folder is opened read-only, only messages from those senders are read, and each message is imported once.
 * Job pages on the platforms are never fetched: jobs keep the alert's title, company, location and link.
 */
@Service
public class EmailAlertSource {
    private static final Logger log = LoggerFactory.getLogger(EmailAlertSource.class);

    private final EmailImportRepository settings;
    private final ExternalJobIngest ingest;
    private final Crypto crypto;
    private final AlertJobExtractor aiExtractor;
    private final SourceRunLog runLog;
    private final AppProperties.Email cfg;

    public EmailAlertSource(EmailImportRepository settings, ExternalJobIngest ingest, Crypto crypto,
                            AlertJobExtractor aiExtractor, SourceRunLog runLog, AppProperties props) {
        this.settings = settings;
        this.ingest = ingest;
        this.crypto = crypto;
        this.aiExtractor = aiExtractor;
        this.runLog = runLog;
        this.cfg = props.sources().email();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public record SyncResult(int messages, int alertMessages, int jobsFound, int accepted, int newJobs) {}

    public record TestResult(boolean ok, int alertMessages, String message) {}

    /** Connects with the given settings and counts recent alert e-mails, without importing anything. */
    public TestResult test(String host, int port, String username, String password, String folderName) {
        try (Store store = connect(host, port, username, password)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            try {
                int alerts = 0;
                for (Message m : recent(folder)) if (platformOf(m) != null) alerts++;
                return new TestResult(true, alerts, "Connected. Found " + alerts + " job-alert e-mail(s) from LinkedIn, StepStone or Indeed in the last "
                        + cfg.firstSyncDays() + " days.");
            } finally {
                folder.close(false);
            }
        } catch (MessagingException e) {
            return new TestResult(false, 0, "Connection failed: " + e.getMessage());
        }
    }

    public SyncResult sync(long userId) {
        EmailImportRepository.Settings s = settings.get(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Connect your mailbox in Settings first"));
        Instant started = Instant.now();
        try (Store store = connect(s.host(), s.port(), s.username(), crypto.decryptString(s.passwordEnc()))) {
            Folder folder = store.getFolder(s.folder());
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uids = (UIDFolder) folder;
                long validity = uids.getUIDValidity();
                Message[] messages = s.lastUid() == null || s.uidValidity() == null || s.uidValidity() != validity
                        ? recent(folder)
                        : uids.getMessagesByUID(s.lastUid() + 1, UIDFolder.MAXUID);
                long maxUid = s.lastUid() == null || s.uidValidity() == null || s.uidValidity() != validity ? 0 : s.lastUid();
                int alertMessages = 0;
                List<ExternalJob> jobs = new ArrayList<>();
                Set<String> imported = new LinkedHashSet<>();
                for (Message m : messages) {
                    long uid = uids.getUID(m);
                    if (uid > maxUid) maxUid = uid;
                    String platform = platformOf(m);
                    if (platform == null) continue;
                    String messageId = messageId(m, validity, uid);
                    if (settings.isSeen(userId, messageId) || !imported.add(messageId)) continue;
                    alertMessages++;
                    String html = body(m);
                    List<AlertEmailParser.ParsedJob> parsed = AlertEmailParser.parse(platform, html);
                    if (parsed.isEmpty()) parsed = aiExtractor.extract(userId, platform, html);
                    Instant received = m.getReceivedDate() == null ? Instant.now() : m.getReceivedDate().toInstant();
                    for (AlertEmailParser.ParsedJob p : parsed) {
                        jobs.add(new ExternalJob("EMAIL_ALERT", p.platform(), p.externalId(), p.title(), p.company(),
                                p.location() == null ? List.of() : List.of(p.location()), p.url(), p.url(), null, received, userId, true));
                    }
                }
                ExternalJobIngest.Stats stats = ingest.ingest(jobs);
                imported.forEach(id -> settings.markSeen(userId, id)); // only after the jobs are stored
                settings.updateSync(userId, validity, maxUid == 0 ? null : maxUid, null);
                runLog.insert("EMAIL_ALERT", userId, started, jobs.size(), stats.accepted(), stats.newJobs(), null);
                return new SyncResult(messages.length, alertMessages, jobs.size(), stats.accepted(), stats.newJobs());
            } finally {
                folder.close(false);
            }
        } catch (MessagingException e) {
            String msg = "Mailbox error: " + e.getMessage();
            log.warn("E-mail import failed for user {}: {}", userId, e.getMessage());
            settings.updateSync(userId, null, null, msg);
            runLog.insert("EMAIL_ALERT", userId, started, 0, 0, 0, msg);
            throw new ApiException(HttpStatus.BAD_GATEWAY, msg);
        }
    }

    private Message[] recent(Folder folder) throws MessagingException {
        Date since = Date.from(Instant.now().minus(cfg.firstSyncDays(), ChronoUnit.DAYS));
        return folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
    }

    static Store connect(String host, int port, String username, String password) throws MessagingException {
        boolean ssl = port == 993;
        String protocol = ssl ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".connectiontimeout", "15000");
        p.put("mail." + protocol + ".timeout", "30000");
        if (!ssl) p.put("mail.imap.starttls.enable", "true");
        Store store = Session.getInstance(p).getStore(protocol);
        store.connect(host, port, username, password);
        return store;
    }

    private static String platformOf(Message m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from == null) return null;
        for (Address a : from) {
            String addr = a instanceof InternetAddress ia ? ia.getAddress() : a.toString();
            String p = AlertEmailParser.platformOf(addr);
            if (p != null) return p;
        }
        return null;
    }

    private static String messageId(Message m, long validity, long uid) throws MessagingException {
        String[] ids = m.getHeader("Message-ID");
        return ids != null && ids.length > 0 ? ids[0] : validity + ":" + uid;
    }

    /** HTML body if present, otherwise the plain text body wrapped in <pre>. */
    static String body(Part p) throws MessagingException {
        try {
            String html = find(p, "text/html");
            if (html != null) return html;
            String text = find(p, "text/plain");
            return text == null ? "" : "<pre>" + org.jsoup.nodes.Entities.escape(text) + "</pre>";
        } catch (java.io.IOException e) {
            throw new MessagingException("Cannot read message body", e);
        }
    }

    private static String find(Part p, String type) throws MessagingException, java.io.IOException {
        if (p.isMimeType(type) && p.getContent() instanceof String s) return s;
        if (p.isMimeType("multipart/*") && p.getContent() instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String r = find(mp.getBodyPart(i), type);
                if (r != null) return r;
            }
        }
        return null;
    }
}
