package de.bwjobs.document;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** Detects MIME types and extracts text from PDF/DOCX/DOC/TXT using Apache Tika. */
@Component
public class TextExtractor {
    private final Tika tika = new Tika();

    public String detect(byte[] data, String filename) {
        return tika.detect(data, filename);
    }

    public String extract(byte[] data) {
        try {
            BodyContentHandler handler = new BodyContentHandler(2_000_000);
            new AutoDetectParser().parse(new ByteArrayInputStream(data), handler, new Metadata(), new ParseContext());
            return handler.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
